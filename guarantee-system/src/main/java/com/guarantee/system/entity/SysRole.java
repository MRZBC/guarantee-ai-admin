package com.guarantee.system.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色配置（sys_role）。
 */
@Data
public class SysRole {

    private Long id;
    /** 角色编码 */
    private String roleCode;
    /** 角色名称 */
    private String roleName;
    private String description;
    /** 状态 1启用 0停用 */
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
