package com.guarantee.system.vo;

import lombok.Data;

/**
 * 部门下拉选项（可按机构过滤）。
 */
@Data
public class DepartmentOptionVO {

    private Long id;
    private String deptName;
    private Long orgId;
}
