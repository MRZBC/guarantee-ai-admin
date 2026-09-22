package com.guarantee.ai.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作审计（{@code ai_operation_audit}）。
 *
 * <p><b>为什么要统一一张表</b>："谁改了什么"不能有两个答案。助手确认（{@code source=AI}）
 * 与页面直连（{@code source=WEB}）写同一张表，才能按渠道区分又统一追溯（SYS-A-07 / AC-22）。</p>
 *
 * <p><b>D-4 脱敏</b>：{@code beforeValue} / {@code afterValue} 中敏感字段只保留
 * 字段名与"是否变更"（{@code <changed>}），且脱敏在**写入前**完成、与操作者角色无关。
 * 落库前一律经 {@code SensitiveFieldMasker} 处理。</p>
 *
 * <p><b>保留期（D-5）</b>：本表按月 RANGE 分区，在线 24 个月 + 归档 36 个月；
 * 主键是复合的 {@code (id, operated_at)}——MySQL 要求分区键出现在每个唯一键中。</p>
 *
 * <p><b>为什么没有 {@code operator_org_id}</b>（PLAN-移除用户与部门的机构归属 §8 Q3）：
 * 机构是**外部的出函机构，服务于订单**，不是人的归属维度——用户与部门都不再挂机构，
 * "操作人机构"因此没有任何数据来源（阶段一该列已恒为 {@code null}）。
 * 留一列恒空的"机构"只会在读审计的人心里造出一个并不存在的数据范围概念，
 * 故随本次改动一并删除（DDL 见 {@code V5__drop_operator_org_id.sql}）。</p>
 */
@Data
public class AiOperationAudit {

    private Long id;
    /** 操作时间（分区键）。 */
    private LocalDateTime operatedAt;
    private Long operatorUserId;
    private String operatorUsername;
    private String operatorRealName;
    /** AI（助手确认）/ WEB（页面直连）。 */
    private String source;
    private String action;
    private String targetType;
    private Long targetId;
    private String targetName;
    /** 变更前结构化快照（敏感字段已脱敏）。 */
    private String beforeValue;
    /** 变更后结构化快照（敏感字段已脱敏）。 */
    private String afterValue;
    /** 变更字段列表，便于检索。 */
    private String changedFields;
    /** 快照超 8KB 被截断（SYS-A-16）。 */
    private Integer truncated;
    /** SUCCESS / FAILED / REJECTED / EXPIRED / PARTIAL */
    private String result;
    private String errorMessage;
    private Long proposalId;
    private Long conversationId;
    private String traceId;
    /** 逻辑删除 0正常 1已删除（LD-01：所有业务查询默认只看 0） */
    private Integer isDeleted;
    /** 删除时间（DATETIME(6)，未删除为 null；同时是唯一键分量） */
    private LocalDateTime deletedAt;
    /** 删除人：应用写 sys_user.id 字符串，数据库直连删除为 'DB' */
    private String deletedBy;

}
