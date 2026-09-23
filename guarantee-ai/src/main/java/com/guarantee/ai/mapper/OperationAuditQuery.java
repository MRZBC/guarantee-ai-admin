package com.guarantee.ai.mapper;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 操作审计查询条件（同时被 {@code queryOperationAudit} 工具与页面接口复用）。
 *
 * <p><b>为什么不用通用 DTO</b>：审计查询有两条硬规则必须由查询对象自身携带——
 * ① 时间区间必填且 ≤90 天（SYS-A-17）；② 可见性规则与其它实体不同
 * （ROLE/PERMISSION 类仅 ADMIN 可见，SYS-A-10）。把规则放在查询对象里，
 * Mapper 就能无条件按它们过滤，而不是指望每个调用方都记得传。</p>
 *
 * <p><b>机构维度已整体移除</b>（PLAN-移除用户与部门的机构归属 §8 Q3）：
 * 用户与部门都不再挂机构，"操作人机构"没有任何数据来源，因此查询对象里
 * 不再有 {@code visibleOrgIds} / {@code restrictByOrg}——可见性只由
 * {@link #allowedTargetTypes} 表达。DDL 见 {@code V5__drop_operator_org_id.sql}。</p>
 */
@Data
public class OperationAuditQuery {

    private LocalDateTime startDate;
    private LocalDateTime endDate;

    private String operatorUsername;

    /** USER / ORG / DEPT / ROLE / PERMISSION / INSURANCE_TYPE。 */
    private String targetType;

    private String action;

    /** SUCCESS / FAILED / REJECTED / EXPIRED / PARTIAL。 */
    private String result;

    /** AI / WEB。 */
    private String source;

    private Long targetId;
    private String traceId;

    /** 返回条数上限（默认 50，最大 200，由 Service 收敛）；{@link #all} 为 true 时忽略本字段。 */
    private Integer limit;

    /**
     * 「全部」：不设条数上限（页面「条数上限 = 全部」）。
     *
     * <p>必须用**独立开关**而不是"把 limit 传很大"来表达：`clampLimit` 会把任何超过 200 的值收敛回 200，
     * 传大数只会让页面显示"全部"却只拿到 200 条——那是更糟的谎报。
     * 也不复用 {@code limit<=0}（那个语义是"没传，用默认值"），否则 AI 工具里模型传 0 就会变成不限量。</p>
     *
     * <p>唯一护栏仍然是强制的 ≤90 天时间区间（SYS-A-17）。</p>
     */
    private boolean all;

    /** 允许查看的目标类型白名单；{@code null} 表示不限制。 */
    private List<String> allowedTargetTypes;
}
