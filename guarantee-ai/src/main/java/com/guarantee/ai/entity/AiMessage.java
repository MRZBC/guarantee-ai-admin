package com.guarantee.ai.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** AI 消息。 */
@Data
public class AiMessage {

    private Long id;
    private Long conversationId;
    /** USER / ASSISTANT / SYSTEM / TOOL */
    private String role;
    private String content;
    private Integer tokenCount;
    private LocalDateTime createdAt;
}
