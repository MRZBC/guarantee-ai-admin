package com.guarantee.system.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 部门配置响应对象（MyBatis 结果类型，orgName 来自 sys_org 关联）。
 */
@Data
public class DepartmentVO {

    private Long id;
    private String deptCode;
    private String deptName;
    private Long orgId;
    /** 所属机构名称，关联 sys_org */
    private String orgName;
    private Long parentId;
    /** 状态 1启用 0停用 */
    private Integer status;
    private Integer sortNo;
    private LocalDateTime createdAt;
}
