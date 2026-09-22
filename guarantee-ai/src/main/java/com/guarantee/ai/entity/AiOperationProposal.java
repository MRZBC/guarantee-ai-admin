package com.guarantee.ai.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 变更提案（{@code ai_operation_proposal}）。
 *
 * <p>写工具只产出本记录，**不落库任何业务变更**；真正的执行发生在用户点击确认后，
 * 由带 JWT 的独立 HTTP 接口在 Web 线程上完成（7.1）。</p>
 */
@Data
public class AiOperationProposal {

    private Long id;
    /** 提案编号 OP+时间+随机，全局唯一。 */
    private String proposalNo;
    private Long conversationId;
    private Long userId;
    private String toolName;
    /** CREATE / UPDATE / ENABLE / DISABLE / ASSIGN_ROLES / ASSIGN_PERMISSIONS */
    private String action;
    /** USER / ORG / DEPT / ROLE / INSURANCE_TYPE */
    private String targetType;
    /** 新建时为空。 */
    private Long targetId;
    private String targetName;
    /** 模型解析后的参数（已脱敏，SYS-A-09）。 */
    private String requestPayload;
    /** changes[] + impact + warnings[]。 */
    private String previewPayload;
    /** 目标版本指纹，执行前比对（T-09）。 */
    private String targetFingerprint;
    /** 所需权限码，逗号分隔；确认时复核（SYS-C-04）。 */
    private String requiredPerms;
    /** PENDING / EXECUTING / EXECUTED / REJECTED / EXPIRED / INVALIDATED / FAILED */
    private String status;
    private String rejectReason;
    private String resultMessage;
    private LocalDateTime confirmedAt;
    private LocalDateTime executedAt;
    private LocalDateTime expiresAt;
    private Long auditId;
    private String traceId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 逻辑删除 0正常 1已删除（LD-01：所有业务查询默认只看 0） */
    private Integer isDeleted;
    /** 删除时间（DATETIME(6)，未删除为 null；同时是唯一键分量） */
    private LocalDateTime deletedAt;
    /** 删除人：应用写 sys_user.id 字符串，数据库直连删除为 'DB' */
    private String deletedBy;

}
