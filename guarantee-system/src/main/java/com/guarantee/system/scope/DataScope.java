package com.guarantee.system.scope;

import java.util.List;

/**
 * 数据范围（SYS-P-07）：当前登录用户**有权查看的机构集合**。
 *
 * <p>本对象是服务端强制的过滤依据，不允许只在提示词里要求模型自律（SYS-P-08）。
 * 判定逻辑统一放在 {@link DataScopeService}，AI 层只传 {@code orgId} + {@code roles}，
 * 不重复实现（SYS-P-10）。</p>
 *
 * @param unrestricted 是否不受机构限制（ADMIN 或总部用户）
 * @param orgId        当前用户所属机构
 * @param orgLevel     当前用户所属机构层级（1总部 2省级 3市级），机构缺失时为 null
 * @param orgIds       可见机构 id 列表；{@code unrestricted} 为 true 时为空
 * @param description  人类可读的范围描述，用于工具返回值回显与日志
 */
public record DataScope(boolean unrestricted, Long orgId, Integer orgLevel,
                        List<Long> orgIds, String description) {

    /** 不受限范围（ADMIN / 总部）。 */
    public static DataScope all(Long orgId, Integer orgLevel) {
        return new DataScope(true, orgId, orgLevel, List.of(), "全量（不受机构限制）");
    }

    /** 受限范围：只可见给定机构。 */
    public static DataScope of(Long orgId, Integer orgLevel, List<Long> orgIds, String description) {
        return new DataScope(false, orgId, orgLevel, List.copyOf(orgIds), description);
    }

    /** 受限范围：只可见自己所属机构（无机构或机构缺失时的收敛结果）。 */
    public static DataScope singleOrg(Long orgId, Integer orgLevel, String description) {
        return new DataScope(false, orgId, orgLevel, orgId == null ? List.of() : List.of(orgId),
                description);
    }

    /** 目标机构是否在范围内。 */
    public boolean contains(Long targetOrgId) {
        if (unrestricted) {
            return true;
        }
        return targetOrgId != null && orgIds.contains(targetOrgId);
    }

    /** 是否只有一个可见机构（市级用户：仅见本市）。 */
    public boolean isSingleOrg() {
        return !unrestricted && orgIds.size() == 1;
    }
}
