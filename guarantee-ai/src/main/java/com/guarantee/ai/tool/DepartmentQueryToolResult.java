package com.guarantee.ai.tool;

import java.util.List;

/**
 * {@code queryDepartment} 的工具返回值（SYS-Q-02 / SYS-Q-07）。
 *
 * <p>{@code ambiguousOrgs} 是机构名歧义的显式出口：{@code orgName} 模糊匹配到多个机构时
 * **不允许猜测**，必须把候选交给模型向用户确认（SYS-Q-02 歧义处理）。</p>
 */
public record DepartmentQueryToolResult(
        long total,
        List<DeptItem> items,
        List<OrgCandidate> ambiguousOrgs,
        String ambiguousHint,
        ToolResultMeta meta) {

    /** 单条部门。 */
    public record DeptItem(
            Long id,
            String deptCode,
            String deptName,
            Long orgId,
            String orgName,
            Long parentId,
            Integer status,
            String statusName,
            Long userCount,
            /** 是否已逻辑删除：1=已删除（仅 includeDeleted=true 时可能出现），0/null=未删除。 */
            Integer isDeleted,
            /** 删除时间（ISO-8601 字符串）；未删除时为 null。 */
            String deletedAt) {
    }

    /** 机构候选（歧义澄清用）。 */
    public record OrgCandidate(Long id, String orgCode, String orgName, String regionName, Integer orgLevel, String orgLevelName) {
    }

    public static DepartmentQueryToolResult denied(String reason) {
        return new DepartmentQueryToolResult(0L, List.of(), List.of(), null, ToolResultMeta.denied(reason));
    }
}
