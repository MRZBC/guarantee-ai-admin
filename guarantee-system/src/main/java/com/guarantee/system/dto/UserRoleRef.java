package com.guarantee.system.dto;

import lombok.Data;

/**
 * 用户-角色关联的读取模型（MyBatis 结果类型，用于批量回填用户角色）。
 */
@Data
public class UserRoleRef {

    private Long userId;
    private Long roleId;
    private String roleCode;
    private String roleName;
}
