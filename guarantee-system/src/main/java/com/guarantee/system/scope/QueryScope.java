package com.guarantee.system.scope;

import com.guarantee.common.security.CurrentUser;

import java.util.List;

/**
 * 查询对象内嵌的数据范围持有者。
 *
 * <p>查询 DTO 统一持有一个 {@code scope} 字段，Mapper 的 {@code queryWhere} 片段即可
 * 用 {@code q.scope.unrestricted} / {@code q.scope.orgIds} 判断是否注入机构过滤
 * （SYS-P-08：过滤必须由服务端强制，而不是可选的调用参数）。</p>
 *
 * <p>Web 层用 {@link #of(DataScope)} 显式写入；无数据范围要求的接口（例如仅 ADMIN 可用的
 * 全局审计）用 {@link #unrestricted()}。</p>
 */
public final class QueryScope {

    /** 不受机构限制。 */
    public final boolean unrestricted;

    /**
     * 是否受机构限制（{@code !unrestricted}）。
     *
     * <p>⚠️ 刻意同时暴露正向与反向的**字段**，而不是只提供 {@code unrestricted}：
     * MyBatis 的 OGNL 对 {@code !q.scope.unrestricted} 这类"取反 + 嵌套属性"的写法
     * 不会抛错、而是**静默判定为 false**，导致机构过滤条件整段不生效——
     * 表现为"数据范围形同虚设"，而且没有任何日志。改用正向判断
     * （{@code q.scope.restricted}）即可稳定生效。</p>
     */
    public final boolean restricted;

    /** 可见机构 id 列表；{@code unrestricted} 为 true 时为空列表。 */
    public final List<Long> orgIds;

    private QueryScope(boolean unrestricted, List<Long> orgIds) {
        this.unrestricted = unrestricted;
        this.restricted = !unrestricted;
        this.orgIds = orgIds == null ? List.of() : List.copyOf(orgIds);
    }

    /** 不受限（全量）。 */
    public static QueryScope unrestricted() {
        return new QueryScope(true, List.of());
    }

    /** 由已解析的数据范围构造。 */
    public static QueryScope of(DataScope scope) {
        if (scope == null || scope.unrestricted()) {
            return unrestricted();
        }
        return new QueryScope(false, scope.orgIds());
    }

    /**
     * 从请求线程的登录用户构造。
     *
     * <p>此时权限尚未按机构层级解析，因此必须显式提供由
     * {@code DataScopeService#resolve} 得到的结果——本方法保留给"确实不需要机构过滤"的
     * 场景（例如 ANALYST 自查工具调用记录，范围固定为本人）。</p>
     */
    public static QueryScope currentUserOrUnrestricted() {
        CurrentUser.Principal principal = CurrentUser.get();
        if (principal == null || principal.isAdmin()) {
            return unrestricted();
        }
        return new QueryScope(false, principal.orgId() == null ? List.of() : List.of(principal.orgId()));
    }

    /** 序列化/日志友好。 */
    public String describe() {
        return unrestricted ? "unrestricted" : orgIds.toString();
    }
}
