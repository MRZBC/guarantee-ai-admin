package com.guarantee.ai.tool;

import java.util.List;

/**
 * {@code queryOrg} 的工具返回值（SYS-Q-01 / SYS-Q-07）。
 *
 * <p>{@code ambiguous} 为 true 时表示关键字命中多个机构：模型必须把候选列给用户确认，
 * 不允许自行选一个（SYS-Q-01 特别要求 / SYS-N-04）。</p>
 */
public record OrgQueryToolResult(
        long total,
        List<OrgItem> items,
        boolean ambiguous,
        String ambiguousHint,
        ToolResultMeta meta) {

    /** 单条机构。 */
    public record OrgItem(
            Long id,
            String orgCode,
            String orgName,
            String regionCode,
            String regionName,
            Integer orgLevel,
            String orgLevelName,
            Long parentId,
            String parentName,
            Integer status,
            String statusName,
            Long deptCount,
            Long userCount,
            /**
             * 是否已被逻辑删除：1=已删除（仅 {@code includeDeleted=true} 时可能出现），
             * 0/null=未删除。模型据此区分"默认不可见"的记录，不得对已删除记录再提变更提案。
             */
            Integer isDeleted,
            /** 删除时间（ISO-8601 字符串）；未删除时为 null。 */
            String deletedAt) {
    }

    /** 无权限：不返回任何业务数据，只给明确原因（SYS-Q-11）。 */
    public static OrgQueryToolResult denied(String reason) {
        return new OrgQueryToolResult(0L, List.of(), false, null, ToolResultMeta.denied(reason));
    }

    /** 无数据（与"无权限"必须可区分）。 */
    public static OrgQueryToolResult empty(String dataSource) {
        return new OrgQueryToolResult(0L, List.of(), false, null, ToolResultMeta.ok(dataSource));
    }
}
