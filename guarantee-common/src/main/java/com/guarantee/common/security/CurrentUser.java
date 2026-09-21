package com.guarantee.common.security;

/**
 * 当前登录用户上下文。
 *
 * <p>由 guarantee-auth 的 JWT 过滤器在请求进入时写入，业务模块只读。
 * 放在 common 模块是为了避免 guarantee-ai / guarantee-analysis 反向依赖 guarantee-auth。</p>
 */
public final class CurrentUser {

    /**
     * 登录主体最小信息。
     */
    public record Principal(Long userId, String username, String realName, Long orgId) {
    }

    private static final ThreadLocal<Principal> HOLDER = new ThreadLocal<>();

    private CurrentUser() {
    }

    public static void set(Principal principal) {
        HOLDER.set(principal);
    }

    public static Principal get() {
        return HOLDER.get();
    }

    /** 未登录时返回 null。 */
    public static Long userId() {
        Principal p = HOLDER.get();
        return p == null ? null : p.userId();
    }

    public static String username() {
        Principal p = HOLDER.get();
        return p == null ? null : p.username();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
