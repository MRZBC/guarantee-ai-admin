package com.guarantee.system.service;

import com.guarantee.common.api.PageResult;
import com.guarantee.common.exception.BizException;
import com.guarantee.system.dto.DepartmentDto;
import com.guarantee.system.entity.SysDepartment;
import com.guarantee.system.entity.SysOrg;
import com.guarantee.system.mapper.SysDepartmentMapper;
import com.guarantee.system.mapper.SysOrgMapper;
import com.guarantee.system.scope.DataScope;
import com.guarantee.system.scope.DataScopeService;
import com.guarantee.system.scope.QueryScope;
import com.guarantee.system.vo.DepartmentOptionVO;
import com.guarantee.system.vo.DepartmentVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 部门配置服务。orgName 由 Mapper 关联 sys_org 直接返回。
 *
 * <p>写操作规则（SYS-W-03）：{@code deptCode} / {@code orgId} 不可改；
 * 停用前置检查部门下的用户数。</p>
 */
@Service
public class DepartmentService {

    private static final Logger log = LoggerFactory.getLogger(DepartmentService.class);

    public static final int CANDIDATE_LIMIT = 20;

    private final SysDepartmentMapper sysDepartmentMapper;
    /** 恢复部门时校验所属机构未被删除（LD-04a）。 */
    private final SysOrgMapper sysOrgMapper;
    private final DataScopeService dataScopeService;
    private final WebAuditor webAuditor;

    public DepartmentService(SysDepartmentMapper sysDepartmentMapper,
                             SysOrgMapper sysOrgMapper,
                             DataScopeService dataScopeService,
                             WebAuditor webAuditor) {
        this.sysDepartmentMapper = sysDepartmentMapper;
        this.sysOrgMapper = sysOrgMapper;
        this.dataScopeService = dataScopeService;
        this.webAuditor = webAuditor;
    }

    // ==================================================================
    // 读
    // ==================================================================

    @Transactional(readOnly = true)
    public PageResult<DepartmentVO> page(DepartmentDto.Query query, DataScope scope) {
        query.setScope(QueryScope.of(scope));
        long total = sysDepartmentMapper.countByQuery(query);
        if (total == 0) {
            return PageResult.empty(query.getPageNum(), query.getPageSize());
        }
        List<DepartmentVO> list = sysDepartmentMapper.selectPage(query, query.offset(), query.getPageSize());
        return PageResult.of(query.getPageNum(), query.getPageSize(), total, list);
    }

    /**
     * 树形数据源（SYS-C-21 / SYS-C-24 口径，与机构树同构）。
     *
     * <p>树必须基于**全量**数据组装：沿用分页接口时，部门数超过一页（默认 10）后
     * 树会静默缺失节点，且界面看不出异常。因此本方法独立于 {@link #page}，
     * 只放开条数上限并在触顶时告警（SYS-C-19）。</p>
     *
     * <p>返回扁平列表，嵌套结构由前端按 {@code parentId} 组装——机构为顶级分组节点、
     * 部门为其子节点的形态由前端决定，服务端不引入"机构"这一层伪节点。</p>
     */
    @Transactional(readOnly = true)
    public List<DepartmentVO> tree(DepartmentDto.Query query, DataScope scope) {
        query.setScope(QueryScope.of(scope));
        List<DepartmentVO> list = sysDepartmentMapper.selectTree(query, DepartmentDto.TREE_LIMIT);
        // 条数上限保护：超出时必须告警而非静默截断（SYS-C-19）
        if (list.size() >= DepartmentDto.TREE_LIMIT) {
            log.warn("部门树达到条数上限 {}，可能存在静默截断，请评估是否需要调整上限或分片展示",
                    DepartmentDto.TREE_LIMIT);
        }
        return list;
    }

    /** 带数据范围的明细（SYS-P-14）。 */
    @Transactional(readOnly = true)
    public DepartmentVO getById(Long id, DataScope scope) {
        DepartmentDto.Query query = new DepartmentDto.Query();
        query.setId(id);
        query.setScope(QueryScope.of(scope));
        DepartmentVO vo = sysDepartmentMapper.selectVoScoped(query);
        if (vo == null) {
            // 与"不存在"同一文案，不暴露跨范围目标的存在性（SYS-P-09）
            throw BizException.notFound(DataScopeService.OUT_OF_SCOPE_MESSAGE);
        }
        return vo;
    }

