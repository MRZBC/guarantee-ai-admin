package com.guarantee.ai.vo;

import java.time.LocalDateTime;

/** 会话列表项。 */
public record ConversationVO(
        Long id,
        String conversationNo,
        String title,
        String model,
        String status,
        Integer messageCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
