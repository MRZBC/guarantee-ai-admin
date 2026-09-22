package com.guarantee.system.scope;

import com.guarantee.common.exception.BizException;
import com.guarantee.system.entity.SysOrg;
import com.guarantee.system.mapper.SysOrgMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 数据范围判定（SYS-P-07 ~ SYS-P-10、SYS-P-14）。
 *
 * <p><b>规则</b>：</p>
 * <ul>
 *   <li>{@code ADMIN} → 全量；</li>
 *   <li>{@code org_level=1}（总部）→ 全量；</li>
 *   <li>{@code org_level=2}（省级）→ 本省机构及其下部门/用户；</li>
 *   <li>{@code org_level=3}（市级）→ 仅本市机构及其下部门/用户。</li>
 * </ul>
 *
 * <p><b>为什么放在 guarantee-system</b>：数据范围是系统管理域的领域规则，
 * AI 层只负责把它随 {@code ToolContext} 透传下来（SYS-P-10）。这样 Web 接口与 AI 工具
 * 走的是同一份判定，不会出现"页面拦住了、助手没拦住"的错位。</p>
 *
 * <p><b>不暴露存在性</b>：跨范围目标一律返回与"不存在"完全一致的错误文案（SYS-P-09），
 * 调用方不得用"目标存在但无权限"这类可区分的措辞。</p>
 */
@Service
public class DataScopeService {

    private static final Logger log = LoggerFactory.getLogger(DataScopeService.class);

    /** 机构层级：总部。 */
    public static final int LEVEL_HEADQUARTERS = 1;

    /** 机构层级：省级。 */
    public static final int LEVEL_PROVINCE = 2;

    /** 机构层级：市级。 */
    public static final int LEVEL_CITY = 3;

    /**
     * 跨范围/不存在的统一文案。
     *
     * <p>刻意与"资源不存在"共用一套措辞，使越权探测无法区分两种情况。</p>
     */
    public static final String OUT_OF_SCOPE_MESSAGE = "目标不存在或不在你的数据范围内";

    private final SysOrgMapper sysOrgMapper;

    public DataScopeService(SysOrgMapper sysOrgMapper) {
        this.sysOrgMapper = sysOrgMapper;
    }

    /**
     * 由 AI 层或 Web 层传入的身份解析数据范围。
     *
     * @param userId 用户主键（仅用于日志）
     * @param orgId  所属机构
     * @param roles  角色编码列表，含 {@code ADMIN} 即全量
     */
    @Transactional(readOnly = true)
    public DataScope resolve(Long userId, Long orgId, List<String> roles) {
        if (roles != null && roles.contains(com.guarantee.common.security.Roles.ADMIN)) {
            return DataScope.all(orgId, null);
        }
        if (orgId == null) {
            // 无机构归属：按最小可见范围处理，只可见自己（避免误放开）
            log.warn("用户 {} 无机构归属，数据范围收敛为自己", userId);
            return DataScope.singleOrg(null, null, "无机构归属（仅可见自己）");
        }
        SysOrg org = sysOrgMapper.selectEntityById(orgId);
        if (org == null) {
            log.warn("用户 {} 的机构 {} 不存在，数据范围收敛为自己", userId, orgId);
            return DataScope.singleOrg(orgId, null, "机构缺失（仅可见本机构）");
        }
        Integer level = org.getOrgLevel();
        if (level != null && level == LEVEL_HEADQUARTERS) {
            return DataScope.all(orgId, level);
        }
        List<Long> orgIds = sysOrgMapper.selectVisibleOrgIds(orgId);
        if (orgIds.isEmpty()) {
            orgIds = List.of(orgId);
        }
        String description = (level != null && level == LEVEL_CITY)
                ? "本市范围：" + org.getOrgName()
                : "本省范围：" + org.getRegionName();
        return DataScope.of(orgId, level, orgIds, description);
    }

    /**
     * 校验目标机构是否在范围内。
     *
     * @throws BizException 不在范围内时抛出，文案与"不存在"一致（SYS-P-09）
     */
    public void requireInScope(DataScope scope, Long targetOrgId) {
        if (!scope.contains(targetOrgId)) {
            throw BizException.notFound(OUT_OF_SCOPE_MESSAGE);
        }
    }

    /**
     * 校验目标机构是否存在且在当前用户的数据范围内（SYS-P-14）。
     *
     * <p>写操作按名称解析 id 时也必须走这里，否则会出现"能停用但看不到"的越权。</p>
     */
    @Transactional(readOnly = true)
    public SysOrg requireVisibleOrg(DataScope scope, Long targetOrgId) {
        if (targetOrgId == null) {
            throw BizException.notFound(OUT_OF_SCOPE_MESSAGE);
        }
        SysOrg org = sysOrgMapper.selectEntityById(targetOrgId);
        if (org == null || !scope.contains(org.getId())) {
            throw BizException.notFound(OUT_OF_SCOPE_MESSAGE);
        }
        return org;
    }
}