    /** 下拉框使用：仅启用部门，orgId 为空时返回全部。 */
    @Transactional(readOnly = true)
    public List<DepartmentOptionVO> listOptions(Long orgId) {
        return sysDepartmentMapper.selectEnabledOptions(orgId);
    }

    /** 部门下的启用用户数（SYS-Q-02 出参 userCount）。 */
    @Transactional(readOnly = true)
    public long countEnabledUsers(Long deptId) {
        return sysDepartmentMapper.countEnabledUserByDept(deptId);
    }

    /** 写操作目标解析（SYS-W-10）：按名称/编码模糊匹配，带数据范围过滤。 */
    @Transactional(readOnly = true)
    public List<SysDepartment> findCandidates(String keyword, Long orgId, DataScope scope, int limit) {
        return sysDepartmentMapper.selectCandidates(keyword, orgId, scope, limit);
    }

    // ==================================================================
    // 写（SYS-W-03）
    // ==================================================================

    @Transactional
    public DepartmentVO create(DepartmentDto.CreateRequest request, DataScope scope) {
        validateCreate(request, scope);
        SysDepartment entity = new SysDepartment();
        entity.setDeptCode(request.getDeptCode().trim());
        entity.setDeptName(request.getDeptName().trim());
        entity.setOrgId(request.getOrgId());
        entity.setParentId(request.getParentId() == null ? 0L : request.getParentId());
        entity.setStatus(1);
        entity.setSortNo(request.getSortNo() == null ? 0 : request.getSortNo());
        sysDepartmentMapper.insert(entity);
        log.info("新增部门成功 id={} code={} orgId={}", entity.getId(), entity.getDeptCode(), entity.getOrgId());

        // 页面直连审计（SYS-A-07 / AC-22）
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("deptCode", entity.getDeptCode());
        after.put("deptName", entity.getDeptName());
        after.put("orgId", entity.getOrgId());
        after.put("parentId", entity.getParentId());
        after.put("status", entity.getStatus());
        webAuditor.success("CREATE", "DEPT", entity.getId(), entity.getDeptName(), null, after);

        return getById(entity.getId(), scope);
    }

    @Transactional
    public DepartmentVO update(Long id, DepartmentDto.UpdateRequest request, DataScope scope) {
        SysDepartment existing = validateUpdate(id, request, scope);
        SysDepartment entity = new SysDepartment();
        entity.setId(id);
        entity.setDeptName(request.getDeptName());
        entity.setParentId(request.getParentId());
        entity.setSortNo(request.getSortNo());
        sysDepartmentMapper.updateById(entity);
        log.info("修改部门成功 id={}", id);

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("deptName", existing.getDeptName());
        before.put("parentId", existing.getParentId());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("deptName", request.getDeptName() == null ? existing.getDeptName() : request.getDeptName());
        after.put("parentId", request.getParentId() == null ? existing.getParentId() : request.getParentId());
        webAuditor.success("UPDATE", "DEPT", id, existing.getDeptName(), before, after);

        return getById(id, scope);
    }

    @Transactional
    public DepartmentVO changeStatus(Long id, Integer targetStatus, DataScope scope) {
        SysDepartment existing = requireVisible(id, scope);
        if (existing.getStatus() != null && existing.getStatus().equals(targetStatus)) {
            throw new BizException("部门已处于目标状态，无需变更：" + existing.getDeptName());
        }
        if (targetStatus == 0) {
            long users = sysDepartmentMapper.countEnabledUserByDept(id);
            if (users > 0) {
                throw new BizException("该部门下仍有 " + users + " 个启用中的用户，不能停用");
            }
        }
        int affected = sysDepartmentMapper.updateStatus(id, targetStatus, existing.getStatus());
        if (affected != 1) {
            throw new BizException("部门状态已被他人修改，请刷新后重试");
        }
        log.info("部门启停成功 id={} status={}", id, targetStatus);

        webAuditor.success(targetStatus == 1 ? "ENABLE" : "DISABLE", "DEPT", id, existing.getDeptName(),
                Map.of("status", existing.getStatus()), Map.of("status", targetStatus));

        return getById(id, scope);
    }

