package com.guarantee.system.dto;

import com.guarantee.common.api.PageQuery;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 角色配置的请求 DTO。
 */
public final class RoleDto {

    private RoleDto() {
    }

    /** 角色列表查询条件。 */
    @Getter
    @Setter
    public static class Query extends PageQuery {

        @Size(max = 32, message = "角色编码长度不能超过 32")
        private String roleCode;

        @Size(max = 64, message = "角色名称长度不能超过 64")
        private String roleName;
    }
}
