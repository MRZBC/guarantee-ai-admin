package com.guarantee.ai.tool;

import java.util.List;

/**
 * {@code queryDepartment} 的工具返回值（SYS-Q-02 / SYS-Q-07）。
 *
 * <p>部门是内部组织单元，服务于「人」；它不携带机构归属（机构是外部出函机构，服务于订单），
 * 因此出参只保留部门自身属性与 {@code userCount}。</p>
 */
public record DepartmentQueryToolResult(
        long total,
        List<DeptItem> items,
        ToolResultMeta meta) {

    /** 单条部门。 */
    public record DeptItem(
            Long id,
            String deptCode,
            String deptName,
            Long parentId,
            Integer status,
            String statusName,
            Long userCount,
            /** 是否已逻辑删除：1=已删除（仅 includeDeleted=true 时可能出现），0/null=未删除。 */
            Integer isDeleted,
            /** 删除时间（ISO-8601 字符串）；未删除时为 null。 */
            String deletedAt) {
    }

    public static DepartmentQueryToolResult denied(String reason) {
        return new DepartmentQueryToolResult(0L, List.of(), ToolResultMeta.denied(reason));
    }
}
