package com.guarantee.system.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 部门配置（sys_department）。
 */
@Data
public class SysDepartment {

    private Long id;
    /** 部门编码 */
    private String deptCode;
    /** 部门名称 */
    private String deptName;
    /** 所属机构 */
    private Long orgId;
    /** 上级部门，0为顶级 */
    private Long parentId;
    /** 状态 1启用 0停用 */
    private Integer status;
    private Integer sortNo;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
