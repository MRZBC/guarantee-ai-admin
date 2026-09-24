package com.guarantee.auth.vo;

import java.util.List;

/**
 * 当前登录用户信息。字段与前端 `user` 对象严格对应。
 *
 * <p>不含机构字段：机构是外部出函机构，服务于订单，不是人的归属属性。</p>
 *
 * <p>{@code mustChangePassword}（P-10 / D1=C）必须在这里返回：前端刷新页面后会靠
 * {@code GET /api/auth/me} 重建状态，缺了它路由守卫就无法把用户引回改密页，
 * 用户会看到一堆 403。</p>
 */
public record CurrentUserVO(
        Long id,
        String username,
        String realName,
        Long deptId,
        String deptName,
        List<String> roles,
        List<String> permissions,
        boolean mustChangePassword) {
}
