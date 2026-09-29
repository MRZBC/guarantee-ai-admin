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
    /**
     * 调用来源：CHAT（页面/助手）/ MCP（外部 Agent）/ EVAL（评测）。
     *
     * <p>没有来源列就无法区分"助手调的"与"外部 Agent 调的"（AC-MCP-05）。</p>
     */
    private String source;
    /** 本次调用的 traceId：与审计、成本日志同源，用来把三者串起来（AC-MCP-10）。 */
    private String traceId;
    private LocalDateTime createdAt;
    /** 逻辑删除 0正常 1已删除（LD-01：所有业务查询默认只看 0） */
    private Integer isDeleted;
    /** 删除时间（DATETIME(6)，未删除为 null；同时是唯一键分量） */
    private LocalDateTime deletedAt;
    /** 删除人：应用写 sys_user.id 字符串，数据库直连删除为 'DB' */
    private String deletedBy;

}
