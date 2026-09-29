package com.guarantee.web.ai;

import com.guarantee.ai.dto.AiChatRequest;
import com.guarantee.ai.entity.AiToolCall;
import com.guarantee.ai.mapper.AiToolCallMapper;
import com.guarantee.ai.service.AiChatService;
import com.guarantee.common.security.CurrentUser;
import com.guarantee.common.security.Permissions;
import com.guarantee.common.security.Roles;
import com.guarantee.order.dto.OrderSummaryCriteria;
import com.guarantee.order.service.OrderStatisticsService;
import com.guarantee.system.service.UserService;
import com.guarantee.web.GuaranteeAiAdminApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 确定性评测集（REQ-MCP-07）：用 Stub ChatModel 跑黄金问题集的**服务端事实**部分。
 *
 * <p>与 live 集的分工：live 用真实模型校验"模型措辞与行为"，需要 API Key；本类用脚本化
 * Stub 校验<b>服务端事实</b>——工具真的被调用、口径行/知识来源行由服务端追加、
 * 未收录不追加来源行、伪造来源行被剥离、权限裁剪生效、数值与服务端摘要一致。
 * 因此它**不需要 Key**，但需要 MySQL；失败即 `mvn verify` 失败（发布门禁，AC-MCP-11）。</p>
 *
 * <p>结果写入 `guarantee-web/target/eval/deterministic-report.json`（与
 * `scripts/ai-golden-questions.mjs --suite=deterministic` 的报告同一 schema），
 * 脚本据此输出 Markdown/JSON 报告并做基线 diff。</p>
 *
 * <p><b>场景 id 必须与脚本的 `DETERMINISTIC_IDS` 完全一致</b>：脚本的 `--self-check`
 * 会读本文件源码交叉校验，两边漂移直接报错。</p>
 *
 * <p>开关：`-Dguarantee.ai.eval.deterministic-in-verify=false` 可临时跳过本类
 * （默认参与 `mvn verify`）。</p>
 */
@SpringBootTest(
        classes = {GuaranteeAiAdminApplication.class, EvaluationDeterministicIT.StubConfig.class},
        properties = {"guarantee.data-init.enabled=false"})
class EvaluationDeterministicIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class StubConfig {

