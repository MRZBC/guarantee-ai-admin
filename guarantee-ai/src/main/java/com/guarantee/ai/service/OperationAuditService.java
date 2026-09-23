package com.guarantee.ai.service;

import com.guarantee.ai.entity.AiOperationAudit;
import com.guarantee.ai.mapper.AiOperationAuditMapper;
import com.guarantee.ai.mapper.OperationAuditQuery;
import com.guarantee.common.exception.BizException;
import com.guarantee.common.security.Permissions;
import com.guarantee.common.security.SensitiveFieldMasker;
import com.guarantee.system.scope.DataScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 操作审计服务（SYS-A-*）。
 *
 * <p>三件事必须在本类里一次做对，缺一不可：</p>
 * <ol>
 *   <li><b>写入前脱敏</b>（SYS-A-02a/02b）：敏感字段只记字段名与"是否变更"，
 *       与操作者角色无关（ADMIN 同样脱敏）。脱敏在写入前完成，因为审计表可能被导出、
 *       被 SQL 直查或被备份流转，返回层脱敏覆盖不了这些路径。</li>
 *   <li><b>查询护栏</b>（SYS-A-17 / SYS-A-10）：时间区间必填且 ≤90 天；
 *       ROLE/PERMISSION 类目标仅 ADMIN 可见。</li>
 *   <li><b>快照上限</b>（SYS-A-16）：单行 JSON 快照 ≤8KB，超限只记 changedFields
 *       并标记 truncated，防止快照膨胀把容量估算推翻。</li>
 * </ol>
 */
@Service
public class OperationAuditService {

    private static final Logger log = LoggerFactory.getLogger(OperationAuditService.class);

    /** 时间跨度上限（SYS-A-17）。 */
    public static final int MAX_RANGE_DAYS = 90;

    /** 查询默认/最大条数。 */
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    /** 仅 ADMIN 可见的目标类型（权限主数据，SYS-A-10）。 */
    private static final List<String> ADMIN_ONLY_TARGET_TYPES = List.of("ROLE", "PERMISSION");

    /**
     * 非 ADMIN 可见的目标类型。
     *
     * <p>常量名保留历史的 {@code ORG_SCOPED}，但语义已变（PLAN-移除用户与部门的机构归属 §8 Q3）：
     * 用户、部门都不再挂机构，这组白名单**不再代表"有机构归属"**，
     * 而是"非超级管理员可以查看的操作审计目标类型"。ROLE/PERMISSION 类
     * （权限主数据）仍然仅超级管理员可见，该限制继续有效。</p>
     */
    private static final List<String> ORG_SCOPED_TARGET_TYPES = List.of("USER", "ORG", "DEPT");

    private final AiOperationAuditMapper auditMapper;
    private final ObjectMapper objectMapper;

    public OperationAuditService(AiOperationAuditMapper auditMapper, ObjectMapper objectMapper) {
        this.auditMapper = auditMapper;
        this.objectMapper = objectMapper;
    }

    // ==================================================================
    // 写入
    // ==================================================================

    /**
     * 一次操作审计的写入参数。
     *
     * @param source          AI / WEB
     * @param action          CREATE / UPDATE / DISABLE / ENABLE / ASSIGN_ROLES ...
     * @param targetType      USER / ORG / DEPT / ROLE / INSURANCE_TYPE
     * @param targetName      目标展示名
     * @param before          变更前快照（新增为空）
     * @param after           变更后快照（停用为空）
     * @param result          SUCCESS / FAILED / REJECTED / EXPIRED / PARTIAL
     * @param errorMessage    失败原因
     * @param changedFields   显式声明的变更字段（与快照 diff 取并集）
     */
    public record AuditEntry(
            String source,
            String action,
            String targetType,
            Long targetId,
            String targetName,
            Map<String, Object> before,
            Map<String, Object> after,
            String result,
            String errorMessage,
            Set<String> changedFields) {

        public static AuditEntry of(String source, String action, String targetType,
                                    Long targetId, String targetName,
                                    Map<String, Object> before, Map<String, Object> after,
                                    String result) {
            return new AuditEntry(source, action, targetType, targetId, targetName,
                    before, after, result, null, Set.of());
        }
    }

