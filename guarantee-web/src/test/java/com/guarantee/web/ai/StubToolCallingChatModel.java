package com.guarantee.web.ai;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 确定性的假 ChatModel，用于在没有真实 API Key 的情况下验证：
 *
 * <pre>
 *   用户问题 -> 模型发起 Tool Call -> Spring AI 执行 Tool
 *            -> Tool 调用业务 Service -> Mapper -> DB
 *            -> 工具结果回灌模型 -> 模型输出最终答案 -> SSE 流式返回
 * </pre>
 *
 * <p>行为：第一次调用返回一个 {@code queryOrderSummary} 的工具调用请求；
 * 收到工具执行结果后，第二次调用返回最终文本（分两个 chunk，用于验证流式拼接）。</p>
 */
public class StubToolCallingChatModel implements ChatModel {

    /** 固定注入的工具入参，等价于模型把“2026年第三季度投标订单”换算成明确日期后的结果。 */
    public static final String TOOL_NAME = "queryOrderSummary";
    public static final String TOOL_ARGUMENTS =
            "{\"orderType\":\"TENDER\",\"startDate\":\"2026-07-01\",\"endDate\":\"2026-09-30\"}";

    public static final String ANSWER_PART_1 = "根据 queryOrderSummary 工具返回的真实数据，";
    public static final String ANSWER_PART_2 = "2026 年第三季度（2026-07-01 ~ 2026-09-30）投标订单情况如上。";

    private final AtomicInteger modelInvocations = new AtomicInteger();

    /** 最近一次收到的工具执行结果原文，便于断言工具确实被执行。 */
    private volatile String lastToolResponse;

    public int modelInvocations() {
        return modelInvocations.get();
    }

    public String lastToolResponse() {
        return lastToolResponse;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        modelInvocations.incrementAndGet();
        String toolResult = findToolResult(prompt);
        if (toolResult == null) {
            return responseWithToolCall();
        }
        lastToolResponse = toolResult;
        return textResponse(ANSWER_PART_1 + ANSWER_PART_2);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        modelInvocations.incrementAndGet();
        String toolResult = findToolResult(prompt);
        if (toolResult == null) {
            // 第一轮：发起工具调用
            return Flux.just(responseWithToolCall());
        }
        // 第二轮：分片输出最终答案，验证前端 delta 拼接
        lastToolResponse = toolResult;
        return Flux.just(textResponse(ANSWER_PART_1), textResponse(ANSWER_PART_2));
    }

    /**
     * 构造一次工具调用响应。
     *
     * <p>必须带上 {@code finishReason = "tool_calls"}：Spring AI 的
     * {@code ToolExecutionEligibilityChecker} 会据此判断是否进入工具执行循环，
     * 缺少该元数据时工具不会被真正执行。</p>
     */
    private static ChatResponse responseWithToolCall() {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call_stub_1", "function", TOOL_NAME, TOOL_ARGUMENTS)))
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

    /** 在会话历史中查找 Spring AI 回灌的工具执行结果。 */
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
