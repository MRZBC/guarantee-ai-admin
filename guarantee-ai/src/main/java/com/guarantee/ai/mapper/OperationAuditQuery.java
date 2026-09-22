package com.guarantee.ai.mapper;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 操作审计查询条件（同时被 {@code queryOperationAudit} 工具与页面接口复用）。
 *
 * <p><b>为什么不用通用 DTO</b>：审计查询有两条硬规则必须由查询对象自身携带——
 * ① 时间区间必填且 ≤90 天（SYS-A-17）；② 数据范围规则与其它实体不同
 * （ROLE/PERMISSION 类仅 ADMIN 可见，SYS-A-10）。把规则放在查询对象里，
 * Mapper 就能无条件按它们过滤，而不是指望每个调用方都记得传。</p>
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

    /** 返回条数上限（默认 50，最大 200，由 Service 收敛）。 */
    private Integer limit;

    /**
     * 可见机构范围；{@code null} 表示不按机构过滤（仅 ADMIN 的 ROLE/PERMISSION 类审计）。
     */
    private List<Long> visibleOrgIds;

    /** 允许查看的目标类型白名单；{@code null} 表示不限制。 */
    private List<String> allowedTargetTypes;

    /**
     * 是否按"仅 USER/ORG/DEPT 类"收窄（非 ADMIN）。
     *
     * <p>与 {@link #visibleOrgIds} 配合：非 ADMIN 既受目标类型限制，也受机构范围限制。</p>
     */
    private boolean restrictByOrg;
}
