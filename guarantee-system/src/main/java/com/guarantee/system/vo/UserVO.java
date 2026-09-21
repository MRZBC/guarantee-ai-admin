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
}
