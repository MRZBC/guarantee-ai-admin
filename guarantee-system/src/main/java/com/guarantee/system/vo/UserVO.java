package com.guarantee.system.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户配置响应对象（MyBatis 结果类型，机构/部门名称来自关联表）。
 *
 * <p>注意：任何读路径都不查询、不返回 password 字段。</p>
 */
@Data
public class UserVO {

    private Long id;
    private String username;
    private String realName;
    private Long orgId;
    /** 所属机构名称，关联 sys_org */
    private String orgName;
    private Long deptId;
    /** 所属部门名称，关联 sys_department */
    private String deptName;
    private String phone;
    private String email;
    /** 状态 1启用 0停用 */
    private Integer status;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    /** 已分配角色ID，来自 sys_user_role */
    private List<Long> roleIds;
    /** 已分配角色名称 */
    private List<String> roleNames;
    /** 已分配角色编码（工具返回值与权限判断需要） */
    private List<String> roleCodes;
    /** 逻辑删除 0正常 1已删除 */
    private Integer isDeleted;
    /** 删除时间（DATETIME(6)，未删除为 null） */
    private LocalDateTime deletedAt;
    /** 删除人标识：应用删除记为 sys_user.id 的字符串，数据库直连删除为 "DB"（设计 §2.1a） */
    private String deletedBy;

}
