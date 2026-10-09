package com.guarantee.web.ai;

import com.guarantee.ai.dto.AiChatRequest;
import com.guarantee.ai.metrics.AiChatMetrics;
import com.guarantee.ai.service.AiChatService;
import com.guarantee.common.security.CurrentUser;
import com.guarantee.common.security.Roles;
import com.guarantee.common.trace.TraceContext;
import com.guarantee.system.service.UserService;
import com.guarantee.web.GuaranteeAiAdminApplication;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 观测链路集成测试（TEST-MCP-02 / TEST-MCP-03，AC-MCP-09/10）。
 *
 * <p>用确定性的 Stub ChatModel 跑**真实 MySQL**，验证三件事：</p>
 * <ol>
 *   <li><b>单轮指标落库</b>（REQ-MCP-09）：一次问答一行 {@code ai_turn_metric}，
 *       字段与结构化成本日志同源（轮次 / 调用数 / token / 耗时 / 触顶 / 来源 / traceId）；</li>
 *   <li><b>traceId 三段一致</b>（REQ-MCP-10 / AC-MCP-10）：{@code ai_turn_metric} /
 *       {@code ai_tool_call} / {@code ai_audit_log} 三者 trace_id 相同且非空；</li>
 *   <li><b>失败路径也算一次问答</b>：模型故障时仍落一行 {@code outcome=ERROR} 且 traceId 非空
 *       （"含失败与流式"是 REQ 的原话）。</li>
 * </ol>
 *
 * <p>traceId 由 MDC 注入（与 {@code TraceIdFilter} 同一把钥匙）：
 * 本类直接调 Service 而不走 HTTP，因此自己放进 MDC，模拟请求线程的快照。</p>
 *
 * <p>注意：IT 会真实写入会话 / 消息 / 工具调用 / 指标行（与既有 {@code AiToolChainIT} 同款做法），
 * 不修改任何业务主数据。</p>
 */
@SpringBootTest(
        classes = {GuaranteeAiAdminApplication.class, AiObservabilityIT.ToggleChatModelConfig.class},
        properties = {"guarantee.data-init.enabled=false"})
class AiObservabilityIT {

    private static final String TRACE_ID = "it-trace-observability-0001";
    private static final String QUESTION = "2026年第三季度投标订单有多少？";

    /** AC-MCP-08：ai.* 指标允许出现标签键白名单（与 AiChatMetrics 的口径一致）。 */
    private static final Set<String> ALLOWED_TAG_KEYS = Set.of(
            "outcome", "model", "capped", "tool", "status", "source",
            "direction", "domain", "hit", "result", "application");

    /** 让"失败路径"可切换：置 true 时模型直接报错（用于 AC-MCP-10 的失败分支）。 */
    private static final AtomicBoolean FAIL_STREAM = new AtomicBoolean(false);

    @TestConfiguration(proxyBeanMethods = false)
    static class ToggleChatModelConfig {

        @Bean
        @Primary
        ChatModel togglingStubChatModel() {
            return new TogglingChatModel();
        }
    }

    /** 包一层：正常时委托给既有确定性 Stub；故障模式下直接 {@code Flux.error}。 */
    static final class TogglingChatModel implements ChatModel {

        private final StubToolCallingChatModel delegate = new StubToolCallingChatModel();

        @Override
        public ChatResponse call(Prompt prompt) {
            return delegate.call(prompt);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            if (FAIL_STREAM.get()) {
                return Flux.error(new IllegalStateException("stub 模型故障（观测失败路径用例）"));
            }
            return delegate.stream(prompt);
        }
    }

    @Autowired
    private AiChatService aiChatService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserService userService;

    @Autowired
    private ObjectMapper objectMapper;

