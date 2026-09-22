package com.guarantee.system.scope;

import java.util.List;

/**
 * 列表/明细查询的数据范围载体（SYS-P-08）。
 *
 * <p>⚠️ 说明：最终实现改为「查询 DTO 内嵌 {@link QueryScope}」的方案
 * （见 {@code OrgDto.Query#scope}），因为 Mapper 片段需要读到
 * {@code q.scope.restricted} 与 {@code q.scope.orgIds}。
 * 本接口保留作为"需要暴露这两个属性的类型"的契约说明，
 * 新查询 DTO 一律直接持有 {@link QueryScope} 字段，不必实现本接口。</p>
 *
 * <p>保留它还有一个作用：明确记录"数据范围过滤是服务端强制的"这一约束，
 * 使后续新增列表接口的人知道必须带上 scope 字段。</p>
 */
public interface ScopedQuery {

    /** 是否受机构限制（正向属性，避免 MyBatis OGNL 取反静默失效）。 */
    boolean isRestricted();

    /** 可见机构 id 列表；不受限时可为空。 */
    List<Long> getScopeOrgIds();

    /** 把数据范围写入查询对象。 */
    default void applyScope(DataScope scope) {
        setScope(QueryScope.of(scope));
    }

    void setScope(QueryScope scope);

    /** 当前数据范围。 */
    QueryScope getScope();
}
