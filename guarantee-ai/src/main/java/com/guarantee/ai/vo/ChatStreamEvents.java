package com.guarantee.ai.vo;

/**
 * SSE 事件载荷定义。
 *
 * <p>与前端 {@code src/utils/sse.ts} 约定的事件协议一一对应：
 * meta / delta / tool_call / done / error。</p>
 */
public final class ChatStreamEvents {

    private ChatStreamEvents() {
    }

    /** 首帧：告知会话标识。 */
    public record Meta(Long conversationId, String conversationNo, String title) {
    }

    /** 正文增量。 */
    public record Delta(String content) {
    }

    /** 正常结束。 */
    public record Done(Long conversationId, Long messageId) {
    }

    /** 异常结束。 */
    public record Error(String message) {
    }
}