    /** 生产那份指标注册表：用来断言"埋点真的记到了"（AC-MCP-07）。 */
    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        FAIL_STREAM.set(false);
        // 与 TraceIdFilter 同一把钥匙：请求线程上写入 MDC，Service 会把它快照进 ToolContext/TurnCost
        MDC.put(TraceContext.MDC_KEY, TRACE_ID);
        Long adminId = adminUserId();
        CurrentUser.set(new CurrentUser.Principal(adminId, "admin", "超级管理员",
                List.of(Roles.ADMIN), userService.listPermissionCodesByUserId(adminId)));
    }

    @AfterEach
    void tearDown() {
        FAIL_STREAM.set(false);
        CurrentUser.clear();
        MDC.clear();
    }

    // ==================================================================
    // TEST-MCP-02 + TEST-MCP-03（正常路径）
    // ==================================================================

    @Test
    @DisplayName("TEST-MCP-02/03：一次问答落一行指标，且指标/工具调用/审计三者 traceId 相同且非空")
    void turnMetricIsPersistedAndTraceIdMatchesAcrossThreeRecords() {
        Long userId = adminUserId();
        AiChatRequest request = new AiChatRequest();
        request.setMessage(QUESTION);

        List<ServerSentEvent<String>> events = aiChatService.stream(userId, request)
                .collectList()
                .block(Duration.ofSeconds(90));
        assertThat(events).as("SSE 事件流不应为空").isNotNull().isNotEmpty();

        Long conversationId = conversationIdOf(events);

        Map<String, Object> metric = jdbc.queryForMap("""
                SELECT user_id, model, prompt_version, rounds, tool_calls, tool_cost_ms, total_cost_ms,
                       input_tokens, output_tokens, (capped = 1) AS capped_flag, cap_reason,
                       source, outcome, trace_id
                  FROM ai_turn_metric
                 WHERE conversation_id = ?
                 ORDER BY id DESC
                 LIMIT 1
                """, conversationId);

        assertThat(((Number) metric.get("user_id")).longValue()).isEqualTo(userId);
        assertThat(metric.get("source")).isEqualTo("CHAT");
        assertThat(metric.get("outcome")).isEqualTo("SUCCESS");
        assertThat(metric.get("capped_flag")).as("正常路径不应触顶").isIn(0, 0L);
        assertThat(metric.get("trace_id")).as("AC-MCP-10：指标行必须带 traceId").isEqualTo(TRACE_ID);
        assertThat(((Number) metric.get("rounds")).intValue()).isGreaterThanOrEqualTo(1);
        assertThat(((Number) metric.get("tool_calls")).intValue()).isGreaterThanOrEqualTo(1);
        assertThat(((Number) metric.get("total_cost_ms")).longValue()).isGreaterThanOrEqualTo(0L);
        assertThat(((Number) metric.get("input_tokens")).intValue()).isGreaterThanOrEqualTo(0);
        assertThat(((Number) metric.get("output_tokens")).intValue()).isGreaterThanOrEqualTo(0);

        // 与 ai_tool_call 对得上：本轮的每次工具调用都应记 source=CHAT
        Integer chatToolCalls = jdbc.queryForObject("""
                SELECT COUNT(*) FROM ai_tool_call WHERE conversation_id = ? AND source = 'CHAT'
                """, Integer.class, conversationId);
        assertThat(chatToolCalls)
                .as("指标里的 tool_calls 必须等于 ai_tool_call 的真实行数（同源口径）")
                .isEqualTo(((Number) metric.get("tool_calls")).intValue());

        // 三处 traceId 一致且非空
        String toolCallTrace = jdbc.queryForObject("""
                SELECT trace_id FROM ai_tool_call WHERE conversation_id = ? ORDER BY id DESC LIMIT 1
                """, String.class, conversationId);
        String auditTrace = jdbc.queryForObject("""
                SELECT trace_id FROM ai_audit_log
                 WHERE conversation_id = ? AND action = 'TOOL_CALL' ORDER BY id DESC LIMIT 1
                """, String.class, conversationId);

        assertThat(toolCallTrace).as("AC-MCP-10：工具调用必须带 traceId").isNotNull().isEqualTo(TRACE_ID);
        assertThat(auditTrace).as("AC-MCP-10：审计必须带同一个 traceId").isNotNull().isEqualTo(TRACE_ID);
        assertThat(metric.get("trace_id")).isEqualTo(toolCallTrace).isEqualTo(auditTrace);

        // ---- AC-MCP-07/08：指标真的记到了（IT 用的是生产那份 MeterRegistry） ----
        assertThat(meterRegistry.find(AiChatMetrics.CHAT_REQUESTS)
                .tag("outcome", AiChatMetrics.OUTCOME_SUCCESS).tag("source", "ignored").counter())
                .as("ai.chat.requests 不应有 source 标签（标签白名单收窄）").isNull();
        assertThat(meterRegistry.find(AiChatMetrics.CHAT_REQUESTS)
                .tag("outcome", AiChatMetrics.OUTCOME_SUCCESS).counter())
                .as("每次问答必须记 ai.chat.requests{outcome=success}").isNotNull();
        assertThat(meterRegistry.find(AiChatMetrics.TOOL_CALLS).tag("source", "chat").counter())
                .as("工具调用必须带 source=chat 标签（AC-MCP-05 的观测面）").isNotNull();
        /*
          ai.tokens 在**本 IT 里不会出现**：Stub 模型不返回 Usage（token 为 0），
          而 AiChatMetrics 对 0 值不建时间序列（避免"每个模型一条 0 曲线"）。
          真实 usage 下的口径由单测 AiChatMetricsTest#tokensRespectDirection 覆盖。
        */
        assertThat(meterRegistry.find(AiChatMetrics.CHAT_ROUNDS).tag("capped", "false").summary())
                .as("轮次分布必须记到").isNotNull();

        // 基数纪律（AC-MCP-08）：ai.* 的标签键只允许白名单里的枚举维度
        for (Meter meter : meterRegistry.getMeters()) {
            if (!meter.getId().getName().startsWith("ai.")) {
                continue;
            }
            for (Tag tag : meter.getId().getTags()) {
                assertThat(ALLOWED_TAG_KEYS)
                        .as("指标 %s 出现非白名单标签键 %s", meter.getId().getName(), tag.getKey())
                        .contains(tag.getKey());
            }
        }
    }

    // ==================================================================
    // TEST-MCP-03（失败路径）
    // ==================================================================

    @Test
    @DisplayName("TEST-MCP-03（失败路径）：模型故障时仍落一行 outcome=ERROR 且 traceId 非空")
    void failingTurnStillPersistsTraceableMetric() {
        FAIL_STREAM.set(true);
        Long userId = adminUserId();
        // 先记基线，再定位"**本轮**新建的会话"。
        // 不能用"该用户最新一条会话"（原来的写法）：它是跨用例共享的查询，
        // 一旦别的 IT 在它之前建了 admin 的会话，这里就会校验到别人的行。
        // 2026-10-08 实测：把 IT 的执行顺序反转即复现——取到别人 config_version=0 的会话，
        // 本用例报 "Expecting actual: 0L to be greater than: 0L"（假失败）。
        // CI（Linux）的文件遍历顺序与本地不同，正是这类"只在别人跑过之后才红"的用例的温床。
        Long baseline = jdbc.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM ai_conversation WHERE user_id = ?", Long.class, userId);
        AiChatRequest request = new AiChatRequest();
        request.setMessage("这一轮注定失败（用于验证失败路径的观测完整性）");

        List<ServerSentEvent<String>> events = aiChatService.stream(userId, request)
                .collectList()
                .block(Duration.ofSeconds(60));

        assertThat(events).isNotNull().isNotEmpty();
        assertThat(events.stream().map(ServerSentEvent::event).toList()).contains("error");

        Integer newConversations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_conversation WHERE user_id = ? AND id > ?",
                Integer.class, userId, baseline);
        assertThat(newConversations).as("失败轮同样算一次问答：必须新建且只新建一个会话").isEqualTo(1);

        Long conversationId = jdbc.queryForObject(
                "SELECT id FROM ai_conversation WHERE user_id = ? AND id > ? ORDER BY id ASC LIMIT 1",
                Long.class, userId, baseline);

        Map<String, Object> metric = jdbc.queryForMap("""
                SELECT rounds, tool_calls, source, outcome, trace_id
                  FROM ai_turn_metric
                 WHERE conversation_id = ?
                 ORDER BY id DESC
                 LIMIT 1
                """, conversationId);

        assertThat(metric.get("outcome")).as("失败也是「一次问答」，必须留行").isEqualTo("ERROR");
        assertThat(metric.get("source")).isEqualTo("CHAT");
        assertThat(metric.get("trace_id")).as("失败路径同样要能按 traceId 串起来").isEqualTo(TRACE_ID);
        // 故障发生在第一轮模型调用：轮次计 1（"尝试过一轮"是真实发生的事），
        // 工具调用数为 0（根本没走到工具执行）
        assertThat(((Number) metric.get("rounds")).intValue()).isEqualTo(1);
        assertThat(((Number) metric.get("tool_calls")).intValue()).isZero();

        /*
          D4 / AC-CFG-09「每轮可追溯」在**失败轮**也必须成立：ai_conversation 的
          prompt_version / config_version 是"最近一次回答所用的版本"，失败轮同样是一次回答尝试。
          修复前只有成功收尾（finishTurn）才写这两列，失败轮永远为空 → 第一轮就失败时
          这一列从头到尾都是 NULL，回溯链断在半路。
          prompt_version 允许为 NULL（DB 无发布版 = 用 classpath 内置提示词，这本身是事实），
          而 config_version 必须回填**本轮快照的真实版本号**。

          断言用"等于那一刻库里的 MAX(version)"而不是"大于 0"：
          AiConfigSnapshot 的口径就是"版本号 = ai_config_item.version 的最大值，
          **0 表示空表**"，而应用/播种都不会预置配置行——干净库上这张表是空的。
          原来的 "> 0" 等于要求"别的用例先往 ai_config_item 写过行"，于是在
          单独跑本类、或 CI（Linux 的文件遍历顺序与本地不同）上都必红
          （2026-10-08 实测：干净库单跑 → Expecting actual: 0L to be greater than: 0L）。
          改成与快照同源比较后，空表（0）与非空表（正版本）两种状态都能验证"确实回填了本轮版本"。
        */
        Map<String, Object> versionTrace = jdbc.queryForMap(
                "SELECT prompt_version, config_version FROM ai_conversation WHERE id = ?", conversationId);
        long expectedConfigVersion = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM ai_config_item", Long.class);
        assertThat(versionTrace.get("config_version"))
                .as("失败轮必须回填 config_version（本轮配置快照的版本号；空表按 AiConfigSnapshot 口径为 0）")
                .isNotNull();
        assertThat(((Number) versionTrace.get("config_version")).longValue())
                .as("回填的必须是那一刻 ai_config_item 的版本快照")
                .isEqualTo(expectedConfigVersion);
        Object promptVersion = versionTrace.get("prompt_version");
        if (promptVersion != null) {
            assertThat(((Number) promptVersion).intValue())
                    .as("prompt_version 一旦有值就必须是正版本号（normalizePromptVersion 的口径）")
                    .isGreaterThan(0);
        }
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private Long adminUserId() {
        return jdbc.queryForObject("SELECT id FROM sys_user WHERE username = 'admin'", Long.class);
    }

    /** 从 meta 事件里取会话 id（与 AiToolChainIT 同款解析）。 */
    private Long conversationIdOf(List<ServerSentEvent<String>> events) {
        for (ServerSentEvent<String> event : events) {
            if ("meta".equals(event.event()) && event.data() != null) {
                Map<String, Object> data = objectMapper.readValue(event.data(), Map.class);
                return ((Number) data.get("conversationId")).longValue();
            }
        }
        throw new AssertionError("SSE 流里没有 meta 事件，取不到 conversationId");
    }
}
