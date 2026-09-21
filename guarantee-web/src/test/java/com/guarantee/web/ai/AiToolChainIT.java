package com.guarantee.web.ai;

import com.guarantee.ai.dto.AiChatRequest;
import com.guarantee.ai.entity.AiToolCall;
import com.guarantee.ai.mapper.AiToolCallMapper;
import com.guarantee.ai.service.AiChatService;
import com.guarantee.order.dto.OrderSummaryCriteria;
import com.guarantee.order.service.OrderStatisticsService;
import com.guarantee.order.vo.OrderSummaryVO;
import com.guarantee.web.GuaranteeAiAdminApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端链路集成测试（不需要真实 LLM API Key）。
 *
 * <p>用确定性的 {@link StubToolCallingChatModel} 替换真实模型，
 * 验证完整链路与真实数据库结果一致：</p>
 * <pre>
 *   POST /api/ai/chat
 *     -> 模型发起 Tool Call
 *     -> Spring AI ToolCallingAdvisor 执行 Tool
 *     -> Tool -> OrderStatisticsService -> OrderStatisticsMapper -> MySQL
 *     -> 结果回灌模型 -> 流式输出 -> 会话与工具调用落库
 * </pre>
 *
 * <p>需要可用的 MySQL（见 application.yml）。运行方式：{@code mvn verify}。</p>
 */
@SpringBootTest(
        classes = {GuaranteeAiAdminApplication.class, AiToolChainIT.StubChatModelConfig.class},
        properties = {
                // 仅替换模型实现（@Primary），保留 Spring AI 其余自动配置：
                // ToolCallingManager / ToolCallingAdvisor.Builder 正是驱动 Tool 循环的组件，
                // 若用 spring.ai.model.chat=none 关掉，工具循环不会执行。
                "guarantee.data-init.enabled=false"
        })
class AiToolChainIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class StubChatModelConfig {

