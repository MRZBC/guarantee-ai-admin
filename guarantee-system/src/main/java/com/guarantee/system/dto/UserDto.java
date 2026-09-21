package com.guarantee.system.dto;

import com.guarantee.common.api.PageQuery;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 用户配置的请求 DTO。
 */
public final class UserDto {

    private UserDto() {
    }

    /** 用户列表查询条件。 */
    @Getter
    @Setter
    public static class Query extends PageQuery {

        @Size(max = 64, message = "登录账号长度不能超过 64")
        private String username;

        @Size(max = 64, message = "姓名长度不能超过 64")
        private String realName;

        private Long orgId;

        private Integer status;
    }
}
