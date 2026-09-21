package com.guarantee.ai.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** AI Tool Call 审计记录。 */
@Data
public class AiToolCall {

    private Long id;
    private Long conversationId;
    private Long messageId;
    private String toolName;
    /** READ / WRITE */
    private String toolType;
    private String arguments;
    private String result;
    /** SUCCESS / FAILED */
    private String status;
    private Long durationMs;
    private String errorMessage;
    private LocalDateTime createdAt;
}
