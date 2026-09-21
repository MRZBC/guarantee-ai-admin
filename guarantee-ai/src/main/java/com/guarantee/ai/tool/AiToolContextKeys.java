package com.guarantee.ai.tool;

/**
 * 通过 Spring AI {@code ToolContext} 在「聊天请求」与「Tool 执行」之间传递的上下文键。
 *
 * <p>Tool 执行发生在模型回调内部，拿不到 Web 层参数，因此会话信息必须随 ToolContext 下传。</p>
 */
public final class AiToolContextKeys {

    /** 当前会话 ID（Long）。 */
    public static final String CONVERSATION_ID = "aiConversationId";

    /** 当前用户 ID（Long）。 */
    public static final String USER_ID = "aiUserId";

    /** 本次请求的 TraceId（String）。 */
    public static final String TRACE_ID = "aiTraceId";

    /** 用于把 Tool Call 实时推给 SSE 的 {@code Sinks.Many<ToolCallEvent>}。 */
    public static final String EVENT_SINK = "aiToolEventSink";

    private AiToolContextKeys() {
    }
}
