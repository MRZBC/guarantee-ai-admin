package com.guarantee.ai.analysis;

import com.guarantee.ai.config.*;
import com.guarantee.analysis.dto.OrderTrendQuery;
import com.guarantee.analysis.service.OrderReportService;
import com.guarantee.analysis.vo.*;
import com.guarantee.common.exception.BizException;
import com.guarantee.common.trace.TraceContext;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

/** 独立的只读分析场景：模型只选择证据，事实与导航全部由服务端生成。 */
@Service
public class AnalysisSummaryService {
    public static final String TEMPLATE_VERSION = "analysis-summary-v1";
    private static final Logger log = LoggerFactory.getLogger(AnalysisSummaryService.class);
    private final OrderReportService reports;
    private final AiConfigService configs;
    private final ChatModel model;
    private final ObjectMapper json;
    private final String template;
    private final Set<Long> users = ConcurrentHashMap.newKeySet();
    private final Semaphore slots = new Semaphore(4);
    private final ExecutorService executor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new SynchronousQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "analysis-summary");
                thread.setDaemon(true);
                return thread;
            });

    public record Request(OrderTrendQuery criteria, String reportFingerprint) { }
    public record Evidence(String id, String kind, String text, Map<String, Object> drilldown) { }
    public record Observation(String text, List<String> evidenceIds) { }
    public record Summary(String status, OrderReportVO report, String reportFingerprint,
                          String generatedAt, List<Observation> observations, List<Evidence> evidence,
                          List<String> limitations, String message, String traceId) { }

    public AnalysisSummaryService(OrderReportService reports, AiConfigService configs, ChatModel model, ObjectMapper json) {
        this.reports = reports; this.configs = configs; this.model = model; this.json = json;
        try (var in = new ClassPathResource("prompts/analysis-summary-v1.st").getInputStream()) {
            this.template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ex) { throw new IllegalStateException("分析解读模板无法读取", ex); }
    }

    public Summary generate(Long userId, Request request) {
        validate(request);
        if (!users.add(userId)) throw BizException.badRequest("已有解读正在生成，请稍后重试");
        if (!slots.tryAcquire()) {
            users.remove(userId);
            throw BizException.badRequest("解读服务繁忙，请稍后重试");
        }
        boolean submitted = false;
        long started = System.nanoTime();
        String traceId = TraceContext.currentTraceId();
        String outcome = "REPORT_ERROR";
        String fingerprint = "";
        try {
            // 此服务不启事务，report 自己的只读事务在返回时已结束。
            OrderReportVO report = reports.report(request.criteria());
            fingerprint = report.getReportFingerprint();
            List<Evidence> evidence = evidence(report);
            if (!fingerprint.equals(request.reportFingerprint())) {
                outcome = "REPORT_CHANGED";
                return result(outcome, report, evidence, List.of(), "数据已更新，请重新解读", traceId);
            }
            if (report.comparison().orderCount().current().signum() == 0) {
                outcome = "NO_DATA";
                return result(outcome, report, evidence, List.of(), "本期无订单，未调用模型；请结合比较期和数据覆盖范围核查。", traceId);
            }
            AiConfigSnapshot config = configs.refreshIfStale();
            long timeout = Math.min(60_000L, config.findLong(AiConfigCatalog.MODEL_TIMEOUT).orElse(60_000L));
            Future<List<Observation>> task = executor.submit(() -> {
                try { return interpret(evidence, config, timeout, traceId); }
                finally { release(userId); }
            });
            submitted = true;
            try {
                List<Observation> observations = task.get(timeout, TimeUnit.MILLISECONDS);
                outcome = "GENERATED";
                return result(outcome, report, evidence, observations, "已根据本次报告整理关注点", traceId);
            } catch (TimeoutException ex) {
                task.cancel(true);
                outcome = "TIMEOUT";
            } catch (InterruptedException ex) {
                task.cancel(true);
                Thread.currentThread().interrupt();
                outcome = "CANCELLED";
            } catch (ExecutionException ex) {
                outcome = "MODEL_OR_OUTPUT_ERROR";
            }
            return result("FACTS_ONLY", report, evidence, List.of(),
                    "TIMEOUT".equals(outcome) ? "解读超时，以下仅展示服务端统计事实。" : "解读未完成，以下仅展示服务端统计事实。", traceId);
        } finally {
            if (!submitted) release(userId);
            log.info("scene=analysis-summary user={} traceId={} report={} template={} status={} elapsedMs={}",
                    userId, traceId, fingerprint, TEMPLATE_VERSION, outcome, (System.nanoTime() - started) / 1_000_000);
        }
    }

    private void release(Long userId) { users.remove(userId); slots.release(); }

    static void validate(Request request) {
        if (request == null || request.criteria() == null || request.reportFingerprint() == null
                || !request.reportFingerprint().matches("[a-f0-9]{64}"))
            throw BizException.badRequest("缺少有效报告，请先查询后解读");
        var c = request.criteria();
        if (c.getStartDate() == null || c.getEndDate() == null)
            throw BizException.badRequest("解读必须指定已应用的完整日期区间");
        if (!Set.of("TENDER", "PERFORMANCE").contains(c.getOrderType() == null ? "" : c.getOrderType()))
            throw BizException.badRequest("订单类型必须为 TENDER 或 PERFORMANCE");
        if (c.getGranularity() == null || !Set.of("day", "month", "year").contains(c.getGranularity().toLowerCase(Locale.ROOT)))
            throw BizException.badRequest("统计粒度无效");
        if (c.getStartDate().getYear() < 1001 || c.getEndDate().getYear() > 9999
                || c.getEndDate().isBefore(c.getStartDate()) || c.getStartDate().plusDays(365).isBefore(c.getEndDate())
                || (c.getOrgId() != null && c.getOrgId() <= 0)
                || (c.getRegionCode() != null && !c.getRegionCode().matches("[0-9]{6}")))
            throw BizException.badRequest("分析筛选条件无效");
    }

    private List<Observation> interpret(List<Evidence> evidence, AiConfigSnapshot config, long timeout, String traceId) {
        var builder = OpenAiChatOptions.builder().toolChoice("none")
                .toolCallbacks(List.of()).timeout(Duration.ofMillis(timeout)).maxRetries(0)
                .maxTokens(Math.min(1024, config.getInt(AiConfigCatalog.MODEL_MAX_TOKENS)));
        if (config.isOverridden(AiConfigCatalog.MODEL_NAME)) builder.model(config.get(AiConfigCatalog.MODEL_NAME));
        if (config.isOverridden(AiConfigCatalog.MODEL_TEMPERATURE)) builder.temperature(config.getDouble(AiConfigCatalog.MODEL_TEMPERATURE));
        // 模型只收到可信的编号、类型、事实文字；无导航信息、订单明细或其他会话。
        String input = json.writeValueAsString(evidence.stream().map(e -> Map.of("id", e.id(), "kind", e.kind(), "fact", e.text())).toList());
        ChatResponse response = model.call(new Prompt(List.of(new SystemMessage(template), new UserMessage(input)), builder.build()));
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null
                || response.getResult().getOutput().hasToolCalls()) throw new IllegalArgumentException("无有效模型结果");
        var usage = response.getMetadata().getUsage();
        log.info("scene=analysis-summary-model traceId={} template={} configVersion={} model={} tokens={}",
                traceId, TEMPLATE_VERSION, config.version(), response.getMetadata().getModel(), usage == null ? null : usage.getTotalTokens());
        return parse(response.getResult().getOutput().getText(), evidence);
    }

    List<Observation> parse(String text, List<Evidence> evidence) {
        if (text == null || text.length() > 4096) throw new IllegalArgumentException("输出超限");
        var root = json.readTree(text);
        if (!root.isObject() || root.size() != 1 || !root.has("evidenceIds") || !root.get("evidenceIds").isArray())
            throw new IllegalArgumentException("输出结构无效");
        var ids = root.get("evidenceIds");
        if (ids.size() < 3 || ids.size() > 5) throw new IllegalArgumentException("关注点数量无效");
        Map<String, Evidence> allowed = new LinkedHashMap<>();
        evidence.forEach(e -> allowed.put(e.id(), e));
        Set<String> seen = new HashSet<>();
        List<Observation> result = new ArrayList<>();
        boolean hasChange = false;
        for (var id : ids) {
            if (!id.isTextual() || !allowed.containsKey(id.asText()) || !seen.add(id.asText()))
                throw new IllegalArgumentException("引用无效");
            Evidence e = allowed.get(id.asText());
            hasChange |= "CHANGE".equals(e.kind());
            result.add(new Observation(e.text(), List.of(e.id())));
        }
        if (!hasChange) throw new IllegalArgumentException("缺少总体变化依据");
        return List.copyOf(result);
    }

    static List<Evidence> evidence(OrderReportVO r) {
        List<Evidence> facts = new ArrayList<>();
        var c = r.comparison();
        Map<String, Object> current = route(c, c.criteria().getStartDate(), c.criteria().getEndDate());
        facts.add(new Evidence("orders", "CHANGE", metric("订单数", "笔", c.orderCount()), current));
        facts.add(new Evidence("guarantee", "CHANGE", metric("担保金额", "元", c.guaranteeAmount()), current));
        facts.add(new Evidence("premium", "CHANGE", metric("保费金额", "元", c.premiumAmount()), current));
        facts.add(new Evidence("previous", "PERIOD", "查看比较期订单（" + c.previousStartDate() + " 至 " + c.previousEndDate() + "）。",
                route(c, c.previousStartDate(), c.previousEndDate())));
        int i = 0;
        for (var row : r.regions().stream().limit(10).toList()) {
            Map<String, Object> route = new LinkedHashMap<>(current);
            if (row.getRegionCode() != null && row.getRegionCode().matches("[0-9]{6}")) {
                route.put("regionCode", row.getRegionCode()); route.put("regionExact", true);
            } else route.clear();
            facts.add(new Evidence("region-" + i++, "DISTRIBUTION", "本期地区Top 10（非全量）中，" + name(row.getRegionName())
                    + "订单数为" + row.getOrderCount() + "笔，担保金额为" + number(row.getGuaranteeAmount()) + "元。", route));
        }
        i = 0;
        for (var row : r.insurances().stream().limit(10).toList()) {
            Map<String, Object> route = new LinkedHashMap<>(current);
            if (row.getInsuranceTypeId() != null && row.getInsuranceTypeId() > 0) route.put("insuranceTypeId", row.getInsuranceTypeId());
            else route.clear();
            facts.add(new Evidence("insurance-" + i++, "DISTRIBUTION", "本期险种返回列表中，" + name(row.getTypeName())
                    + "订单数为" + row.getOrderCount() + "笔，担保金额为" + number(row.getGuaranteeAmount()) + "元。", route));
        }
        i = 0;
        for (var row : r.institutions().stream().limit(10).toList()) {
            Map<String, Object> route = new LinkedHashMap<>(current);
            if (row.getOrgId() != null && row.getOrgId() > 0 && row.getRegionCode() != null && row.getRegionCode().matches("[0-9]{6}")) {
                route.put("orgId", row.getOrgId()); route.put("regionCode", row.getRegionCode()); route.put("regionExact", true);
            } else route.clear();
            facts.add(new Evidence("institution-" + i++, "DISTRIBUTION", "本期机构与地区Top 10（非全量）中，"
                    + name(row.getOrgName()) + " · " + name(row.getRegionName()) + "订单数为" + row.getOrderCount()
                    + "笔，担保金额为" + number(row.getGuaranteeAmount()) + "元。", route));
        }
        return List.copyOf(facts);
    }

    private static String name(String value) {
        if (value == null || value.isBlank()) return "未知分类";
        return value.length() > 80 ? value.substring(0, 80) + "…（名称截断）" : value;
    }
    private static String number(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
    private static String metric(String label, String unit, OrderComparisonVO.Metric m) {
        String change = "NEW".equals(m.status()) ? "较上期新增（上期为零，不计算变化率）"
                : "UNCHANGED".equals(m.status()) ? "与上期无变化"
                : "较上期" + (m.change().signum() < 0 ? "减少" : "增加") + number(m.change().abs()) + unit
                + "，变化率" + number(m.changeRate()) + "%";
        return label + "本期" + number(m.current()) + unit + "，上期" + number(m.previous()) + unit + "；" + change + "。";
    }
    private static Map<String, Object> route(OrderComparisonVO c, LocalDate start, LocalDate end) {
        Map<String, Object> route = new LinkedHashMap<>();
        route.put("startDate", start.toString()); route.put("endDate", end.toString());
        if (c.criteria().getRegionCode() != null) route.put("regionCode", c.criteria().getRegionCode());
        if (c.criteria().getOrgId() != null) route.put("orgId", c.criteria().getOrgId());
        return route;
    }
    private static Summary result(String status, OrderReportVO r, List<Evidence> evidence,
                                  List<Observation> observations, String message, String traceId) {
        List<String> limitations = new ArrayList<>();
        if (!r.comparison().comparisonComplete()) limitations.add("对比数据可能不完整，不能据此生成异常或风险结论。");
        if (r.comparison().changeAlert()) limitations.add("订单数达到既有变化提示阈值；变化提示不代表风险评级或原因判断。");
        limitations.add("仅描述总体变化和本期分布，缺少分类上期数据，无法判断各分类对涨跌的贡献或原因。");
        limitations.add("地区及机构与地区仅Top 10，险种最多取返回列表前10项（实际返回" + r.insurances().size() + "项），均不据此推算全量占比。");
        limitations.add("统计为生成时结果；查看订单时会重新查询，数据可能继续变化。");
        return new Summary(status, r, r.getReportFingerprint(), r.generatedAt().toString(), observations,
                evidence, List.copyOf(limitations), message, traceId);
    }

    @PreDestroy
    public void close() { executor.shutdownNow(); }
}
