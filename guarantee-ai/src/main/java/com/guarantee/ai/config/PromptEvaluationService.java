package com.guarantee.ai.config;

import com.guarantee.ai.config.mapper.AiPromptVersionMapper;
import com.guarantee.ai.config.mapper.PromptEvaluationMapper;
import com.guarantee.ai.service.AiChatService;
import com.guarantee.ai.service.BusinessAssistantPrompt;
import com.guarantee.common.exception.BizException;
import jakarta.annotation.PreDestroy;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 固定数据上的真实模型行为评测。运行生产工具循环，但绝不执行真实业务工具。 */
@Service
public class PromptEvaluationService {
    public static final String SUITE_VERSION = "order-time-v1";
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 21);
    private final PromptEvaluationMapper tasks;
    private final AiPromptVersionMapper versions;
    private final AiConfigService configs;
    private final AiChatService chat;
    private final ChatModel model;
    private final Environment environment;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final ExecutorService executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new SynchronousQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "prompt-evaluation");
                thread.setDaemon(true);
                return thread;
            });

    public PromptEvaluationService(PromptEvaluationMapper tasks, AiPromptVersionMapper versions,
            AiConfigService configs, AiChatService chat, ChatModel model, Environment environment,
            ObjectMapper json, PlatformTransactionManager transactionManager) {
        this.tasks = tasks; this.versions = versions; this.configs = configs; this.chat = chat;
        this.model = model; this.environment = environment; this.json = json;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public PromptEvaluation start(int versionNo, String expectedHash, String operator) {
        AiConfigSnapshot config = configs.refreshIfStale();
        PromptEvaluation task = tx.execute(status -> {
            versions.lockControl();
            tasks.expire();
            if (tasks.running() > 0) throw BizException.badRequest("已有候选评测运行中，请稍后重试");
            AiPromptVersion candidate = versions.lockVersion(versionNo);
            if (candidate == null || !AiPromptVersion.STATUS_DRAFT.equals(candidate.getStatus()))
                throw BizException.badRequest("评测目标必须是已保存的草稿");
            if (!Objects.equals(expectedHash, candidate.getContentHash()))
                throw BizException.badRequest("草稿已变化，请刷新后重新评测");
            PromptComposition.requireSnapshot(candidate);
            PromptEvaluation row = new PromptEvaluation();
            row.setId(UUID.randomUUID().toString()); row.setVersionNo(versionNo);
            row.setContent(candidate.getContent()); row.setContentHash(candidate.getContentHash());
            AiPromptVersion published = versions.selectPublished();
            row.setBaselineContent(published == null ? builtin() : published.getContent());
            row.setContextSnapshot(contextSnapshot(config)); row.setContextHash(contextHash(config)); row.setSuiteVersion(SUITE_VERSION);
            row.setStatus("RUNNING"); row.setSummary("正在评测候选版与当前生效版（固定数据，不执行业务写操作）");
            row.setCreatedBy(operator); tasks.insert(row);
            return row;
        });
        try {
            executor.execute(() -> run(task, config));
        } catch (RejectedExecutionException ex) {
            task.setStatus("ERROR"); task.setSummary("后台执行器忙或已停止，请重新评测"); tasks.finish(task);
        }
        return view(task.getId());
    }

    public PromptEvaluation view(String id) {
        tasks.expire();
        PromptEvaluation row = tasks.select(id);
        if (row == null) throw BizException.badRequest("评测任务不存在");
        return row;
    }

    public PromptEvaluation latest(int versionNo) { tasks.expire(); return tasks.latest(versionNo); }

    public String requirePassed(AiPromptVersion version, String evaluationId) {
        PromptComposition.requireSnapshot(version);
        PromptEvaluation task = evaluationId == null ? latest(version.getVersionNo()) : view(evaluationId);
        if (task == null || !"PASSED".equals(task.getStatus()))
            throw BizException.badRequest("候选评测未通过或未完成，请先评测此版本");
        if (!Objects.equals(task.getVersionNo(), version.getVersionNo())
                || !Objects.equals(task.getContentHash(), version.getContentHash())
                || !Objects.equals(task.getContent(), version.getContent())
                || !SUITE_VERSION.equals(task.getSuiteVersion())
                || !contextHash(configs.refreshIfStale()).equals(task.getContextHash())
                || task.getFinishedAt() == null || task.getFinishedAt().isBefore(LocalDateTime.now().minusHours(24)))
            throw BizException.badRequest("评测结果已失效：正文、配置、应用或测试集已变化，或结果超过24小时，请重新评测");
        return task.getId();
    }

    private void run(PromptEvaluation task, AiConfigSnapshot config) {
        try {
            List<Map<String, Object>> results = new ArrayList<>();
            String[] questions = {"订单有多少？", "上个月订单有多少？", "查全部历史订单", "上个月"};
            for (int i = 0; i < questions.length; i++) {
                List<Message> history = i == 3 ? List.of(new UserMessage("订单有多少？"),
                        new AssistantMessage("请问要统计哪个时间范围的订单？")) : List.of();
                AiChatService.EvaluationAnswer candidate = chat.evaluateCandidate(task.getContent(), questions[i], history, config, TODAY);
                AiChatService.EvaluationAnswer baseline = chat.evaluateCandidate(task.getBaselineContent(), questions[i], history, config, TODAY);
                boolean passed = accepts(i, candidate);
                results.add(Map.of("question", questions[i], "passed", passed,
                        "candidate", candidate, "baseline", baseline,
                        "expectation", i == 0 ? "询问时间范围且不查询订单" : i == 2
                                ? "查询全部历史，不设置日期边界，回答包含37" : "查询2026-09-01至2026-09-30，回答包含37"));
            }
            task.setResults(json.writeValueAsString(results));
            long passed = results.stream().filter(row -> Boolean.TRUE.equals(row.get("passed"))).count();
            task.setStatus(passed == results.size() ? "PASSED" : "FAILED");
            task.setSummary("候选行为评测 " + passed + "/" + results.size() + " 通过；固定日期=" + TODAY
                    + "，固定数据工具；服务端鉴权回归由CI执行");
        } catch (Exception ex) {
            // 不将提供商响应、凭据或业务数据写入页面错误说明。
            task.setStatus("ERROR"); task.setSummary("评测未完成（模型不可用、超时或工具异常），请检查服务日志并重新评测");
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("候选评测失败 id={} type={}", task.getId(), ex.getClass().getSimpleName());
        }
        tasks.finish(task);
    }

    boolean accepts(int index, AiChatService.EvaluationAnswer answer) {
        var orders = answer.calls().stream().filter(call -> "queryOrderSummary".equals(call.name())).toList();
        if (answer.calls().stream().anyMatch(call -> !Set.of("queryOrderSummary", "getCurrentDate").contains(call.name()))) return false;
        if (index == 0) return orders.isEmpty() && answer.answer().matches("(?s).*(时间|日期|周期|月份|哪.*月).*" )
                && !answer.answer().matches("(?s).*\\d+\\s*(笔|单|条).*" );
        if (orders.isEmpty() || !answer.answer().matches("(?s).*(?<![0-9])37(?![0-9]).*")) return false;
        return orders.stream().allMatch(call -> {
            var args = json.readTree(call.arguments());
            String start = args.path("startDate").asText("");
            String end = args.path("endDate").asText("");
            return index == 2 ? start.isBlank() && end.isBlank()
                    : "2026-09-01".equals(start) && "2026-09-30".equals(end);
        });
    }

    String contextHash(AiConfigSnapshot config) { return PromptVersionService.sha256(contextSnapshot(config)); }

    private String contextSnapshot(AiConfigSnapshot config) {
        TreeMap<String, String> values = new TreeMap<>(config.effectiveValues());
        values.remove(AiConfigCatalog.PROMPT_ACTIVE_VERSION);
        var options = model.getOptions();
        values.put("defaults.model", String.valueOf(options == null ? null : options.getModel()));
        values.put("defaults.temperature", String.valueOf(options == null ? null : options.getTemperature()));
        values.put("defaults.maxTokens", String.valueOf(options == null ? null : options.getMaxTokens()));
        values.put("provider", environment.getProperty("spring.ai.openai.base-url", ""));
        values.put("suite", SUITE_VERSION);
        values.put("platformBaseVersion", PromptComposition.base().version());
        values.put("platformBaseHash", PromptComposition.base().contentHash());
        values.put("implementation", implementationHash());
        return json.writeValueAsString(values);
    }

    private static String implementationHash() {
        try {
            StringBuilder bytes = new StringBuilder();
            for (Class<?> type : List.of(AiChatService.class, BusinessAssistantPrompt.class, PromptEvaluationService.class,
                    com.guarantee.ai.tool.AiToolRegistry.class, com.guarantee.ai.tool.OrderSummaryTool.class,
                    com.guarantee.ai.time.TimeSemanticParser.class)) {
                try (var in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                    bytes.append(HexFormat.of().formatHex(in.readAllBytes()));
                }
            }
            return PromptVersionService.sha256(bytes.toString());
        } catch (java.io.IOException ex) { throw new IllegalStateException("无法识别评测应用版本", ex); }
    }

    static String builtin() {
        try (var in = new org.springframework.core.io.ClassPathResource("prompts/business-assistant.st").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
    }

    @PreDestroy public void shutdown() { executor.shutdownNow(); }
}
