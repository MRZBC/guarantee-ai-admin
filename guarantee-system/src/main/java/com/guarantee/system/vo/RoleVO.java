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
    /** 已授权权限编码（queryRole 的 ROLE_PERMISSION 模式需要） */
    private List<String> permissionCodes;
    /** 已授权权限数（SYS-Q-04 ROLE 模式出参） */
    private Integer permissionCount;
    /** 持有该角色的用户数（SYS-Q-04 ROLE 模式出参） */
    private Integer userCount;
    /** 逻辑删除 0正常 1已删除 */
    private Integer isDeleted;
    /** 删除时间（DATETIME(6)，未删除为 null） */
    private LocalDateTime deletedAt;
    /** 删除人标识：应用删除记为 sys_user.id 的字符串，数据库直连删除为 "DB"（设计 §2.1a） */
    private String deletedBy;

}
