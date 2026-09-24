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
    /** 所属部门（必填：用户必须属于一个部门） */
    private Long deptId;
    private String phone;
    private String email;
    /** 状态 1启用 0停用 */
    private Integer status;
    /**
     * 首次登录强制改密（P-10 / D1=C）。
     *
     * <p>新建账号与管理员重置密码都会置 1；用户改密成功后清 0。为 1 时服务端闸门
     * （{@code PasswordChangeRequiredFilter}）会拒绝除改密/登出/读自己外的一切请求。</p>
     */
    private Integer mustChangePassword;
    /** 最近登录时间 */
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 逻辑删除 0正常 1已删除（LD-01：所有业务查询默认只看 0） */
    private Integer isDeleted;
    /** 删除时间（DATETIME(6)，未删除为 null；同时是唯一键分量） */
    private LocalDateTime deletedAt;
    /** 删除人：应用写 sys_user.id 字符串，数据库直连删除为 'DB' */
    private String deletedBy;

}