        /** 以 @Primary 覆盖真实模型，其余 Spring AI 装配保持不变。 */
        @Bean
        @Primary
        ChatModel stubToolCallingChatModel() {
            return new StubToolCallingChatModel();
        }
    }

    private static final String QUESTION = "2026年第三季度投标订单有多少？";

    @Autowired
    private AiChatService aiChatService;

    @Autowired
    private OrderStatisticsService orderStatisticsService;

    @Autowired
    private AiToolCallMapper aiToolCallMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ChatModel chatModel;

    @Test
    @DisplayName("AI 提出的 Tool Call 必须命中真实数据库，且结果与直接调用 Service 完全一致")
    void shouldReturnRealDatabaseNumbersThroughToolChain() {
        // ---- 1. 基准值：绕过 AI，直接走 Service -> Mapper -> DB ----
        OrderSummaryCriteria criteria = new OrderSummaryCriteria();
        criteria.setOrderType("TENDER");
        criteria.setStartDate(LocalDate.of(2026, 7, 1));
        criteria.setEndDate(LocalDate.of(2026, 9, 30));
        OrderSummaryVO expected = orderStatisticsService.summarize(criteria);

        assertThat(expected.getOrderCount())
                .as("2026Q3 应存在投标订单，说明演示数据已初始化")
                .isGreaterThan(1000L);

        // ---- 2. 经由 AI 对话链路获取同一口径的数据 ----
        Long userId = adminUserId();
        AiChatRequest request = new AiChatRequest();
        request.setMessage(QUESTION);

        List<ServerSentEvent<String>> events = aiChatService.stream(userId, request)
                .collectList()
                .block(Duration.ofSeconds(90));

        assertThat(events).isNotNull().isNotEmpty();

        // meta：会话已建立
        Map<String, Object> meta = firstData(events, "meta");
        long conversationId = ((Number) meta.get("conversationId")).longValue();
        assertThat(conversationId).isPositive();

        // tool_call：工具被真实执行且成功
        Map<String, Object> toolCall = firstData(events, "tool_call");
        assertThat(toolCall.get("toolName")).isEqualTo(StubToolCallingChatModel.TOOL_NAME);
        assertThat(toolCall.get("toolType")).isEqualTo("READ");
        assertThat(toolCall.get("status")).isEqualTo("SUCCESS");
        assertThat(((Number) toolCall.get("durationMs")).longValue()).isGreaterThanOrEqualTo(0L);
        assertThat(toolCall.get("arguments")).asString()
                .contains("2026-07-01").contains("2026-09-30");

        // 工具返回的业务数字必须与直接调用 Service 完全一致
        @SuppressWarnings("unchecked")
        Map<String, Object> toolResult = objectMapper.readValue((String) toolCall.get("result"), Map.class);
        assertThat(((Number) toolResult.get("orderCount")).longValue())
                .as("AI Tool 返回的订单量必须等于数据库真实值")
                .isEqualTo(expected.getOrderCount());
        assertThat(new BigDecimal(toolResult.get("guaranteeAmount").toString()))
                .isEqualByComparingTo(expected.getGuaranteeAmount());
        assertThat(new BigDecimal(toolResult.get("premiumAmount").toString()))
                .isEqualByComparingTo(expected.getPremiumAmount());
        assertThat(((Number) toolResult.get("enterpriseCount")).longValue())
                .isEqualTo(expected.getEnterpriseCount());
        assertThat(((Number) toolResult.get("projectCount")).longValue())
                .isEqualTo(expected.getProjectCount());

        // delta：流式正文已拼接
        String answer = events.stream()
                .filter(e -> "delta".equals(e.event()))
                .map(ServerSentEvent::data)
                .reduce("", (a, b) -> a + extractContent(b));
        assertThat(answer).contains(StubToolCallingChatModel.ANSWER_PART_1)
                .contains(StubToolCallingChatModel.ANSWER_PART_2);

        // done：流正常结束
        Map<String, Object> done = firstData(events, "done");
        assertThat(((Number) done.get("conversationId")).longValue()).isEqualTo(conversationId);

        // ---- 3. 落库校验：ai_message 与 ai_tool_call ----
        Integer messageCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_message WHERE conversation_id = ?", Integer.class, conversationId);
        assertThat(messageCount).as("应至少落库 USER + ASSISTANT 两条消息").isGreaterThanOrEqualTo(2);

        String assistantContent = jdbcTemplate.queryForObject(
                "SELECT content FROM ai_message WHERE conversation_id = ? AND role = 'ASSISTANT' "
                        + "ORDER BY id DESC LIMIT 1", String.class, conversationId);
        assertThat(assistantContent).contains(StubToolCallingChatModel.ANSWER_PART_1);

        List<AiToolCall> persisted = aiToolCallMapper.selectByConversationId(conversationId);
        assertThat(persisted).hasSize(1);
        AiToolCall entity = persisted.get(0);
        assertThat(entity.getToolName()).isEqualTo(StubToolCallingChatModel.TOOL_NAME);
        assertThat(entity.getToolType()).isEqualTo("READ");
        assertThat(entity.getStatus()).isEqualTo("SUCCESS");
        assertThat(entity.getArguments()).contains("TENDER");
        assertThat(entity.getResult()).contains(String.valueOf(expected.getOrderCount()));
        assertThat(entity.getDurationMs()).isNotNull().isGreaterThanOrEqualTo(0L);
        assertThat(entity.getMessageId()).as("Tool Call 应关联到助手消息").isNotNull();
    }

    @Test
    @DisplayName("模型应被调用两次：一次发起工具调用，一次基于工具结果作答")
    void shouldDriveToolCallingLoop() {
        Long userId = adminUserId();
        AiChatRequest request = new AiChatRequest();
        request.setMessage(QUESTION);

        StubToolCallingChatModel stub = (StubToolCallingChatModel) chatModel;
        int before = stub.modelInvocations();

        aiChatService.stream(userId, request).collectList().block(Duration.ofSeconds(90));

        assertThat(stub.modelInvocations() - before)
                .as("应调用模型两次：发起 Tool Call -> 基于工具结果作答")
                .isEqualTo(2);
        assertThat(stub.lastToolResponse())
                .as("工具执行结果必须回灌给模型")
                .contains("orderCount")
                .contains("guaranteeAmount");
    }

    private Long adminUserId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = 'admin'", Long.class);
    }

    /** 取出指定事件的第一个 data 载荷并解析为 Map。 */
    private Map<String, Object> firstData(List<ServerSentEvent<String>> events, String eventName) {
        String payload = events.stream()
                .filter(e -> eventName.equals(e.event()))
                .map(ServerSentEvent::data)
                .findFirst()
                .orElseThrow(() -> new AssertionError("未收到 SSE 事件: " + eventName));
        return parse(payload);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) {
        return objectMapper.readValue(json, Map.class);
    }

    @SuppressWarnings("unchecked")
    private String extractContent(String deltaJson) {
        Object content = parse(deltaJson).get("content");
        return content == null ? "" : content.toString();
    }
}
