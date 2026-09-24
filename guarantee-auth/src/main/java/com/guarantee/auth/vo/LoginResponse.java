package com.guarantee.auth.vo;

/**
 * 登录响应。
 *
 * @param token              令牌本体
 * @param tokenType          固定 {@code Bearer}
 * @param expiresIn          距**绝对上限**（JWT {@code exp}）的剩余秒数，语义与 AUTH-04 之前一致
 * @param idleTimeoutSeconds 空闲超时秒数（AUTH-04）；连续无请求超过该时长即失效
 * @param mustChangePassword 首次登录必须修改初始密码（P-10 / D1=C）；
 *                           为 true 时除改密/登出/读自己外的一切请求都会被服务端闸门拒绝
 * @param user               当前用户信息
 */
public record LoginResponse(
        String token,
        String tokenType,
        long expiresIn,
        long idleTimeoutSeconds,
        boolean mustChangePassword,
        CurrentUserVO user) {
}
