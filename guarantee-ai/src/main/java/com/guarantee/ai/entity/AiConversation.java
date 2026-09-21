package com.guarantee.ai.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** AI 会话。 */
@Data
public class AiConversation {

    private Long id;
    private String conversationNo;
    private Long userId;
    private String title;
    private String model;
    /** ACTIVE / ARCHIVED */
    private String status;
    private Integer messageCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