    // ==================================================================
    // 预检
    // ==================================================================

    public SysDepartment validateCreate(DepartmentDto.CreateRequest request, DataScope scope) {
        String code = request.getDeptCode() == null ? null : request.getDeptCode().trim();
        if (code == null || code.isEmpty()) {
            throw BizException.badRequest("部门编码不能为空");
        }
        if (sysDepartmentMapper.selectEntityByCode(code) != null) {
            throw new BizException("部门编码已存在: " + code);
        }
        // orgId 必须存在且在数据范围内（SYS-W-03）
        dataScopeService.requireVisibleOrg(scope, request.getOrgId());
        Long parentId = request.getParentId();
        if (parentId != null && parentId != 0L) {
            SysDepartment parent = requireVisible(parentId, scope);
            if (!parent.getOrgId().equals(request.getOrgId())) {
                throw BizException.badRequest("上级部门必须与本部门属于同一机构");
            }
        }
        return null;
    }

    public SysDepartment validateUpdate(Long id, DepartmentDto.UpdateRequest request, DataScope scope) {
        SysDepartment existing = requireVisible(id, scope);
        if (request.getParentId() != null && request.getParentId() != 0L) {
            if (request.getParentId().equals(id)) {
                throw BizException.badRequest("上级部门不能是自己");
            }
            SysDepartment parent = requireVisible(request.getParentId(), scope);
            if (!parent.getOrgId().equals(existing.getOrgId())) {
                throw BizException.badRequest("上级部门必须与本部门属于同一机构");
            }
        }
        return existing;
    }

    /** 停用影响面（确认卡明示）。 */
    public Map<String, Object> stopImpact(SysDepartment department) {
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("部门下启用用户数", sysDepartmentMapper.countEnabledUserByDept(department.getId()));
        return impact;
    }

    @Transactional(readOnly = true)
    public SysDepartment requireVisible(Long id, DataScope scope) {
        SysDepartment entity = sysDepartmentMapper.selectEntityById(id);
        if (entity == null || !scope.contains(entity.getOrgId())) {
            throw BizException.notFound(DataScopeService.OUT_OF_SCOPE_MESSAGE);
        }
        return entity;
    }
    // ==================================================================
    // 逻辑删除 / 恢复（LD-02 / LD-04）
    // ==================================================================

    /**
     * 部门逻辑删除。
     *
     * <p>设计 §6.2 对部门只要求"无未删除的用户"。实施中额外增加"无未删除的下级部门"：
     * 部门存在 parent_id 层级，删掉父部门会留下指向已删除父部门的子部门（悬挂引用），
     * 与 §6.2 机构检查"无下级机构"的理由完全一致。属实施期补充，已记入进度文档。</p>
     */
    @Transactional
    public DepartmentVO delete(Long id, DataScope scope, Long operatorUserId) {
        SysDepartment existing = requireVisible(id, scope);
        List<String> blockers = deleteBlockers(existing);
        if (!blockers.isEmpty()) {
            throw new BizException("该部门不能删除：" + String.join("；", blockers)
                    + "。如只需暂停业务，请改用「停用」。");
        }
        DepartmentVO before = getById(id, scope);
        int affected = sysDepartmentMapper.softDelete(id, operatorId(operatorUserId));
        if (affected != 1) {
            throw new BizException("部门已被他人删除，请刷新后重试");
        }
        log.info("部门逻辑删除成功 id={} code={} 操作者={}", id, existing.getDeptCode(), operatorUserId);
        webAuditor.success("DELETE", "DEPT", id, existing.getDeptName(),
                Map.of("isDeleted", 0), Map.of("isDeleted", 1));
        SysDepartment deleted = sysDepartmentMapper.selectEntityByIdIncludingDeleted(id);
        before.setIsDeleted(deleted.getIsDeleted());
        before.setDeletedAt(deleted.getDeletedAt());
        // deleted_by 必须一并回填（混存字段，漏回填会让删除响应显示成列默认值 'DB'，设计 §2.1a）
        before.setDeletedBy(deleted.getDeletedBy());
        return before;
    }

