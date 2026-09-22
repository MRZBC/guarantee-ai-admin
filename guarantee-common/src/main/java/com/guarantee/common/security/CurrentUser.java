package com.guarantee.common.security;

import java.util.List;

/**
 * 当前登录用户上下文。
 *
 * <p>由 guarantee-auth 的 JWT 过滤器在请求进入时写入，业务模块只读。
 * 放在 common 模块是为了避免 guarantee-ai / guarantee-analysis 反向依赖 guarantee-auth。</p>
 *
 * <p><b>为什么 Principal 要携带 roles / permissions</b>：AI 工具执行发生在
 * {@code AiChatService} 自驱动的工具循环里（Reactor 线程），此时 Spring Security 上下文
 * 与 {@code CurrentUser} ThreadLocal 均已失效，权限只能随 {@code ToolContext} 下传。
 * 请求线程上的 Web 接口用 {@code @PreAuthorize} 即可，但工具线程必须有一份可携带的权限快照，
 * 见 SYS-P-02 / SYS-P-03。</p>
 */
public final class CurrentUser {

    /**
     * 登录主体信息。
     *
     * <p>刻意**不含机构**：机构（{@code sys_org}）是外部出函机构，服务于订单，
     * 不是人的归属属性；用户只归属部门（{@code sys_user.dept_id}，NOT NULL）。</p>
     *
     * @param userId      用户主键
     * @param username    登录账号
     * @param realName    姓名
     * @param roles       启用角色编码（来自 JWT claims）
     * @param permissions 启用权限编码（来自 JWT claims，多角色去重）
     */
    public record Principal(Long userId, String username, String realName,
                            List<String> roles, List<String> permissions) {

        public Principal {
            roles = roles == null ? List.of() : List.copyOf(roles);
            permissions = permissions == null ? List.of() : List.copyOf(permissions);
        }

        public boolean hasRole(String roleCode) {
            return roleCode != null && roles.contains(roleCode);
        }

        public boolean isAdmin() {
            return hasRole(Roles.ADMIN);
        }

        public boolean hasPermission(String permission) {
            return permission != null && permissions.contains(permission);
        }
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

    public static List<String> roles() {
        Principal p = HOLDER.get();
        return p == null ? List.of() : p.roles();
    }

    public static List<String> permissions() {
        Principal p = HOLDER.get();
        return p == null ? List.of() : p.permissions();
    }

    /** 未登录返回 false；已登录时判断是否持有指定权限码。 */
    public static boolean hasPermission(String permission) {
        Principal p = HOLDER.get();
        return p != null && p.hasPermission(permission);
    }

    public static void clear() {
        HOLDER.remove();
    }
}
