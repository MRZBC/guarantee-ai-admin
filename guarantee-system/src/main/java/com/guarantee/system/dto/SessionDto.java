package com.guarantee.system.dto;

import com.guarantee.common.api.PageQuery;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 在线会话的请求 DTO（AUTH-05）。
 */
public final class SessionDto {

    private SessionDto() {
    }

    /** 在线会话列表查询条件。 */
    @Getter
    @Setter
    public static class Query extends PageQuery {

        /** 按归属用户精确过滤。 */
        private Long userId;

        /** 按登录账号精确过滤。 */
        @Size(max = 64, message = "登录账号长度不能超过 64")
        private String username;

        /** 账号或姓名的统一模糊词。 */
        @Size(max = 64, message = "关键字长度不能超过 64")
        private String keyword;
    }
}
