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
 * 确定性的假 ChatModel：第一轮发起 {@code queryBusinessKnowledge} 检索，第二轮给出最终正文。
 *
 * <p>与 {@link StubToolCallingChatModel} 同一套路，但**可配置**（检索问句与最终正文都能换），
 * 因为知识类 IT 要覆盖三种不同的对话：正常引用（并验证与服务端返回值逐字一致）、
 * 模型自写假来源行（验证被剥离）、越权（验证审计条目在工具返回值里就不存在）。
 * 前者是固定的，后两者必须换问句/正文。</p>
 *
 * <p>单元测试不能替代它：这里走的是**真实** Tool 循环 + 真实 MySQL，
 * 断言的正是"模型 → Tool → Service → Mapper → DB → 回灌 → 收尾"这条链。</p>
 */
public class StubKnowledgeChatModel implements ChatModel {

    /** 必须与 {@code QueryBusinessKnowledgeTool} 的 {@code @Tool(name=…)} 完全一致。 */
    public static final String TOOL_NAME = "queryBusinessKnowledge";

    private final AtomicInteger invocations = new AtomicInteger();

    private volatile String toolArguments = "{\"query\":\"停用和删除有什么区别\"}";
    private volatile String finalAnswer = "停用是暂停业务；删除是从默认列表移除。";

    /** 最近一次收到的工具执行结果原文（断言工具真的被执行过）。 */
    private volatile String lastToolResponse;

    /**
     * 换一条对话：{@code query} 是模型要传给检索工具的问句，{@code answer} 是最终正文。
     *
     * <p>问句直接拼进 JSON；测试里的问句都不含引号与反斜杠（换问句时请守住这一点）。</p>
     */
    public void ask(String query, String answer) {
        this.toolArguments = "{\"query\":\"" + query + "\"}";
        this.finalAnswer = answer;
    }

    public int invocations() {
        return invocations.get();
    }

    public String lastToolResponse() {
        return lastToolResponse;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        invocations.incrementAndGet();
        String toolResult = findToolResult(prompt);
        if (toolResult == null) {
            return toolCallResponse();
        }
        lastToolResponse = toolResult;
        return textResponse(finalAnswer);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        invocations.incrementAndGet();
        String toolResult = findToolResult(prompt);
        if (toolResult == null) {
            return Flux.just(toolCallResponse());
        }
        lastToolResponse = toolResult;
        // 分两片输出，顺带验证流式拼接
        return Flux.just(textResponse(finalAnswer), textResponse(""));
    }

    /** 必须带 {@code finishReason = "tool_calls"}，否则 Spring AI 不会进入工具执行循环。 */
    private ChatResponse toolCallResponse() {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call_kb_1", "function", TOOL_NAME, toolArguments)))
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
