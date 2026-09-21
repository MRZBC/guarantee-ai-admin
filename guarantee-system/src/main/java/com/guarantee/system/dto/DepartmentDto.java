package com.guarantee.system.dto;

import com.guarantee.common.api.PageQuery;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 部门配置的请求 DTO。
 */
public final class DepartmentDto {

    private DepartmentDto() {
    }

    /** 部门列表查询条件。 */
    @Getter
    @Setter
    public static class Query extends PageQuery {

        private Long orgId;

        @Size(max = 64, message = "部门名称长度不能超过 64")
        private String deptName;

        private Integer status;
    }
}
