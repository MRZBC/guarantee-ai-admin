package com.guarantee.ai.vo;

import java.time.LocalDateTime;

/** 会话中的单条消息。 */
public record MessageVO(
        Long id,
        Long conversationId,
        String role,
        String content,
        Integer tokenCount,
        LocalDateTime createdAt) {
}