    /**
     * 写入一条操作审计。
     *
     * <p><b>与业务同事务</b>（SYS-A-03）：本方法不吞异常——审计写入失败必须导致业务回滚，
     * 否则会出现"数据改了但没痕迹"的最坏情况。</p>
     */
    @Transactional
    public Long record(AuditEntry entry, OperatorContext operator,
                       Long proposalId, Long conversationId, String traceId) {
        AiOperationAudit audit = new AiOperationAudit();
        audit.setOperatedAt(LocalDateTime.now());
        audit.setOperatorUserId(operator.userId());
        audit.setOperatorUsername(operator.username());
        audit.setOperatorRealName(operator.realName());
        audit.setSource(entry.source());
        audit.setAction(entry.action());
        audit.setTargetType(entry.targetType());
        audit.setTargetId(entry.targetId());
        audit.setTargetName(truncate(entry.targetName(), 128));
        audit.setResult(entry.result());
        audit.setErrorMessage(truncate(entry.errorMessage(), 500));
        audit.setProposalId(proposalId);
        audit.setConversationId(conversationId);
        audit.setTraceId(traceId);

        // ---- D-4：写入前脱敏，与操作者角色无关 ----
        Map<String, Object> maskedBefore = SensitiveFieldMasker.maskSnapshotForAudit(entry.before());
        Map<String, Object> maskedAfter = SensitiveFieldMasker.maskSnapshotForAudit(entry.after());

        Set<String> changed = new LinkedHashSet<>();
        if (entry.changedFields() != null) {
            changed.addAll(entry.changedFields());
        }
        changed.addAll(diffFields(entry.before(), entry.after()));
        changed.addAll(SensitiveFieldMasker.changedSensitiveFields(entry.before(), entry.after()));

        String beforeJson = toJson(maskedBefore);
        String afterJson = toJson(maskedAfter);
        boolean truncated = false;
        // ---- SYS-A-16：单行快照上限 8KB ----
        if (exceedsLimit(beforeJson)) {
            beforeJson = null;
            truncated = true;
        }
        if (exceedsLimit(afterJson)) {
            afterJson = null;
            truncated = true;
        }
        audit.setBeforeValue(beforeJson);
        audit.setAfterValue(afterJson);
        audit.setChangedFields(truncate(String.join(",", changed), 500));
        audit.setTruncated(truncated ? 1 : 0);

        auditMapper.insert(audit);
        log.info("操作审计已写入 id={} source={} action={} target={}:{} result={} 变更字段={}",
                audit.getId(), entry.source(), entry.action(), entry.targetType(),
                entry.targetId(), entry.result(), changed);
        return audit.getId();
    }

    /**
     * 操作者上下文（助手线程无 CurrentUser，需显式传入）。
     *
     * <p><b>为什么没有 orgId</b>：机构是外部的出函机构、服务于订单，
     * 不是人的归属维度；用户与部门都不再挂机构，"操作人机构"因此没有数据来源。
     * 审计的 {@code operator_org_id} 列已随之删除（ddl 见 {@code V5__drop_operator_org_id.sql}）。</p>
     */
    public record OperatorContext(Long userId, String username, String realName) {
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /** 审计查询结果。 */
    public record AuditPage(long total, List<AiOperationAudit> items) {
    }

    /**
     * 分页查询审计。
     *
     * @param admin 是否 ADMIN（决定 ROLE/PERMISSION 类可见性）
     * @param scope 操作者数据范围。**当前实现不再使用它**：机构已从人/部门上移除，
     *              审计既没有机构可收敛，也没有别的分级维度；保留入参是为了让
     *              阶段二（以权限码重建分级范围）有一个明确的落点，
     *              而不是每个调用方都要再改一次签名。
     */
    @Transactional(readOnly = true)
    public AuditPage query(OperationAuditQuery query, boolean admin, DataScope scope) {
        validateRange(query.getStartDate(), query.getEndDate());

        if (admin) {
            query.setAllowedTargetTypes(null);
        } else {
            // SYS-A-10：非 ADMIN 只能看 USER/ORG/DEPT 类；
            // 若显式点名 ROLE/PERMISSION，直接拒绝而不是返回残缺结果。
            //
            // 注意这里**不再有任何机构收敛代码**：历史实现置 restrictByOrg=true +
            // visibleOrgIds=null，而 Mapper 的 <when visibleOrgIds 非空> 不成立时
            // 会走 <otherwise> 注入 `AND 1 = 0`，导致非 ADMIN 查询审计恒为空——
            // 编译期完全看不出来的静默失效。V5 删列时已把该分支与查询对象字段一并删除，
            // 所以这条路径不可能再回来。
            String requested = query.getTargetType();
            if (requested != null && ADMIN_ONLY_TARGET_TYPES.contains(requested.toUpperCase())) {
                throw new BizException(com.guarantee.common.api.ResultCode.FORBIDDEN,
                        "你没有查看 " + requested + " 类操作审计的权限（该类记录仅超级管理员可见）");
            }
            query.setAllowedTargetTypes(ORG_SCOPED_TARGET_TYPES);
        }

        long total = auditMapper.countByQuery(query);
        if (total == 0) {
            return new AuditPage(0L, List.of());
        }
        // 「全部」分支：页面显式要求不限条数（`all=true`）时不做上限收敛。
        // 唯一的护栏是上面强制校验的 ≤90 天区间（SYS-A-17）：分区裁剪已把扫描范围限定住。
        // 注意 AI 工具 queryOperationAudit **不会**走这条分支（它不传 all），
        // 因此 SYS-Q-06 对工具返回值的 200 条上限保持不变。
        int limit = query.isAll() ? UNLIMITED_LIMIT : clampLimit(query.getLimit());
        List<AiOperationAudit> items = auditMapper.selectPage(query, 0, limit);
        return new AuditPage(total, items);
    }