    /** 删除阻碍项（§6.2 + 下级部门）。 */
    public List<String> deleteBlockers(SysDepartment dept) {
        List<String> blockers = new ArrayList<>();
        long users = sysDepartmentMapper.countUserByDept(dept.getId());
        if (users > 0) {
            blockers.add("存在 " + users + " 个未删除的用户");
        }
        long children = sysDepartmentMapper.countChildDept(dept.getId());
        if (children > 0) {
            blockers.add("存在 " + children + " 个未删除的下级部门");
        }
        return blockers;
    }

    /** 删除影响面（确认卡明示）。 */
    public Map<String, Object> deleteImpact(SysDepartment dept) {
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("部门下用户数", sysDepartmentMapper.countUserByDept(dept.getId()));
        impact.put("下级部门数", sysDepartmentMapper.countChildDept(dept.getId()));
        impact.put("影响", "该部门默认不再出现在列表中；被用户或下级部门引用时会被拒绝，可在「显示已删除」中恢复");
        return impact;
    }

    /**
     * 部门恢复（LD-04a）：所属机构与上级部门都必须处于未删除状态。
     */
    @Transactional
    public DepartmentVO restore(Long id, Long operatorUserId) {
        SysDepartment existing = sysDepartmentMapper.selectEntityByIdIncludingDeleted(id);
        if (existing == null) {
            throw BizException.notFound("部门不存在: " + id);
        }
        if (!Integer.valueOf(1).equals(existing.getIsDeleted())) {
            throw new BizException("部门未被删除，无需恢复");
        }
        SysOrg org = sysOrgMapper.selectEntityByIdIncludingDeleted(existing.getOrgId());
        if (org == null) {
            throw new BizException("所属机构不存在，无法恢复");
        }
        if (Integer.valueOf(1).equals(org.getIsDeleted())) {
            throw new BizException("请先恢复其所属机构：" + org.getOrgName());
        }
        Long parentId = existing.getParentId();
        if (parentId != null && parentId != 0L) {
            SysDepartment parent = sysDepartmentMapper.selectEntityByIdIncludingDeleted(parentId);
            if (parent == null) {
                throw new BizException("上级部门不存在，无法恢复：" + parentId);
            }
            if (Integer.valueOf(1).equals(parent.getIsDeleted())) {
                throw new BizException("请先恢复其上级部门：" + parent.getDeptName());
            }
        }
        SysDepartment duplicate = sysDepartmentMapper.selectEntityByCode(existing.getDeptCode());
        if (duplicate != null && !duplicate.getId().equals(id)) {
            throw new BizException("部门编码已被同名的有效部门占用，无法恢复：" + existing.getDeptCode());
        }
        int affected = sysDepartmentMapper.restore(id);
        if (affected != 1) {
            throw new BizException("部门恢复失败，可能已被他人恢复，请刷新后重试");
        }
        log.info("部门恢复成功 id={} code={} 操作者={}", id, existing.getDeptCode(), operatorUserId);
        webAuditor.success("RESTORE", "DEPT", id, existing.getDeptName(),
                Map.of("isDeleted", 1), Map.of("isDeleted", 0));
        return readVoUnscoped(id);
    }

    /** 恢复后回读：此时记录已重新可见，用不带数据范围的查询回读（调用方已做父记录校验）。 */
    private DepartmentVO readVoUnscoped(Long id) {
        DepartmentDto.Query query = new DepartmentDto.Query();
        query.setId(id);
        query.setScope(QueryScope.unrestricted());
        DepartmentVO vo = sysDepartmentMapper.selectVoScoped(query);
        if (vo == null) {
            throw BizException.notFound("部门不存在: " + id);
        }
        return vo;
    }

    /** 删除人标识：应用侧写 sys_user.id 的字符串形式；未知/直连时由 SQL 落默认 'DB'（设计 §2.1a）。 */
    private static String operatorId(Long operatorUserId) {
        return operatorUserId == null ? null : String.valueOf(operatorUserId);
    }

}
