package com.guarantee.ai.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** AI 审计日志。 */
@Data
public class AiAuditLog {

    private Long id;
    private Long conversationId;
    private Long userId;
    /** CHAT / TOOL_CALL / ERROR */
    private String action;
    private String detail;
    private String traceId;
    private LocalDateTime createdAt;
}