    /**
     * 「全部」时的 LIMIT 取值。
     *
     * <p>{@code Integer.MAX_VALUE} 直接写进 {@code LIMIT #{limit}} 是合法的（MySQL 的 LIMIT 接受大整数），
     * 语义就是"不设条数上限"。</p>
     *
     * <p><b>为什么敢放开</b>：查询本身被强制要求带 ≤90 天的 {@code operated_at} 区间，
     * 且审计表按月分区、在线窗口 24 个月，所以这不是"全表无界扫描"。
     * <b>代价</b>：区间内数据量很大时响应体会很大（页面上仍会如实显示"共 N 条 / 已显示 M 条"），
     * 这是"要看全部"与"响应可控"之间的取舍，由 ADMIN 主动选择「全部」时才承担。</p>
     */
    public static final int UNLIMITED_LIMIT = Integer.MAX_VALUE;

    /** 条数上限收敛：默认 50，最大 200（SYS-Q-06）。 */
    public static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /**
     * 查询护栏（SYS-A-17 / TEST-23）。
     *
     * <p>时间条件缺失或跨度超限时**明确拒绝**并给出可读提示，
     * 而不是退化成一次全表扫描。</p>
     */
    public void validateRange(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            throw BizException.badRequest(
                    "操作审计查询必须提供明确的时间区间（startDate / endDate），"
                            + "例如最近 7 天请传 startDate 与 endDate，禁止无时间条件的全量扫描");
        }
        if (start.isAfter(end)) {
            throw BizException.badRequest("startDate 不能晚于 endDate");
        }
        long days = Duration.between(start, end).toDays();
        if (days > MAX_RANGE_DAYS) {
            throw BizException.badRequest(
                    "操作审计查询的时间跨度最大 " + MAX_RANGE_DAYS + " 天，当前为 " + days + " 天，请收窄区间");
        }
    }

    /** 供页面接口使用的权限检查（ROLE/PERMISSION 类仅 ADMIN）。 */
    public static boolean requiresAdmin(List<String> permissions) {
        return permissions == null || !permissions.contains(Permissions.AUDIT_VIEW);
    }

    // ==================================================================
    // 容量可观测与归档（SYS-A-18 / SYS-A-14）
    // ==================================================================

    /** 在线行数。 */
    @Transactional(readOnly = true)
    public long onlineRows() {
        Long count = auditMapper.countAll();
        return count == null ? 0L : count;
    }

    /** 最老在线记录时间；无数据时返回 null。 */
    @Transactional(readOnly = true)
    public LocalDateTime oldestOperatedAt() {
        return auditMapper.selectOldestOperatedAt();
    }

    /**
     * 删除在线窗口中早于 {@code before} 的记录。
     *
     * <p>真实环境应优先 {@code DROP PARTITION}（元数据操作、秒级、无长事务），
     * 本方法只作为"存量非分区表"的兜底路径。</p>
     */
    @Transactional
    public int purgeBefore(LocalDateTime before) {
        int deleted = auditMapper.deleteBefore(before);
        if (deleted > 0) {
            log.info("操作审计清理完成：删除 {} 条早于 {} 的记录", deleted, before);
        }
        return deleted;
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /** 结构化快照的字段级 diff（SYS-A-02 要求结构化，不得只存一句描述）。 */
    private static List<String> diffFields(Map<String, Object> before, Map<String, Object> after) {
        Set<String> keys = new LinkedHashSet<>();
        if (before != null) {
            keys.addAll(before.keySet());
        }
        if (after != null) {
            keys.addAll(after.keySet());
        }
        List<String> changed = new ArrayList<>();
        for (String key : keys) {
            Object b = before == null ? null : before.get(key);
            Object a = after == null ? null : after.get(key);
            if (!java.util.Objects.equals(b, a)) {
                changed.add(key);
            }
        }
        return changed;
    }

    private boolean exceedsLimit(String json) {
        return json != null && json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > SensitiveFieldMasker.SNAPSHOT_MAX_BYTES;
    }

    private String toJson(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            log.warn("审计快照序列化失败，放弃快照内容：{}", ex.getMessage());
            return null;
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
