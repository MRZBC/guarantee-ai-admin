package com.guarantee.system.dto;

import lombok.Data;

/**
 * 角色-权限关联的读取模型（MyBatis 结果类型，用于批量回填角色权限）。
 */
@Data
public class RolePermissionRef {

    private Long roleId;
    private Long permissionId;
    private String permissionName;
}