        @Bean
        @Primary
        ChatModel scriptedEvalChatModel() {
            return new ScriptedEvalChatModel();
        }
    }

    /** 默认参与 verify；置 false 可临时跳过。 */
    public static boolean evalEnabled() {
        return !"false".equalsIgnoreCase(
                System.getProperty("guarantee.ai.eval.deterministic-in-verify", "true"));
    }

    private static final String REPORT_DIR = "target/eval";
    private static final String REPORT_JSON = REPORT_DIR + "/deterministic-report.json";
    private static final String REPORT_MD = REPORT_DIR + "/deterministic-report.md";

    /** 逐场景结果（同一 schema 给脚本用），@AfterAll 统一落盘。 */
    private static final List<Map<String, Object>> RESULTS = new ArrayList<>();

    @Autowired
    private AiChatService aiChatService;

    @Autowired
    private AiToolCallMapper aiToolCallMapper;

    @Autowired
    private OrderStatisticsService orderStatisticsService;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ChatModel chatModel;

    @BeforeEach
    void setUp() {
        CurrentUser.clear();
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    @AfterAll
    static void writeReport() throws IOException {
        Path dir = Path.of(REPORT_DIR);
        Files.createDirectories(dir);
        Map<String, Object> report = buildReport();
        // 不依赖 pretty-print API（不同 Jackson 版本命名有差异）：紧凑 JSON 足够机器消费
        Files.writeString(dir.resolve("deterministic-report.json"),
                new tools.jackson.databind.ObjectMapper().writeValueAsString(report)
                        + System.lineSeparator(),
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("deterministic-report.md"), renderMarkdown(report),
                StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // 场景
    // ------------------------------------------------------------------

    private record ToolCallSpec(String name, String arguments) {
    }

    private record Expect(List<String> tools,
                          boolean dataSourceLine,
                          List<String> knowledgeSourceAny,
                          boolean noKnowledgeSource,
                          List<String> toolResultNotContains,
                          List<String> answerNotContains) {
    }

    private record Scenario(String id, String category, String question, List<String> permissions,
                            List<ToolCallSpec> calls, String answer, OrderSummaryCriteria crossCheck,
                            Expect expect) {
    }

    private static final Pattern KNOWLEDGE_SOURCE = Pattern.compile("知识来源：([^\\r\\n]*)");

    private static List<Scenario> scenarios() {
        List<Scenario> list = new ArrayList<>();
        // ---- 单维度统计：工具链路 + 服务端摘要（数值与直接调 Service 一致）----
        list.add(new Scenario("GQ-07", "单维度统计",
                "2026 年第二季度投标保函的订单量、担保金额和保费分别是多少？",
                admin(),
                List.of(new ToolCallSpec("queryOrderSummary",
                        "{\"orderType\":\"TENDER\",\"startDate\":\"2026-04-01\",\"endDate\":\"2026-06-30\"}")),
                "订单量、担保金额与保费如上。",
                criteria("TENDER", "2026-04-01", "2026-06-30"),
                new Expect(List.of("queryOrderSummary"), true, List.of(), false, List.of(), List.of())));
        list.add(new Scenario("GQ-26", "单维度统计",
                "2026 年第一季度投标订单的订单量和担保金额分别是多少？",
                admin(),
                List.of(new ToolCallSpec("queryOrderSummary",
                        "{\"orderType\":\"TENDER\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-03-31\"}")),
                "第一季度投标订单的订单量与担保金额如上。",
                criteria("TENDER", "2026-01-01", "2026-03-31"),
                new Expect(List.of("queryOrderSummary"), true, List.of(), false, List.of(), List.of())));
        // ---- 空结果：如实说明 + 口径行 ----
        list.add(new Scenario("GQ-12", "降级/失败",
                "2025 年 1 月西藏自治区的投标订单有多少？",
                admin(),
                List.of(new ToolCallSpec("queryOrderSummary",
                        "{\"orderType\":\"TENDER\",\"startDate\":\"2025-01-01\",\"endDate\":\"2025-01-31\",\"regionCode\":\"540000\"}")),
                "该条件下没有查到订单（0 条）。",
                null,
                new Expect(List.of("queryOrderSummary"), true, List.of(), false, List.of(), List.of())));
        // ---- 知识类：来源行由服务端追加（含"伪造来源行被剥离"）----
        list.add(new Scenario("GQ-16", "定义/知识类", "停用和删除有什么区别？",
                admin(),
                List.of(new ToolCallSpec("queryBusinessKnowledge", "{\"query\":\"停用和删除有什么区别\"}")),
                "停用与删除的区别如上。\n\n知识来源：KB-ORDER-9999《编造的条目》v9",
                null,
                new Expect(List.of("queryBusinessKnowledge"), false,
                        List.of("KB-SYSTEM-0009", "KB-SYSTEM-0011"), false, List.of(),
                        List.of("KB-ORDER-9999", "编造的条目"))));
        list.add(new Scenario("GQ-17", "定义/知识类", "保额区间的口径是怎么规定的？只讲规定，不要给统计数字。",
                admin(),
                List.of(new ToolCallSpec("queryBusinessKnowledge", "{\"query\":\"保额区间的口径是怎么规定的\"}")),
                "保额区间的口径如上（不填 = 不限）。",
                null,
                new Expect(List.of("queryBusinessKnowledge"), false, List.of("KB-ORDER-0001"), false,
                        List.of(), List.of("数据摘要（服务端生成）"))));
        list.add(new Scenario("GQ-18", "定义/知识类", "区域编码的层级前缀匹配是什么意思？选省和选市有什么区别？",
                admin(),
                List.of(new ToolCallSpec("queryBusinessKnowledge", "{\"query\":\"区域编码的层级前缀匹配\"}")),
                "区域编码按层级前缀匹配，选省市粒度不同。",
                null,
                new Expect(List.of("queryBusinessKnowledge"), false, List.of("KB-ORDER-0002"), false,
                        List.of(), List.of())));
        list.add(new Scenario("GQ-19", "定义/知识类", "逻辑删除是什么意思？删除之后还能恢复吗？",
                admin(),
                List.of(new ToolCallSpec("queryBusinessKnowledge", "{\"query\":\"逻辑删除是什么意思\"}")),
                "逻辑删除可以恢复，与停用不同。",
                null,
                new Expect(List.of("queryBusinessKnowledge"), false,
                        List.of("KB-SYSTEM-0009", "KB-SYSTEM-0011"), false, List.of(), List.of())));
        list.add(new Scenario("GQ-33", "定义/知识类", "投标保函和履约保函有什么区别？",
                admin(),
                List.of(new ToolCallSpec("queryBusinessKnowledge", "{\"query\":\"投标保函和履约保函有什么区别\"}")),
                "投标保函与履约保函的差别如上。",
                null,
                new Expect(List.of("queryBusinessKnowledge"), false, List.of("KB-ORDER-0003"), false,
                        List.of(), List.of())));
        // ---- 混合：知识来源行 + 数据口径行并存 ----
        list.add(new Scenario("GQ-20", "定义/知识类混合",
                "保额区间的规则是怎么规定的？另外，平台上「投标保函（标准）」现在配置的区间是多少？",
                admin(),
                List.of(new ToolCallSpec("queryBusinessKnowledge", "{\"query\":\"保额区间的规则\"}"),
                        new ToolCallSpec("queryInsuranceType", "{\"keyword\":\"投标保函（标准）\"}")),
                "规则如上；当前配置见险种数据。",
                null,
                new Expect(List.of("queryBusinessKnowledge", "queryInsuranceType"), true,
                        List.of("KB-ORDER-0001"), false, List.of(), List.of())));
        list.add(new Scenario("GQ-21", "定义/知识类混合",
                "险种的基准费率口径是什么？顺便告诉我「投标保函（标准）」现在的基准费率是多少。",
                admin(),
                List.of(new ToolCallSpec("queryBusinessKnowledge", "{\"query\":\"险种的基准费率口径\"}"),
                        new ToolCallSpec("queryInsuranceType", "{\"keyword\":\"投标保函（标准）\"}")),
                "费率口径如上；当前费率见险种数据。",
                null,
                new Expect(List.of("queryBusinessKnowledge", "queryInsuranceType"), true,
                        List.of("KB-ORDER-0003"), false, List.of(), List.of())));
        // ---- 未收录：不得出现来源行 ----
        list.add(new Scenario("GQ-22", "定义/知识类未收录", "保证金退还流程是怎样的？",
                admin(),
                List.of(new ToolCallSpec("queryBusinessKnowledge", "{\"query\":\"保证金退还流程是怎样的\"}")),
                "知识库未收录这条。",
                null,
                new Expect(List.of("queryBusinessKnowledge"), false, List.of(), true, List.of(), List.of())));
        // ---- 越权：审计口径条目不返回（AC-RAG-06）----
        list.add(new Scenario("GQ-24", "定义/知识类越权", "操作审计记录里敏感字段是怎么记录的？",
                List.of(Permissions.AI_CHAT),
                List.of(new ToolCallSpec("queryBusinessKnowledge", "{\"query\":\"操作审计记录里敏感字段是怎么记录的\"}")),
                "我按可用的知识作答。",
                null,
                new Expect(List.of("queryBusinessKnowledge"), false, List.of(), false,
                        List.of("KB-SYSTEM-0010", "操作审计记录的内容与渠道"),
                        List.of("KB-SYSTEM-0010", "操作审计记录的内容与渠道"))));
        return list;
    }

    private static List<String> admin() {
        return List.of(Permissions.AI_CHAT, Permissions.AI_SYSTEM_QUERY, Permissions.AI_SYSTEM_WRITE,
                Permissions.ORG_VIEW, Permissions.DEPT_VIEW, Permissions.USER_VIEW, Permissions.ROLE_VIEW,
                Permissions.INSURANCE_VIEW, Permissions.AUDIT_VIEW, Permissions.AI_DEBUG_VIEW);
    }

    private static OrderSummaryCriteria criteria(String orderType, String from, String to) {
        OrderSummaryCriteria criteria = new OrderSummaryCriteria();
        criteria.setOrderType(orderType);
        criteria.setStartDate(java.time.LocalDate.parse(from));
        criteria.setEndDate(java.time.LocalDate.parse(to));
        return criteria;
    }

    // ------------------------------------------------------------------
    // 用例（一条场景一个测试，全部执行完再落盘）
    // ------------------------------------------------------------------

    @Test
    @EnabledIf("evalEnabled")
    @DisplayName("GQ-07 单维度统计：工具链路 + 服务端摘要与直接调 Service 一致")
    void gq07() {
        runScenario(scenarioById("GQ-07"));
    }

    @Test
    @EnabledIf("evalEnabled")
    @DisplayName("GQ-12 空结果：如实说明 + 口径行")
    void gq12() {
        runScenario(scenarioById("GQ-12"));
    }

    @Test
    @EnabledIf("evalEnabled")
    @DisplayName("GQ-16 知识：来源行由服务端追加，模型自写的伪造来源行被剥离")
    void gq16() {
        runScenario(scenarioById("GQ-16"));
    }

    @Test
    @EnabledIf("evalEnabled")
    @DisplayName("GQ-17 知识：定义类只走知识检索，不误用业务工具")
    void gq17() {
        runScenario(scenarioById("GQ-17"));
    }

    @Test
    @EnabledIf("evalEnabled")
    @DisplayName("GQ-18 知识：区域前缀语义来源行")
    void gq18() {
        runScenario(scenarioById("GQ-18"));
    }

    @Test
    @EnabledIf("evalEnabled")
    @DisplayName("GQ-19 知识：逻辑删除来源行")
    void gq19() {
        runScenario(scenarioById("GQ-19"));
    }

    @Test
    @EnabledIf("evalEnabled")
    @DisplayName("GQ-20 混合：知识来源行与数据口径行并存")
    void gq20() {
        runScenario(scenarioById("GQ-20"));
    }

    @Test
    @EnabledIf("evalEnabled")
    @DisplayName("GQ-21 混合：知识来源行与数据口径行并存")
    void gq21() {
        runScenario(scenarioById("GQ-21"));
    }

    @Test
    @EnabledIf("evalEnabled")
    @DisplayName("GQ-22 未收录：不得出现知识来源行")
    void gq22() {
        runScenario(scenarioById("GQ-22"));
    }

    @Test
    @EnabledIf("evalEnabled")
    @DisplayName("GQ-24 越权：审计口径条目不返回、不暗示存在")
    void gq24() {
        runScenario(scenarioById("GQ-24"));
    }

    @Test
    @EnabledIf("evalEnabled")
    @DisplayName("GQ-26 单维度统计：工具链路 + 服务端摘要一致")
    void gq26() {
        runScenario(scenarioById("GQ-26"));
    }

    @Test
    @EnabledIf("evalEnabled")
    @DisplayName("GQ-33 知识：引用完整（有检索必有来源行）")
    void gq33() {
        runScenario(scenarioById("GQ-33"));
    }

    private static Scenario scenarioById(String id) {
        return scenarios().stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("未定义的确定性场景：" + id));
    }

    // ------------------------------------------------------------------
    // 场景执行 + 服务端事实断言
    // ------------------------------------------------------------------

    private void runScenario(Scenario scenario) {
        long startedAt = System.currentTimeMillis();
        setPrincipal(scenario.permissions());
        ((ScriptedEvalChatModel) chatModel).script(scenario.calls(), scenario.answer());

        AiChatRequest request = new AiChatRequest();
        request.setMessage(scenario.question());
        List<ServerSentEvent<String>> events = aiChatService.stream(adminUserId(), request)
                .collectList().block(Duration.ofSeconds(120));
        assertThat(events).as("%s：SSE 流不为空", scenario.id()).isNotNull().isNotEmpty();
        long conversationId = ((Number) firstData(events, "meta").get("conversationId")).longValue();

        String content = assistantContent(conversationId);
        List<AiToolCall> toolCalls = aiToolCallMapper.selectByConversationId(conversationId);
        List<String> reasons = new ArrayList<>();

        // ① 工具真的被调用且成功
        for (String tool : scenario.expect().tools()) {
            boolean ok = toolCalls.stream()
                    .anyMatch(call -> tool.equals(call.getToolName()) && "SUCCESS".equals(call.getStatus()));
            if (!ok) {
                reasons.add("没有成功调用必需的工具 " + tool);
            }
        }
        // ② 口径行：有成功工具调用 ⇒ 必须有
        boolean dataSourceLine = content.contains("口径：") || content.contains("口径:");
        if (scenario.expect().dataSourceLine() && !dataSourceLine) {
            reasons.add("有成功的工具调用，但正文里没有口径行");
        }
        // ③ 知识来源行：要么命中期望条目，要么明确"不得有"
        String sourceLine = extractKnowledgeSource(content);
        if (!scenario.expect().knowledgeSourceAny().isEmpty()) {
            boolean hit = scenario.expect().knowledgeSourceAny().stream()
                    .anyMatch(id -> sourceLine != null && sourceLine.contains(id));
            if (!hit) {
                reasons.add("知识来源行未命中期望条目 " + scenario.expect().knowledgeSourceAny()
                        + "（实际：" + sourceLine + "）");
            }
        }
        if (scenario.expect().noKnowledgeSource() && sourceLine != null) {
            reasons.add("未收录类问题却出现了知识来源行：" + sourceLine);
        }
        // ④ 工具返回值里不得出现的条目（权限裁剪 / 存在性）
        if (!scenario.expect().toolResultNotContains().isEmpty()) {
            String toolResult = toolCalls.stream().map(AiToolCall::getResult)
                    .reduce("", (a, b) -> a + "\n" + (b == null ? "" : b));
            for (String forbidden : scenario.expect().toolResultNotContains()) {
                if (toolResult.contains(forbidden)) {
                    reasons.add("工具返回值里出现了不该出现的条目：" + forbidden);
                }
            }
        }
        // ⑤ 正文不得出现的内容（伪造来源行等）
        for (String forbidden : scenario.expect().answerNotContains()) {
            if (content.contains(forbidden)) {
                reasons.add("正文里出现了不该出现的内容：" + forbidden);
            }
        }
        // ⑥ 数值与服务端一致（订单汇总类）
        if (scenario.crossCheck() != null) {
            long expectedCount = orderStatisticsService.summarize(scenario.crossCheck()).getOrderCount();
            if (!content.contains(String.valueOf(expectedCount))) {
                reasons.add("正文里没有直接调用 Service 得到的订单量 " + expectedCount);
            }
        }
        // ⑦ 事件集合不变
        List<String> allowedEvents = List.of("meta", "delta", "reset", "tool_call", "done");
        for (ServerSentEvent<String> event : events) {
            if (!allowedEvents.contains(event.event())) {
                reasons.add("出现未知 SSE 事件类型：" + event.event());
            }
        }

        long elapsedMs = System.currentTimeMillis() - startedAt;
        boolean pass = reasons.isEmpty();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", scenario.id());
        row.put("category", scenario.category());
        row.put("question", scenario.question());
        row.put("status", pass ? "pass" : "fail");
        row.put("reasons", reasons);
        row.put("tools", toolCalls.stream().map(c -> c.getToolName() + ":" + c.getStatus()).toList());
        row.put("toolCalls", toolCalls.size());
        row.put("rounds", scenario.calls().isEmpty() ? 1 : 2);
        row.put("elapsedMs", elapsedMs);
        row.put("answerChars", content.length());
        row.put("score", Map.of(
                // 口径行只该由**业务数据工具**触发；知识检索返回的 dataSource 进知识来源行
                "dataSourceLineExpected", toolCalls.stream()
                        .anyMatch(c -> !"queryBusinessKnowledge".equals(c.getToolName())),
                "dataSourceLinePresent", dataSourceLine,
                // 只有"要求有来源行"的场景才计入引用完整率（未收录/越权场景本就不该有）
                "knowledgeSourceExpected", !scenario.expect().knowledgeSourceAny().isEmpty(),
                "knowledgeSourcePresent", sourceLine != null,
                "forbiddenViolations", 0));
        RESULTS.add(row);

        assertThat(reasons).as("%s（%s）的服务端事实", scenario.id(), scenario.question()).isEmpty();
    }

    // ------------------------------------------------------------------
    // 报告（schema 与 scripts/ai-golden-questions.mjs 一致）
    // ------------------------------------------------------------------

    private static Map<String, Object> buildReport() {
        List<Map<String, Object>> results = List.copyOf(RESULTS);
        int passed = (int) results.stream().filter(r -> "pass".equals(r.get("status"))).count();
        int failed = (int) results.stream().filter(r -> "fail".equals(r.get("status"))).count();
        int notRun = (int) results.stream().filter(r -> "not-run".equals(r.get("status"))).count();

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("passRate", results.isEmpty() ? null
                : round4(passed / (double) results.size()));
        long dsExpected = results.stream().filter(r -> scoreBool(r, "dataSourceLineExpected")).count();
        long dsPresent = results.stream().filter(r -> scoreBool(r, "dataSourceLinePresent")).count();
        metrics.put("dataSourceConsistencyRate", dsExpected == 0 ? null : round4(dsPresent / (double) dsExpected));
        long citeExpected = results.stream().filter(r -> scoreBool(r, "knowledgeSourceExpected")).count();
        long citePresent = results.stream().filter(r -> scoreBool(r, "knowledgeSourcePresent")).count();
        metrics.put("citationCompletenessRate", citeExpected == 0 ? null : round4(citePresent / (double) citeExpected));
        metrics.put("forbiddenTermViolations", 0);
        metrics.put("rounds", stats(results, "rounds"));
        metrics.put("elapsedMs", stats(results, "elapsedMs"));
        metrics.put("toolCalls", stats(results, "toolCalls"));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", "ai-golden-questions/deterministic@1");
        report.put("generatedAt", java.time.OffsetDateTime.now().toString());
        report.put("suite", "deterministic");
        report.put("mode", "Stub ChatModel（无需 API Key，需 MySQL）");
        report.put("note", "确定性集只校验服务端事实（工具调用/口径行/来源行/权限裁剪/数值一致），"
                + "不校验模型措辞——措辞属 live 集");
        report.put("totals", Map.of("total", results.size(), "passed", passed, "failed", failed, "notRun", notRun));
        report.put("metrics", metrics);
        report.put("results", results);
        return report;
    }

    @SuppressWarnings("unchecked")
    private static boolean scoreBool(Map<String, Object> row, String key) {
        Object score = row.get("score");
        return score instanceof Map<?, ?> map && Boolean.TRUE.equals(((Map<String, Object>) map).get(key));
    }

    private static double round4(double value) {
        return Math.round(value * 10000) / 10000.0;
    }

    private static Map<String, Object> stats(List<Map<String, Object>> results, String key) {
        List<Double> values = results.stream()
                .map(r -> r.get(key))
                .filter(Number.class::isInstance)
                .map(v -> ((Number) v).doubleValue())
                .toList();
        Map<String, Object> stats = new LinkedHashMap<>();
        if (values.isEmpty()) {
            stats.put("min", null);
            stats.put("max", null);
            stats.put("avg", null);
            return stats;
        }
        double sum = values.stream().mapToDouble(Double::doubleValue).sum();
        stats.put("min", values.stream().mapToDouble(Double::doubleValue).min().orElse(0));
        stats.put("max", values.stream().mapToDouble(Double::doubleValue).max().orElse(0));
        stats.put("avg", Math.round(sum / values.size() * 10) / 10.0);
        return stats;
    }

    private static String renderMarkdown(Map<String, Object> report) {
        StringBuilder sb = new StringBuilder();
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) report.get("totals");
        sb.append("# 确定性评测集报告（Stub ChatModel）\n\n");
        sb.append("> 生成时间：").append(report.get("generatedAt")).append('\n');
        sb.append("> 通过 ").append(totals.get("passed")).append('/').append(totals.get("total"))
                .append("，失败 ").append(totals.get("failed")).append("，未跑 ").append(totals.get("notRun")).append("\n\n");
        sb.append("> ").append(report.get("note")).append("\n\n");
        sb.append("| 编号 | 类别 | 结果 | 工具调用 | 轮次 | 耗时(ms) | 备注 |\n|---|---|---|---|---|---|---|\n");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) report.get("results");
        for (Map<String, Object> row : results) {
            String mark = "pass".equals(row.get("status")) ? "✅" : "❌";
            sb.append("| ").append(row.get("id")).append(" | ").append(row.get("category")).append(" | ")
                    .append(mark).append(" | ").append(row.get("toolCalls")).append(" | ")
                    .append(row.get("rounds")).append(" | ").append(row.get("elapsedMs")).append(" | ")
                    .append(String.join("；", ((List<?>) row.get("reasons")).stream().map(Object::toString).toList()))
                    .append(" |\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private void setPrincipal(List<String> permissions) {
        CurrentUser.set(new CurrentUser.Principal(adminUserId(), "admin", "超级管理员",
                List.of(Roles.ADMIN), permissions));
    }

    private Long adminUserId() {
        return jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = 'admin'", Long.class);
    }

    private String assistantContent(long conversationId) {
        String content = jdbcTemplate.queryForObject(
                "SELECT content FROM ai_message WHERE conversation_id = ? AND role = 'ASSISTANT' "
                        + "ORDER BY id DESC LIMIT 1", String.class, conversationId);
        assertThat(content).isNotNull();
        return content;
    }

    private static String extractKnowledgeSource(String content) {
        Matcher matcher = KNOWLEDGE_SOURCE.matcher(content);
        return matcher.find() ? matcher.group(1) : null;
    }

    private Map<String, Object> firstData(List<ServerSentEvent<String>> events, String eventName) {
        String payload = events.stream()
                .filter(e -> eventName.equals(e.event()))
                .map(ServerSentEvent::data)
                .findFirst()
                .orElseThrow(() -> new AssertionError("未收到 SSE 事件: " + eventName));
        return objectMapper.readValue(payload, Map.class);
    }

    /**
     * 可编排的 Stub ChatModel：第一轮按脚本发起工具调用，第二轮返回脚本正文。
     *
     * <p>与 live 集的差别：这里**不模拟模型措辞**，只驱动"模型会发起哪些工具调用"，
     * 于是被校验的对象是服务端自己产出的事实（口径行/来源行/权限裁剪）。</p>
     */
    static final class ScriptedEvalChatModel implements ChatModel {

        private record Script(List<ToolCallSpec> calls, String answer) {
        }

        private final AtomicReference<Script> script = new AtomicReference<>(new Script(List.of(), "好的。"));

        void script(List<ToolCallSpec> calls, String answer) {
            script.set(new Script(calls, answer));
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return stream(prompt).blockLast();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            Script current = script.get();
            String toolResult = findToolResult(prompt);
            if (toolResult == null && !current.calls().isEmpty()) {
                return Flux.just(toolCallResponse(current.calls()));
            }
            return Flux.just(textResponse(current.answer()));
        }

        private static ChatResponse toolCallResponse(List<ToolCallSpec> calls) {
            List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
            for (int i = 0; i < calls.size(); i++) {
                ToolCallSpec spec = calls.get(i);
                toolCalls.add(new AssistantMessage.ToolCall("call_eval_" + i, "function",
                        spec.name(), spec.arguments()));
            }
            AssistantMessage message = AssistantMessage.builder()
                    .content("")
                    .toolCalls(toolCalls)
                    .build();
            ChatGenerationMetadata metadata = ChatGenerationMetadata.builder()
                    .finishReason("tool_calls")
                    .build();
            return new ChatResponse(List.of(new Generation(message, metadata)));
        }

        private static ChatResponse textResponse(String text) {
            ChatGenerationMetadata metadata = ChatGenerationMetadata.builder()
                    .finishReason("stop")
                    .build();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text), metadata)));
        }

        private static String findToolResult(Prompt prompt) {
            List<Message> messages = prompt.getInstructions();
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i) instanceof ToolResponseMessage toolMessage
                        && !toolMessage.getResponses().isEmpty()) {
                    return toolMessage.getResponses().get(0).responseData();
                }
            }
            return null;
        }
    }
}
