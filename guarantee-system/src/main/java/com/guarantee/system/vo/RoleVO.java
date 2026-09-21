package com.guarantee.system.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 角色配置响应对象（MyBatis 结果类型，权限信息由 Service 批量回填）。
 */
@Data
public class RoleVO {

    private Long id;
    private String roleCode;
    private String roleName;
    private String description;
    /** 状态 1启用 0停用 */
    private Integer status;
    private LocalDateTime createdAt;
    /** 已授权权限ID，来自 sys_role_permission */
    private List<Long> permissionIds;
    /** 已授权权限名称 */
    private List<String> permissionNames;
}
