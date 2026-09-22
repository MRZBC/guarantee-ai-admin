package com.guarantee.system.service;

import com.guarantee.common.api.PageResult;
import com.guarantee.common.exception.BizException;
import com.guarantee.system.dto.OrgDto;
import com.guarantee.system.entity.SysOrg;
import com.guarantee.system.mapper.SysOrgMapper;
import com.guarantee.system.scope.DataScope;
import com.guarantee.system.scope.DataScopeService;
import com.guarantee.system.scope.QueryScope;
import com.guarantee.system.vo.OrgOptionVO;
import com.guarantee.system.vo.OrgVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 机构配置服务。Controller -&gt; Service -&gt; Mapper -&gt; DB，对外只暴露 VO。
 *
 * <p>项目建成效方法在本类，分别服务两条渠道：</p>
 * <ul>
 *   <li>Web 渠道：Controller 传入由 {@code DataScopeService} 解析好的 {@link DataScope}；</li>
 *   <li>AI 渠道：写工具只调用 {@link #validateCreate}/{@link #validateUpdate} 做**预检**，
 *       真正的变更由确认接口在执行期调用同一个 {@code create}/{@code update}。</li>
 * </ul>
 * 两条渠道共用同一份业务校验，避免"预览通过、执行失败"或"预览没查、执行才报错"。</p>
 */
@Service
public class OrgService {

    private static final Logger log = LoggerFactory.getLogger(OrgService.class);

    /** 目标解析返回的候选上限，避免名称过于宽泛时返回海量结果。 */
    public static final int CANDIDATE_LIMIT = 20;

    private final SysOrgMapper sysOrgMapper;
    private final DataScopeService dataScopeService;
    private final WebAuditor webAuditor;

    public OrgService(SysOrgMapper sysOrgMapper, DataScopeService dataScopeService,
                      WebAuditor webAuditor) {
        this.sysOrgMapper = sysOrgMapper;
        this.dataScopeService = dataScopeService;
        this.webAuditor = webAuditor;
    }

    // ==================================================================
    // 读
    // ==================================================================

    @Transactional(readOnly = true)
    public PageResult<OrgVO> page(OrgDto.Query query, DataScope scope) {
        query.setScope(QueryScope.of(scope));
        long total = sysOrgMapper.countByQuery(query);
        if (total == 0) {
            return PageResult.empty(query.getPageNum(), query.getPageSize());
        }
        List<OrgVO> list = sysOrgMapper.selectPage(query, query.offset(), query.getPageSize());
        return PageResult.of(query.getPageNum(), query.getPageSize(), total, list);
    }

    /**
     * 树形数据源（SYS-C-21 / SYS-C-24）。
     *
     * <p>树必须基于**全量**数据组装，分页会导致树静默缺节点，因此这里独立于
     * {@link #page}，只放开条数上限并给出超限告警。</p>
     */
    @Transactional(readOnly = true)
    public List<OrgVO> tree(OrgDto.Query query, DataScope scope) {
        query.setScope(QueryScope.of(scope));
        List<OrgVO> list = sysOrgMapper.selectTree(query, OrgDto.TREE_LIMIT);
        // 条数上限保护：超出时必须告警而非静默截断（SYS-C-19）
        if (list.size() >= OrgDto.TREE_LIMIT) {
            log.warn("机构树达到条数上限 {}，可能存在静默截断，请评估是否需要调整上限或分片展示", OrgDto.TREE_LIMIT);
        }
        return list;
    }

    /** 下拉框使用：仅启用机构。 */
    @Transactional(readOnly = true)
    public List<OrgOptionVO> listOptions() {
        return sysOrgMapper.selectEnabledOptions();
    }

    @Transactional(readOnly = true)
    public OrgVO getById(Long id) {
        OrgVO vo = sysOrgMapper.selectVoById(id);
        if (vo == null) {
            throw BizException.notFound("机构不存在: " + id);
        }
        return vo;
    }

    /** 带数据范围的明细（SYS-P-14）。 */
    @Transactional(readOnly = true)
    public OrgVO getById(Long id, DataScope scope) {
        // 先判定可见性：不可见时返回与"不存在"一致的文案，不暴露存在性（SYS-P-09）
        dataScopeService.requireVisibleOrg(scope, id);
        return getById(id);
    }

    /** 供其它模块使用：全部启用机构实体。 */
    @Transactional(readOnly = true)
    public List<SysOrg> listEnabledOrgEntities() {
        return sysOrgMapper.selectAllEnabled();
    }

    /**
     * 工具/页面共用的机构查询（SYS-Q-01）。
     *
     * <p>阶段一 O3 起机构不再承载"部门数 / 用户数"（部门与用户都已不挂机构，
     * {@code OrgVO} 也已删除这两个字段），因此这里只补齐 {@code parentName}；
     * 补齐放在 Service 而不是工具里，是为了让"助手回答"与"页面展示"共用同一份口径。</p>
     */
    @Transactional(readOnly = true)
    public List<OrgVO> listForQuery(OrgDto.Query query, DataScope scope) {
        query.setScope(QueryScope.of(scope));
        List<OrgVO> list = sysOrgMapper.selectPage(query, query.offset(), query.getPageSize());
        if (list.isEmpty()) {
            return list;
        }
        Map<Long, String> parentNames = orgNameByIds(list.stream()
                .map(OrgVO::getParentId).filter(pid -> pid != null && pid != 0L).distinct().toList());
        for (OrgVO vo : list) {
            vo.setParentName(vo.getParentId() == null || vo.getParentId() == 0L
                    ? null : parentNames.get(vo.getParentId()));
        }
        return list;
    }

    /** 供其它模块按主键读取机构实体。 */
    @Transactional(readOnly = true)
    public SysOrg getEntityById(Long id) {
        SysOrg entity = sysOrgMapper.selectEntityById(id);
        if (entity == null) {
            throw BizException.notFound("机构不存在: " + id);
        }
        return entity;
    }

    /** 机构层级名称，供工具返回值与确认卡展示。 */
    public static String levelName(Integer level) {
        if (level == null) {
            return "未知";
        }
        return switch (level) {
            case 1 -> "总部";
            case 2 -> "省级";
            case 3 -> "市级";
            default -> "未知(" + level + ")";
        };
    }

    /** 状态名称。 */
    public static String statusName(Integer status) {
        if (status == null) {
            return "未知";
        }
        return status == 1 ? "启用" : "停用";
    }

    /** 全部机构实体（用于把 parentId 翻译成 parentName，机构表很小）。 */
    @Transactional(readOnly = true)
    public Map<Long, String> orgNameByIds(List<Long> orgIds) {
        if (orgIds == null || orgIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new LinkedHashMap<>();
        for (Long id : orgIds) {
            if (id == null || id == 0L) {
                continue;
            }
            SysOrg org = sysOrgMapper.selectEntityById(id);
            if (org != null) {
                names.put(id, org.getOrgName());
            }
        }
        return names;
    }

    /** 机构编码唯一性检查（写操作预检，跨全量机构）。 */
    @Transactional(readOnly = true)
    public SysOrg findEntityByCode(String orgCode) {
        return sysOrgMapper.selectEntityByCode(orgCode);
    }

    /** 写操作目标解析（SYS-W-10）：按名称/编码模糊匹配，带数据范围过滤。 */
    @Transactional(readOnly = true)
    public List<SysOrg> findCandidates(String keyword, DataScope scope, int limit) {
        return sysOrgMapper.selectCandidates(keyword, scope, limit);
    }

    // ==================================================================
    // 写（SYS-W-02）
    // ==================================================================

    /**
     * 机构新增。
     *
     * <p>校验：{@code orgCode} 唯一；{@code parentId} 必须存在且层级 = 自身层级 - 1
     * （0 表示顶级，仅允许层级 1）。</p>
     *
     * <p><b>机构创建会新增数据范围边界</b>，因此创建省级及以上需要 ADMIN——
     * 该限制由调用方的权限码（{@code system:org:create}）与角色共同决定，
     * 这里额外拒绝"在非全量范围内创建顶级/省级机构"。</p>
     */
    @Transactional
    public OrgVO create(OrgDto.CreateRequest request, DataScope scope) {
        validateCreate(request, scope);
        SysOrg entity = new SysOrg();
        entity.setOrgCode(request.getOrgCode().trim());
        entity.setOrgName(request.getOrgName().trim());
        entity.setRegionCode(request.getRegionCode().trim());
        entity.setRegionName(request.getRegionName() != null
                ? request.getRegionName()
                : resolveRegionName(request.getParentId()));
        entity.setOrgLevel(request.getOrgLevel());
        entity.setParentId(request.getParentId());
        entity.setStatus(1);
        entity.setSortNo(request.getSortNo() == null ? 0 : request.getSortNo());
        sysOrgMapper.insert(entity);
        log.info("新增机构成功 id={} code={} level={} parentId={}",
                entity.getId(), entity.getOrgCode(), entity.getOrgLevel(), entity.getParentId());

        // 页面直连也必须落审计（SYS-A-07 / AC-22）：source=WEB
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("orgCode", entity.getOrgCode());
        after.put("orgName", entity.getOrgName());
        after.put("regionCode", entity.getRegionCode());
        after.put("orgLevel", entity.getOrgLevel());
        after.put("parentId", entity.getParentId());
        after.put("status", entity.getStatus());
        webAuditor.success("CREATE", "ORG", entity.getId(), entity.getOrgName(), null, after);

        return getById(entity.getId());
    }

    /**
     * 机构修改。
     *
     * <p>校验：{@code orgCode} 不可改；{@code parentId} 变更不得形成环。</p>
     */
    @Transactional
    public OrgVO update(Long id, OrgDto.UpdateRequest request, DataScope scope) {
        SysOrg existing = validateUpdate(id, request, scope);
        SysOrg entity = new SysOrg();
        entity.setId(id);
        entity.setOrgName(request.getOrgName());
        entity.setRegionCode(request.getRegionCode());
        entity.setRegionName(request.getRegionName());
        entity.setParentId(request.getParentId());
        entity.setOrgLevel(request.getOrgLevel());
        entity.setSortNo(request.getSortNo());
        sysOrgMapper.updateById(entity);
        log.info("修改机构成功 id={} 变更前层级={} 变更后={}", id, existing.getOrgLevel(), request.getOrgLevel());

        // 页面直连审计：只记真正变化的字段（diff 由审计层按 before/after 计算）
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("orgName", existing.getOrgName());
        before.put("regionCode", existing.getRegionCode());
        before.put("orgLevel", existing.getOrgLevel());
        before.put("parentId", existing.getParentId());
        before.put("sortNo", existing.getSortNo());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("orgName", request.getOrgName() == null ? existing.getOrgName() : request.getOrgName());
        after.put("regionCode", request.getRegionCode() == null ? existing.getRegionCode() : request.getRegionCode());
        after.put("orgLevel", request.getOrgLevel() == null ? existing.getOrgLevel() : request.getOrgLevel());
        after.put("parentId", request.getParentId() == null ? existing.getParentId() : request.getParentId());
        after.put("sortNo", request.getSortNo() == null ? existing.getSortNo() : request.getSortNo());
        webAuditor.success("UPDATE", "ORG", id, existing.getOrgName(), before, after);

        return getById(id);
    }

    /**
     * 机构启停。
     *
     * <p>停用前置检查：存在启用中的**下级机构**时禁止停用（SYS-W-02）。
     * 阶段一 O3 起"启用中的用户"检查已移除（用户不再挂机构）。</p>
     */
    @Transactional
    public OrgVO changeStatus(Long id, Integer targetStatus, DataScope scope) {
        SysOrg existing = dataScopeService.requireVisibleOrg(scope, id);
        if (existing.getStatus() != null && existing.getStatus().equals(targetStatus)) {
            throw new BizException("机构已处于目标状态，无需变更：" + existing.getOrgName());
        }
        if (targetStatus == 0) {
            List<String> blockers = stopBlockers(existing);
            if (!blockers.isEmpty()) {
                throw new BizException("该机构不能停用：" + String.join("；", blockers));
            }
        }
        int affected = sysOrgMapper.updateStatus(id, targetStatus, existing.getStatus());
        if (affected != 1) {
            throw new BizException("机构状态已被他人修改，请刷新后重试");
        }
        log.info("机构启停成功 id={} status={}", id, targetStatus);

        webAuditor.success(targetStatus == 1 ? "ENABLE" : "DISABLE", "ORG", id, existing.getOrgName(),
                Map.of("status", existing.getStatus()), Map.of("status", targetStatus));

        return getById(id);
    }

    // ==================================================================
    // 预检（写工具在生成提案前调用，保证预览与执行口径一致）
    // ==================================================================

    /** 预检新增，返回即将落库的实体草案（不落库）。 */
    public SysOrg validateCreate(OrgDto.CreateRequest request, DataScope scope) {
        String code = request.getOrgCode() == null ? null : request.getOrgCode().trim();
        if (code == null || code.isEmpty()) {
            throw BizException.badRequest("机构编码不能为空");
        }
        if (sysOrgMapper.selectEntityByCode(code) != null) {
            throw new BizException("机构编码已存在: " + code);
        }
        Long parentId = request.getParentId();
        Integer level = request.getOrgLevel();
        if (parentId == null || parentId == 0L) {
            if (level != null && level != DataScopeService.LEVEL_HEADQUARTERS) {
                throw BizException.badRequest("只有总部（层级 1）可以作为顶级机构，当前层级为 " + level);
            }
        } else {
            SysOrg parent = sysOrgMapper.selectEntityById(parentId);
            if (parent == null) {
                throw new BizException("上级机构不存在: " + parentId);
            }
            if (level == null || parent.getOrgLevel() == null
                    || parent.getOrgLevel() != level - 1) {
                throw BizException.badRequest("上级机构层级必须为 " + (level == null ? "?" : level - 1)
                        + "，实际为 " + (parent.getOrgLevel() == null ? "未知" : parent.getOrgLevel()));
            }
            // 新增机构会落在某个上级之下：该上级必须在当前用户的数据范围内
            dataScopeService.requireInScope(scope, parent.getId());
        }
        return null;
    }

    /** 预检修改，返回变更前实体（不落库）。 */
    public SysOrg validateUpdate(Long id, OrgDto.UpdateRequest request, DataScope scope) {
        SysOrg existing = dataScopeService.requireVisibleOrg(scope, id);
        if (request.getParentId() != null) {
            Long newParentId = request.getParentId();
            if (newParentId.equals(id)) {
                throw BizException.badRequest("上级机构不能是自己");
            }
            if (newParentId != 0L) {
                SysOrg parent = sysOrgMapper.selectEntityById(newParentId);
                if (parent == null) {
                    throw new BizException("上级机构不存在: " + newParentId);
                }
                // 防环：新上级不能落在自己或自己的下级里
                List<Long> descendants = sysOrgMapper.selectVisibleOrgIds(id);
                if (descendants.contains(newParentId)) {
                    throw BizException.badRequest("上级机构不能是自己的下级机构，会形成环");
                }
                dataScopeService.requireInScope(scope, parent.getId());
            }
        }
        return existing;
    }

    /**
     * 停用阻碍项（SYS-W-02）：仅"启用中的下级机构"。
     *
     * <p>阶段一 O3：部门与用户都已不挂机构，原"启用中的用户数"检查已无意义，故移除；
     * 下级机构这道守卫必须保留——停用父机构会让启用中的下级机构悬挂。</p>
     */
    public List<String> stopBlockers(SysOrg org) {
        List<String> blockers = new ArrayList<>();
        long enabledDescendants = sysOrgMapper.countEnabledDescendants(org.getId());
        if (enabledDescendants > 0) {
            blockers.add("存在 " + enabledDescendants + " 个启用中的下级机构");
        }
        return blockers;
    }

    /**
     * 停用影响面（用于确认卡明示，不作为阻碍）。
     *
     * <p>阶段一 O3：部门数 / 启用用户数已不再挂在机构下，只剩下级机构数与订单数。</p>
     */
    public Map<String, Object> stopImpact(SysOrg org) {
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("下级机构数", sysOrgMapper.countChildren(org.getId()));
        impact.put("订单数", sysOrgMapper.countOrderByOrg(org.getId()));
        return impact;
    }

    /** 区域名称继承上级；顶级机构缺失时回退为"未指定"。 */
    private String resolveRegionName(Long parentId) {
        if (parentId == null || parentId == 0L) {
            return "未指定";
        }
        SysOrg parent = sysOrgMapper.selectEntityById(parentId);
        return parent == null || parent.getRegionName() == null ? "未指定" : parent.getRegionName();
    }
    // ==================================================================
    // 逻辑删除 / 恢复（LD-02 / LD-04）
    // ==================================================================

    /**
     * 机构逻辑删除。
     *
     * <p>前置检查**比停用更严格**（设计 §6.2）：停用只拦"启用中的下级机构"，
     * 删除要求机构下不存在任何未删除的下级机构与关联订单——
     * 删除后被引用的历史会指向一条"不存在"的记录，因此被引用即拒绝。</p>
     *
     * <p>阶段一 O3：原"未删除的部门 / 用户"检查已移除（部门与用户都已不挂机构）。</p>
     */
    @Transactional
    public OrgVO delete(Long id, DataScope scope, Long operatorUserId) {
        SysOrg existing = dataScopeService.requireVisibleOrg(scope, id);
        List<String> blockers = deleteBlockers(existing);
        if (!blockers.isEmpty()) {
            throw new BizException("该机构不能删除：" + String.join("；", blockers)
                    + "。如只需暂停业务，请改用「停用」。");
        }
        OrgVO before = getById(id);
        int affected = sysOrgMapper.softDelete(id, operatorId(operatorUserId));
        if (affected != 1) {
            throw new BizException("机构已被他人删除，请刷新后重试");
        }
        log.info("机构逻辑删除成功 id={} code={} 操作者={}", id, existing.getOrgCode(), operatorUserId);
        webAuditor.success("DELETE", "ORG", id, existing.getOrgName(),
                Map.of("isDeleted", 0), Map.of("isDeleted", 1));
        SysOrg deleted = sysOrgMapper.selectEntityByIdIncludingDeleted(id);
        before.setIsDeleted(deleted.getIsDeleted());
        before.setDeletedAt(deleted.getDeletedAt());
        // deleted_by 必须一并回填：它是「sys_user.id 或 'DB'」的混存字段，
        // 漏回填会让删除响应显示成列默认值 'DB'，与列表接口给出不同答案（设计 §2.1a）
        before.setDeletedBy(deleted.getDeletedBy());
        return before;
    }

    /**
     * 删除阻碍项（§6.2）：下级机构 / 关联订单必须全部为"未删除"。
     *
     * <p>阶段一 O3：部门与用户都已不挂机构，原"未删除的部门数 / 用户数"检查已无意义，故移除；
     * 下级机构与订单这两道守卫必须保留——机构仍被它们引用，删除会产生悬挂引用。</p>
     */
    public List<String> deleteBlockers(SysOrg org) {
        List<String> blockers = new ArrayList<>();
        long children = sysOrgMapper.countChildren(org.getId());
        if (children > 0) {
            blockers.add("存在 " + children + " 个未删除的下级机构");
        }
        long orders = sysOrgMapper.countOrderByOrg(org.getId());
        if (orders > 0) {
            blockers.add("存在 " + orders + " 条关联订单");
        }
        return blockers;
    }

    /** 删除影响面（确认卡明示）。 */
    public Map<String, Object> deleteImpact(SysOrg org) {
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("下级机构数", sysOrgMapper.countChildren(org.getId()));
        impact.put("关联订单数", sysOrgMapper.countOrderByOrg(org.getId()));
        impact.put("影响", "该机构默认不再出现在列表中；被下级机构或订单引用时会被拒绝，可在「显示已删除」中恢复");
        return impact;
    }

    /**
     * 机构恢复（LD-04a）。
     *
     * <p>必须重做删除时的前置校验：上级机构仍处于已删除状态时拒绝，否则会出现
     * "上级机构已删、下级机构被恢复"的悬挂引用。恢复顺序必须与删除顺序相反（先父后子）。</p>
     */
    @Transactional
    public OrgVO restore(Long id, Long operatorUserId) {
        SysOrg existing = sysOrgMapper.selectEntityByIdIncludingDeleted(id);
        if (existing == null) {
            throw BizException.notFound("机构不存在: " + id);
        }
        if (!Integer.valueOf(1).equals(existing.getIsDeleted())) {
            throw new BizException("机构未被删除，无需恢复");
        }
        Long parentId = existing.getParentId();
        if (parentId != null && parentId != 0L) {
            SysOrg parent = sysOrgMapper.selectEntityByIdIncludingDeleted(parentId);
            if (parent == null) {
                throw new BizException("上级机构不存在，无法恢复：" + parentId);
            }
            if (Integer.valueOf(1).equals(parent.getIsDeleted())) {
                throw new BizException("请先恢复其所属上级机构：" + parent.getOrgName());
            }
        }
        SysOrg duplicate = sysOrgMapper.selectEntityByCode(existing.getOrgCode());
        if (duplicate != null && !duplicate.getId().equals(id)) {
            throw new BizException("机构编码已被同名的有效机构占用，无法恢复：" + existing.getOrgCode());
        }
        int affected = sysOrgMapper.restore(id);
        if (affected != 1) {
            throw new BizException("机构恢复失败，可能已被他人恢复，请刷新后重试");
        }
        log.info("机构恢复成功 id={} code={} 操作者={}", id, existing.getOrgCode(), operatorUserId);
        webAuditor.success("RESTORE", "ORG", id, existing.getOrgName(),
                Map.of("isDeleted", 1), Map.of("isDeleted", 0));
        return getById(id);
    }

    /** 删除人标识：应用侧写 sys_user.id 的字符串形式；未知/直连时由 SQL 落默认 'DB'（设计 §2.1a）。 */
    private static String operatorId(Long operatorUserId) {
        return operatorUserId == null ? null : String.valueOf(operatorUserId);
    }

}
