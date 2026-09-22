package com.guarantee.system.scope;

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
     * <p>阶段一 O3：用户与部门都已不再挂机构，数据范围恒为**全量**，因此本方法恒返回
     * {@link #unrestricted()}（原先"非 ADMIN 则收敛为本人所属机构"的语义已废弃）。
     * 方法保留是为了不改动调用点的形状；阶段二以权限码重建分级范围时会重新实现。</p>
     */
    public static QueryScope currentUserOrUnrestricted() {
        return unrestricted();
    }

    /** 序列化/日志友好。 */
    public String describe() {
        return unrestricted ? "unrestricted" : orgIds.toString();
    }
}
