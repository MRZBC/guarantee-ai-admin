package com.guarantee.auth.vo;

/** 登录响应。 */
public record LoginResponse(
        String token,
        String tokenType,
        long expiresIn,
        CurrentUserVO user) {
}
