package com.guarantee.ai.vo;

import java.time.LocalDateTime;

/** Tool Call 记录（含 raw arguments / result，供前端折叠展示）。 */
public record ToolCallVO(
        Long id,
        Long conversationId,
        Long messageId,
        String toolName,
        String toolType,
        String arguments,
        String result,
        String status,
        Long durationMs,
        String errorMessage,
        LocalDateTime createdAt) {
}
