package com.guarantee.system.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户配置（sys_user）。
 *
 * <p>实体包含 password 散列，仅供登录校验使用，禁止从 Controller 返回。</p>
 */
@Data
public class SysUser {

    private Long id;
    /** 登录账号 */
    private String username;
    /** BCrypt 密码散列，仅登录链路读取 */
    private String password;
    /** 姓名 */
    private String realName;
    /** 所属机构 */
    private Long orgId;
    /** 所属部门 */
    private Long deptId;
    private String phone;
    private String email;
    /** 状态 1启用 0停用 */
    private Integer status;
    /** 最近登录时间 */
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
