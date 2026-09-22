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
 * 数据范围判定（阶段一 O3「显式全量」）。
 *
 * <p><b>现状（阶段一 O3）</b>：用户与部门都已不再挂机构（{@code sys_user.org_id} /
 * {@code sys_department.org_id} 两列已删除，见 {@code V4__drop_org_from_user_and_dept.sql}），
 * 原先按机构层级（总部 / 省级 / 市级）收敛的**分级数据范围已废弃**：
 * {@link #resolve(Long, List)} 恒返回全量，不再按机构收敛；原「{@code orgId == null} →
 * 仅可见自己」的语义同样改为全量。</p>
 *
 * <p><b>阶段二</b>：分级数据范围将以**权限码**重建（不再依赖机构层级），届时本类会重新
 * 产生受限范围。规划见 {@code docs/PLAN-移除用户与部门的机构归属.md}。</p>
 *
 * <p><b>为什么放在 guarantee-system</b>：数据范围是系统管理域的领域规则，
 * AI 层只负责把它随 {@code ToolContext} 透传下来（SYS-P-10）。这样 Web 接口与 AI 工具
 * 走的是同一份判定，不会出现"页面拦住了、助手没拦住"的错位。</p>
 *
 * <p><b>机构本身仍然保留</b>：{@code sys_org} 服务于订单（订单自带 {@code org_id}），
 * 机构增删改查照旧。{@link #requireVisibleOrg(DataScope, Long)} 在全量下恒通过，
 * 但仍校验目标机构**存在**。</p>
 *
 * <p><b>不暴露存在性</b>：跨范围目标一律返回与"不存在"完全一致的错误文案（SYS-P-09），
 * 调用方不得用"目标存在但无权限"这类可区分的措辞。</p>
 */
@Service
public class DataScopeService {

    private static final Logger log = LoggerFactory.getLogger(DataScopeService.class);

    /** 机构层级：总部。 */
    public static final int LEVEL_HEADQUARTERS = 1;

    /** 机构层级：省级。阶段一已不参与范围判定，保留常量以免其它模块编译中断。 */
    public static final int LEVEL_PROVINCE = 2;

    /** 机构层级：市级。阶段一已不参与范围判定，保留常量以免其它模块编译中断。 */
    public static final int LEVEL_CITY = 3;

    /** 阶段一 O3 的全量范围描述。 */
    private static final String FULL_SCOPE_DESCRIPTION = "全量（阶段一 O3：机构维度已移除）";

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
     * <p><b>阶段一 O3</b>：恒返回全量（{@code unrestricted = true}），不再读取机构层级、
     * 不再按机构收敛，也不再有"无机构归属 → 仅可见自己"的分支。{@code userId} / {@code roles}
     * 保留在签名里，是为了让调用方形状与阶段二（以权限码重建分级范围）保持一致。</p>
     *
     * @param userId 用户主键（阶段一仅供日志追踪）
     * @param roles  角色编码列表（阶段一不参与判定；阶段二将以权限码重建分级范围）
     */
    @Transactional(readOnly = true)
    public DataScope resolve(Long userId, List<String> roles) {
        log.debug("解析数据范围：阶段一 O3 恒为全量 userId={} roles={}", userId, roles);
        return new DataScope(true, null, null, List.of(), FULL_SCOPE_DESCRIPTION);
    }

    /**
     * 校验目标机构是否在范围内。
     *
     * <p>阶段一 O3 下 {@link #resolve(Long, List)} 恒返回全量，因此本方法**恒通过**；
     * 保留它是为了让写操作链路（以及阶段二的权限码范围）不需要改动调用点。</p>
     *
     * @throws BizException 不在范围内时抛出，文案与"不存在"一致（SYS-P-09）
     */
    public void requireInScope(DataScope scope, Long targetOrgId) {
        if (!scope.contains(targetOrgId)) {
            throw BizException.notFound(OUT_OF_SCOPE_MESSAGE);
        }
    }

    /**
     * 校验目标机构是否存在（SYS-P-14）。
     *
     * <p>阶段一 O3 下数据范围恒为全量，范围判定**恒通过**；但"目标机构存在"这一道校验
     * 仍然生效——写操作按名称解析 id 时也必须走这里，否则会把不存在的机构当成合法目标。</p>
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
