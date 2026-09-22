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
    /** 上级部门，0为顶级 */
    private Long parentId;
    /** 状态 1启用 0停用 */
    private Integer status;
    private Integer sortNo;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 逻辑删除 0正常 1已删除（LD-01：所有业务查询默认只看 0） */
    private Integer isDeleted;
    /** 删除时间（DATETIME(6)，未删除为 null；同时是唯一键分量） */
    private LocalDateTime deletedAt;
    /** 删除人：应用写 sys_user.id 字符串，数据库直连删除为 'DB' */
    private String deletedBy;

}
