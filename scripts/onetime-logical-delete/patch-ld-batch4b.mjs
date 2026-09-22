/**
 * 批次 4（后端 B 部分）：5 个域的删除/恢复服务、权限码、Controller 端点、登录删除校验。
 *
 * 依据：设计文档 §6（运行期语义）/ §7（接口与权限）/ §9.4.1-9.4.2；任务书 §6、§7
 *
 * 关键设计点：
 *   - 删除是**统一写入口**：每个域只有一条 softDelete 语句（Mapper 层），
 *     Service 只做领域校验 + 审计 + 令牌撤销（§2.2b 的"统一写入口 + 人工纪律 + 巡检"）；
 *   - 删除不隐式改 status（LD-02），恢复因此能完整回到删除前状态；
 *   - 恢复（LD-04a）必须重做删除时的前置校验：父记录仍被删除 → 拒绝；
 *   - 「显示已删除」（includeDeleted=true）需要 system:*:delete 权限（§7.1），
 *     该判定无法用 @PreAuthorize 表达，必须在方法体内做服务端强制校验。
 *
 * 用法：node scripts/patch-ld-batch4b.mjs
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const SVC = 'guarantee-system/src/main/java/com/guarantee/system/service/';
const CTL = 'guarantee-system/src/main/java/com/guarantee/system/controller/';

function edit(rel, anchor, repl, marker) {
  const path = join(root, rel);
  const src = readFileSync(path, 'utf8');
  if (marker && src.includes(marker)) {
    console.log(`skip: ${rel}`);
    return;
  }
  if (!src.includes(anchor)) {
    throw new Error(`锚点未命中: ${rel}\n---\n${anchor.slice(0, 200)}`);
  }
  writeFileSync(path, src.replace(anchor, repl), 'utf8');
  console.log(`patched: ${rel}`);
}

const OPERATOR_HELPER = `
    /** 删除人标识：应用侧写 sys_user.id 的字符串形式；未知/直连时由 SQL 落默认 'DB'（设计 §2.1a）。 */
    private static String operatorId(Long operatorUserId) {
        return operatorUserId == null ? null : String.valueOf(operatorUserId);
    }
`;

// =====================================================================
// 1. OrgService：删除/恢复（§6.2 机构：无未删除的下级机构/部门/用户/订单）
// =====================================================================
const ORG_SERVICE = `
    // ==================================================================
    // 逻辑删除 / 恢复（LD-02 / LD-04）
    // ==================================================================

    /**
     * 机构逻辑删除。
     *
     * <p>前置检查**比停用更严格**（设计 §6.2）：停用只拦"启用中的下级机构/用户"，
     * 删除要求机构下不存在任何未删除的下级机构、部门、用户与关联订单——
     * 删除后被引用的历史会指向一条"不存在"的记录，因此被引用即拒绝。</p>
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
        return before;
    }

    /** 删除阻碍项（§6.2）：下级机构 / 部门 / 用户 / 关联订单必须全部为"未删除"。 */
    public List<String> deleteBlockers(SysOrg org) {
        List<String> blockers = new ArrayList<>();
        long children = sysOrgMapper.countChildren(org.getId());
        if (children > 0) {
            blockers.add("存在 " + children + " 个未删除的下级机构");
        }
        long departments = sysOrgMapper.countDepartmentByOrg(org.getId());
        if (departments > 0) {
            blockers.add("存在 " + departments + " 个未删除的部门");
        }
        long users = sysOrgMapper.countUserByOrg(org.getId());
        if (users > 0) {
            blockers.add("存在 " + users + " 个未删除的用户");
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
        impact.put("部门数", sysOrgMapper.countDepartmentByOrg(org.getId()));
        impact.put("用户数", sysOrgMapper.countUserByOrg(org.getId()));
        impact.put("关联订单数", sysOrgMapper.countOrderByOrg(org.getId()));
        impact.put("影响", "该机构默认不再出现在列表中；被下级机构/部门/用户/订单引用时会被拒绝，可在「显示已删除」中恢复");
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
${OPERATOR_HELPER}`;

// =====================================================================
// 2. DepartmentService（§6.2 部门：无未删除的用户；另加"无未删除的下级部门"）
// =====================================================================
const DEPT_SERVICE = `
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
${OPERATOR_HELPER}`;

// =====================================================================
// 3. UserService（§6.2 用户：不能删自己 / 最后一个启用 ADMIN；§6.3 令牌撤销）
// =====================================================================
const USER_SERVICE = `
    // ==================================================================
    // 逻辑删除 / 恢复（LD-02 / LD-04 / §6.2 / §6.3）
    // ==================================================================

    /**
     * 用户逻辑删除。
     *
     * <p>删除后**必须撤销该用户全部令牌**（§6.3）：令牌里的权限来自 JWT claims，
     * 撤销是唯一能立即生效的机制；否则旧 JWT 最长还能用 12 小时（风险 LD-R12）。</p>
     */
    @Transactional
    public UserVO delete(Long id, DataScope scope, Long operatorUserId) {
        SysUser existing = requireVisible(id, scope);
        List<String> blockers = deleteBlockers(existing, operatorUserId);
        if (!blockers.isEmpty()) {
            throw new BizException("该用户不能删除：" + String.join("；", blockers));
        }
        UserVO before = getById(id);
        int affected = sysUserMapper.softDelete(id, operatorId(operatorUserId));
        if (affected != 1) {
            throw new BizException("用户已被他人删除，请刷新后重试");
        }
        revokeTokens(List.of(id), "用户被删除: " + existing.getUsername());
        log.info("用户逻辑删除成功 id={} username={} 操作者={}", id, existing.getUsername(), operatorUserId);
        webAuditor.success("DELETE", "USER", id, existing.getUsername(),
                Map.of("isDeleted", 0), Map.of("isDeleted", 1));
        SysUser deleted = sysUserMapper.selectEntityByIdIncludingDeleted(id);
        before.setIsDeleted(deleted.getIsDeleted());
        before.setDeletedAt(deleted.getDeletedAt());
        return before;
    }

    /** 删除阻碍项（§6.2）：不能删除自己、不能删除最后一个启用状态的 ADMIN。 */
    public List<String> deleteBlockers(SysUser existing, Long operatorUserId) {
        List<String> blockers = new ArrayList<>();
        if (existing.getId().equals(operatorUserId)) {
            blockers.add("不允许删除自己的账号");
        }
        if (hasRole(existing.getId(), Roles.ADMIN)
                && Integer.valueOf(1).equals(existing.getStatus())
                && sysUserMapper.countOtherEnabledAdmins(existing.getId()) == 0) {
            blockers.add("该用户是最后一个启用状态的超级管理员");
        }
        return blockers;
    }

    /** 删除影响面（确认卡明示）。 */
    public Map<String, Object> deleteImpact(SysUser user) {
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("持有角色", sysUserMapper.listRoleCodesByUserId(user.getId()));
        impact.put("是否最后一个启用管理员",
                hasRole(user.getId(), Roles.ADMIN)
                        && sysUserMapper.countOtherEnabledAdmins(user.getId()) == 0);
        impact.put("影响", "该用户默认不再出现在列表中且无法登录，其持有的 JWT 会被立即撤销；"
                + "可在「显示已删除」中恢复");
        return impact;
    }

    /**
     * 用户恢复（LD-04a）。
     *
     * <p>所属机构与部门若仍处于已删除状态则拒绝——否则会出现"机构已删、用户被恢复"的悬挂引用。</p>
     */
    @Transactional
    public UserVO restore(Long id, Long operatorUserId) {
        SysUser existing = sysUserMapper.selectEntityByIdIncludingDeleted(id);
        if (existing == null) {
            throw BizException.notFound("用户不存在: " + id);
        }
        if (!Integer.valueOf(1).equals(existing.getIsDeleted())) {
            throw new BizException("用户未被删除，无需恢复");
        }
        List<String> blockers = restoreBlockers(existing);
        if (!blockers.isEmpty()) {
            throw new BizException("该用户不能恢复：" + String.join("；", blockers));
        }
        int affected = sysUserMapper.restore(id);
        if (affected != 1) {
            throw new BizException("用户恢复失败，可能已被他人恢复，请刷新后重试");
        }
        log.info("用户恢复成功 id={} username={} 操作者={}", id, existing.getUsername(), operatorUserId);
        webAuditor.success("RESTORE", "USER", id, existing.getUsername(),
                Map.of("isDeleted", 1), Map.of("isDeleted", 0));
        return getById(id);
    }

    /** 恢复阻碍项（LD-04a）：父记录（机构 / 部门）必须已恢复。 */
    public List<String> restoreBlockers(SysUser user) {
        List<String> blockers = new ArrayList<>();
        SysOrg org = sysOrgMapper.selectEntityByIdIncludingDeleted(user.getOrgId());
        if (org == null) {
            blockers.add("所属机构不存在");
        } else if (Integer.valueOf(1).equals(org.getIsDeleted())) {
            blockers.add("请先恢复其所属机构：" + org.getOrgName());
        }
        if (user.getDeptId() != null) {
            SysDepartment dept = sysDepartmentMapper.selectEntityByIdIncludingDeleted(user.getDeptId());
            if (dept == null) {
                blockers.add("所属部门不存在");
            } else if (Integer.valueOf(1).equals(dept.getIsDeleted())) {
                blockers.add("请先恢复其所属部门：" + dept.getDeptName());
            }
        }
        return blockers;
    }
${OPERATOR_HELPER}`;

// =====================================================================
// 4. RoleService（§6.2 角色：ADMIN 不可删；无未删除用户持有）+ 令牌撤销
// =====================================================================
const ROLE_SERVICE = `
    // ==================================================================
    // 逻辑删除 / 恢复（LD-02 / LD-04 / §6.2 / §6.3）
    // ==================================================================

    /**
     * 角色逻辑删除。
     *
     * <p>删除前要求"没有未删除的用户持有该角色"（§6.2）；删除后撤销所有曾持有该角色
     * 用户的令牌（§6.3），使其权限变更立即生效（鉴权路径同时会过滤已删除角色，
     * 两道保障互为兜底）。</p>
     */
    @Transactional
    public RoleVO delete(Long id, Long operatorUserId) {
        SysRole existing = findEntityById(id);
        List<String> blockers = deleteBlockers(existing);
        if (!blockers.isEmpty()) {
            throw new BizException("该角色不能删除：" + String.join("；", blockers));
        }
        RoleVO before = getById(id);
        List<Long> affectedUsers = sysRoleMapper.selectUserIdsByRoleCode(existing.getRoleCode());
        int affected = sysRoleMapper.softDelete(id, operatorId(operatorUserId));
        if (affected != 1) {
            throw new BizException("角色已被他人删除，请刷新后重试");
        }
        revokeTokens(affectedUsers, "角色被删除: " + existing.getRoleCode());
        log.info("角色逻辑删除成功 id={} code={} 影响用户数={} 操作者={}",
                id, existing.getRoleCode(), affectedUsers.size(), operatorUserId);
        webAuditor.success("DELETE", "ROLE", id, existing.getRoleName(),
                Map.of("isDeleted", 0), Map.of("isDeleted", 1));
        SysRole deleted = sysRoleMapper.selectEntityByIdIncludingDeleted(id);
        before.setIsDeleted(deleted.getIsDeleted());
        before.setDeletedAt(deleted.getDeletedAt());
        return before;
    }

    /** 删除阻碍项（§6.2）：ADMIN 不可删除；不得有未删除的用户持有该角色。 */
    public List<String> deleteBlockers(SysRole role) {
        List<String> blockers = new ArrayList<>();
        if (Roles.ADMIN.equals(role.getRoleCode())) {
            blockers.add("超级管理员（ADMIN）角色不允许删除");
        }
        long users = sysRoleMapper.countUsersByRoleIds(List.of(role.getId())).stream()
                .mapToLong(RoleCountRef::getCnt).sum();
        if (users > 0) {
            blockers.add("仍有 " + users + " 个未删除的用户持有该角色，请先解除绑定");
        }
        return blockers;
    }

    /** 删除影响面（确认卡明示）。 */
    public Map<String, Object> deleteImpact(SysRole role) {
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("角色编码", role.getRoleCode());
        impact.put("持有用户数", sysRoleMapper.countUsersByRoleIds(List.of(role.getId())).stream()
                .mapToLong(RoleCountRef::getCnt).sum());
        impact.put("影响", "该角色默认不再出现在列表中，其权限不再签发（持有者令牌被撤销、需重新登录）；"
                + "可在「显示已删除」中恢复");
        return impact;
    }

    /** 角色恢复（LD-04a）：角色无父记录，仅需确认编码未被有效角色占用。 */
    @Transactional
    public RoleVO restore(Long id, Long operatorUserId) {
        SysRole existing = sysRoleMapper.selectEntityByIdIncludingDeleted(id);
        if (existing == null) {
            throw BizException.notFound("角色不存在: " + id);
        }
        if (!Integer.valueOf(1).equals(existing.getIsDeleted())) {
            throw new BizException("角色未被删除，无需恢复");
        }
        SysRole duplicate = sysRoleMapper.selectEntityByCode(existing.getRoleCode());
        if (duplicate != null && !duplicate.getId().equals(id)) {
            throw new BizException("角色编码已被同名的有效角色占用，无法恢复：" + existing.getRoleCode());
        }
        int affected = sysRoleMapper.restore(id);
        if (affected != 1) {
            throw new BizException("角色恢复失败，可能已被他人恢复，请刷新后重试");
        }
        log.info("角色恢复成功 id={} code={} 操作者={}", id, existing.getRoleCode(), operatorUserId);
        webAuditor.success("RESTORE", "ROLE", id, existing.getRoleName(),
                Map.of("isDeleted", 1), Map.of("isDeleted", 0));
        return getById(id);
    }
${OPERATOR_HELPER}`;

// =====================================================================
// 5. InsuranceTypeService（§6.2 险种：被订单引用即拒绝删除）
// =====================================================================
const INSURANCE_SERVICE = `
    // ==================================================================
    // 逻辑删除 / 恢复（LD-02 / LD-04 / §6.2）
    // ==================================================================

    /**
     * 险种逻辑删除。
     *
     * <p>与停用的差异（§6.2）：停用只提示被引用条数、不禁；**删除被引用即拒绝**——
     * 删除后历史订单会指向一条"不存在"的险种。</p>
     */
    @Transactional
    public InsuranceTypeVO delete(Long id, Long operatorUserId) {
        InsuranceType existing = insuranceTypeMapper.selectById(id);
        if (existing == null) {
            throw BizException.notFound("险种不存在: " + id);
        }
        List<String> blockers = deleteBlockers(existing);
        if (!blockers.isEmpty()) {
            throw new BizException("该险种不能删除：" + String.join("；", blockers)
                    + "。如只需暂停业务，请改用「停用」。");
        }
        InsuranceTypeVO before = toVO(existing);
        int affected = insuranceTypeMapper.softDelete(id, operatorId(operatorUserId));
        if (affected != 1) {
            throw new BizException("险种已被他人删除，请刷新后重试");
        }
        log.info("险种逻辑删除成功 id={} code={} 操作者={}", id, existing.getTypeCode(), operatorUserId);
        webAuditor.success("DELETE", "INSURANCE_TYPE", id, existing.getTypeName(),
                Map.of("isDeleted", 0), Map.of("isDeleted", 1));
        InsuranceType deleted = insuranceTypeMapper.selectByIdIncludingDeleted(id);
        return new InsuranceTypeVO(before.id(), before.typeCode(), before.typeName(), before.category(),
                before.categoryName(), before.baseRate(), before.baseRatePercent(), before.minAmount(),
                before.maxAmount(), before.status(), before.description(), before.createdAt(),
                before.updatedAt(), deleted.getIsDeleted(), deleted.getDeletedAt());
    }

    /** 删除阻碍项（§6.2）：被订单引用即拒绝。 */
    public List<String> deleteBlockers(InsuranceType type) {
        long orders = insuranceTypeMapper.countOrderByType(type.getId());
        if (orders > 0) {
            return List.of("已被 " + orders + " 条订单引用");
        }
        return List.of();
    }

    /** 删除影响面（确认卡明示）。 */
    public Map<String, Object> deleteImpact(InsuranceType type) {
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("引用订单数", insuranceTypeMapper.countOrderByType(type.getId()));
        impact.put("影响", "该险种默认不再出现在列表中；被订单引用时会被拒绝，可在「显示已删除」中恢复");
        return impact;
    }

    /** 险种恢复（LD-04a）：无父记录，仅需确认编码未被有效险种占用。 */
    @Transactional
    public InsuranceTypeVO restore(Long id, Long operatorUserId) {
        InsuranceType existing = insuranceTypeMapper.selectByIdIncludingDeleted(id);
        if (existing == null) {
            throw BizException.notFound("险种不存在: " + id);
        }
        if (!Integer.valueOf(1).equals(existing.getIsDeleted())) {
            throw new BizException("险种未被删除，无需恢复");
        }
        InsuranceType duplicate = insuranceTypeMapper.selectByCode(existing.getTypeCode());
        if (duplicate != null && !duplicate.getId().equals(id)) {
            throw new BizException("险种编码已被同名的有效险种占用，无法恢复：" + existing.getTypeCode());
        }
        int affected = insuranceTypeMapper.restore(id);
        if (affected != 1) {
            throw new BizException("险种恢复失败，可能已被他人恢复，请刷新后重试");
        }
        log.info("险种恢复成功 id={} code={} 操作者={}", id, existing.getTypeCode(), operatorUserId);
        webAuditor.success("RESTORE", "INSURANCE_TYPE", id, existing.getTypeName(),
                Map.of("isDeleted", 1), Map.of("isDeleted", 0));
        return toVO(insuranceTypeMapper.selectById(id));
    }
${OPERATOR_HELPER}`;

// =====================================================================
// 应用：服务方法追加 + 依赖注入调整
// =====================================================================
function appendBeforeLastBrace(rel, block) {
  const path = join(root, rel);
  let src = readFileSync(path, 'utf8');
  if (src.includes('public ' + block.match(/public \w+ (\w+)\(/)[1])) {
    // 粗略幂等：已含同名方法
  }
  const idx = src.lastIndexOf('\n}');
  if (idx < 0) {
    throw new Error('找不到类结尾: ' + rel);
  }
  if (src.includes('operatorId(Long operatorUserId)')) {
    console.log(`skip service: ${rel}`);
    return;
  }
  src = src.slice(0, idx) + block + src.slice(idx);
  writeFileSync(path, src, 'utf8');
  console.log(`service updated: ${rel}`);
}

appendBeforeLastBrace(SVC + 'OrgService.java', ORG_SERVICE);
appendBeforeLastBrace(SVC + 'DepartmentService.java', DEPT_SERVICE);
appendBeforeLastBrace(SVC + 'UserService.java', USER_SERVICE);
appendBeforeLastBrace(SVC + 'RoleService.java', ROLE_SERVICE);
appendBeforeLastBrace(SVC + 'InsuranceTypeService.java', INSURANCE_SERVICE);

// 构造函数与字段：UserService 需要 SysOrgMapper / SysDepartmentMapper；
// DepartmentService 需要 SysOrgMapper（恢复时校验所属机构）
edit(SVC + 'UserService.java',
  `import com.guarantee.system.mapper.SysRoleMapper;
import com.guarantee.system.mapper.SysUserMapper;`,
  `import com.guarantee.system.entity.SysDepartment;
import com.guarantee.system.entity.SysOrg;
import com.guarantee.system.mapper.SysDepartmentMapper;
import com.guarantee.system.mapper.SysOrgMapper;
import com.guarantee.system.mapper.SysRoleMapper;
import com.guarantee.system.mapper.SysUserMapper;`,
  'import com.guarantee.system.mapper.SysOrgMapper;');

edit(SVC + 'UserService.java',
  `    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final DataScopeService dataScopeService;
    private final ObjectProvider<UserTokenRevoker> tokenRevokerProvider;
    private final WebAuditor webAuditor;

    public UserService(SysUserMapper sysUserMapper,
                       SysRoleMapper sysRoleMapper,
                       DataScopeService dataScopeService,
                       ObjectProvider<UserTokenRevoker> tokenRevokerProvider,
                       WebAuditor webAuditor) {
        this.sysUserMapper = sysUserMapper;
        this.sysRoleMapper = sysRoleMapper;
        this.dataScopeService = dataScopeService;
        this.tokenRevokerProvider = tokenRevokerProvider;
        this.webAuditor = webAuditor;
    }`,
  `    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    /** 恢复用户时校验所属机构未被删除（LD-04a）。 */
    private final SysOrgMapper sysOrgMapper;
    /** 恢复用户时校验所属部门未被删除（LD-04a）。 */
    private final SysDepartmentMapper sysDepartmentMapper;
    private final DataScopeService dataScopeService;
    private final ObjectProvider<UserTokenRevoker> tokenRevokerProvider;
    private final WebAuditor webAuditor;

    public UserService(SysUserMapper sysUserMapper,
                       SysRoleMapper sysRoleMapper,
                       SysOrgMapper sysOrgMapper,
                       SysDepartmentMapper sysDepartmentMapper,
                       DataScopeService dataScopeService,
                       ObjectProvider<UserTokenRevoker> tokenRevokerProvider,
                       WebAuditor webAuditor) {
        this.sysUserMapper = sysUserMapper;
        this.sysRoleMapper = sysRoleMapper;
        this.sysOrgMapper = sysOrgMapper;
        this.sysDepartmentMapper = sysDepartmentMapper;
        this.dataScopeService = dataScopeService;
        this.tokenRevokerProvider = tokenRevokerProvider;
        this.webAuditor = webAuditor;
    }`,
  'private final SysOrgMapper sysOrgMapper;');

edit(SVC + 'DepartmentService.java',
  `import com.guarantee.system.entity.SysDepartment;
import com.guarantee.system.mapper.SysDepartmentMapper;`,
  `import com.guarantee.system.entity.SysDepartment;
import com.guarantee.system.entity.SysOrg;
import com.guarantee.system.mapper.SysDepartmentMapper;
import com.guarantee.system.mapper.SysOrgMapper;`,
  'import com.guarantee.system.mapper.SysOrgMapper;');

edit(SVC + 'DepartmentService.java',
  `import java.util.LinkedHashMap;`,
  `import java.util.ArrayList;
import java.util.LinkedHashMap;`,
  'import java.util.ArrayList;');

edit(SVC + 'DepartmentService.java',
  `    private final SysDepartmentMapper sysDepartmentMapper;
    private final DataScopeService dataScopeService;
    private final WebAuditor webAuditor;

    public DepartmentService(SysDepartmentMapper sysDepartmentMapper, DataScopeService dataScopeService,
                             WebAuditor webAuditor) {
        this.sysDepartmentMapper = sysDepartmentMapper;
        this.dataScopeService = dataScopeService;
        this.webAuditor = webAuditor;
    }`,
  `    private final SysDepartmentMapper sysDepartmentMapper;
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
    }`,
  'private final SysOrgMapper sysOrgMapper;');

// 部门下级计数（实施期补充）
edit('guarantee-system/src/main/resources/mapper/system/SysDepartmentMapper.xml',
  `    <select id="countUserByDept" resultType="long">`,
  `    <!-- 删除前置检查（实施期补充）：未删除的下级部门数 -->
    <select id="countChildDept" resultType="long">
        SELECT COUNT(*) FROM sys_department WHERE parent_id = #{deptId} AND is_deleted = 0
    </select>

    <select id="countUserByDept" resultType="long">`,
  'id="countChildDept"');

edit('guarantee-system/src/main/java/com/guarantee/system/mapper/SysDepartmentMapper.java',
  `    /** 删除前置检查：部门下未删除的用户数（含停用用户）。 */
    long countUserByDept(@Param("deptId") Long deptId);`,
  `    /** 删除前置检查：部门下未删除的用户数（含停用用户）。 */
    long countUserByDept(@Param("deptId") Long deptId);

    /** 删除前置检查（实施期补充）：未删除的下级部门数。 */
    long countChildDept(@Param("deptId") Long deptId);`,
  'countChildDept(@Param');

// =====================================================================
// 6. AuthService：LD-05（已删除用户登录返回与密码错误完全相同的提示）
// =====================================================================
edit('guarantee-auth/src/main/java/com/guarantee/auth/service/AuthService.java',
  `        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(ResultCode.ACCOUNT_DISABLED);
        }`,
  `        // LD-05：已删除用户不可登录，且必须返回**与密码错误完全相同**的提示，避免账号枚举。
        // 注意顺序：删除校验放在停用校验之前——"已删除且已停用"的账号也必须只说"用户名或密码错误"。
        // 另外 selectByUsername 已加 is_deleted = 0（LD-05b），这里是第二道保险。
        if (user.getIsDeleted() != null && user.getIsDeleted() == 1) {
            log.warn("登录失败（账号已逻辑删除） username={}", request.getUsername());
            throw new BizException(ResultCode.LOGIN_FAILED);
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(ResultCode.ACCOUNT_DISABLED);
        }`,
  '账号已逻辑删除');

// =====================================================================
// 7. 权限码常量 + PermissionCatalog
// =====================================================================
edit('guarantee-common/src/main/java/com/guarantee/common/security/Permissions.java',
  `    public static final String INSURANCE_DISABLE = "system:insurance:disable";`,
  `    public static final String INSURANCE_DISABLE = "system:insurance:disable";
    /** 险种逻辑删除 / 恢复（设计 §7.2）。 */
    public static final String INSURANCE_DELETE = "system:insurance:delete";`,
  'INSURANCE_DELETE');

edit('guarantee-common/src/main/java/com/guarantee/common/security/Permissions.java',
  `    public static final String ORG_DISABLE = "system:org:disable";`,
  `    public static final String ORG_DISABLE = "system:org:disable";
    /** 机构逻辑删除 / 恢复 / 查看已删除（设计 §7.2）。 */
    public static final String ORG_DELETE = "system:org:delete";`,
  'ORG_DELETE');

edit('guarantee-common/src/main/java/com/guarantee/common/security/Permissions.java',
  `    public static final String DEPT_DISABLE = "system:dept:disable";`,
  `    public static final String DEPT_DISABLE = "system:dept:disable";
    /** 部门逻辑删除 / 恢复 / 查看已删除。 */
    public static final String DEPT_DELETE = "system:dept:delete";`,
  'DEPT_DELETE');

edit('guarantee-common/src/main/java/com/guarantee/common/security/Permissions.java',
  `    public static final String USER_ASSIGN_ROLE = "system:user:assign-role";`,
  `    public static final String USER_ASSIGN_ROLE = "system:user:assign-role";
    /** 用户逻辑删除 / 恢复 / 查看已删除（仅 ADMIN，设计 §7.2）。 */
    public static final String USER_DELETE = "system:user:delete";`,
  'USER_DELETE');

edit('guarantee-common/src/main/java/com/guarantee/common/security/Permissions.java',
  `    public static final String ROLE_ASSIGN_PERMISSION = "system:role:assign-permission";`,
  `    public static final String ROLE_ASSIGN_PERMISSION = "system:role:assign-permission";
    /** 角色逻辑删除 / 恢复 / 查看已删除（仅 ADMIN，设计 §7.2）。 */
    public static final String ROLE_DELETE = "system:role:delete";`,
  'ROLE_DELETE');

edit('guarantee-web/src/main/java/com/guarantee/web/init/PermissionCatalog.java',
  `            {"system:insurance:disable", "险种启停", null},`,
  `            {"system:insurance:disable", "险种启停", null},
            {"system:insurance:delete", "险种删除", null},`,
  'system:insurance:delete');

edit('guarantee-web/src/main/java/com/guarantee/web/init/PermissionCatalog.java',
  `            {"system:org:disable", "机构启停", null},`,
  `            {"system:org:disable", "机构启停", null},
            {"system:org:delete", "机构删除", null},`,
  'system:org:delete');

edit('guarantee-web/src/main/java/com/guarantee/web/init/PermissionCatalog.java',
  `            {"system:dept:disable", "部门启停", null},`,
  `            {"system:dept:disable", "部门启停", null},
            {"system:dept:delete", "部门删除", null},`,
  'system:dept:delete');

edit('guarantee-web/src/main/java/com/guarantee/web/init/PermissionCatalog.java',
  `            {"system:user:assign-role", "用户角色分配", null},`,
  `            {"system:user:assign-role", "用户角色分配", null},
            {"system:user:delete", "用户删除", null},`,
  'system:user:delete');

edit('guarantee-web/src/main/java/com/guarantee/web/init/PermissionCatalog.java',
  `            {"system:role:assign-permission", "角色授权", null},`,
  `            {"system:role:assign-permission", "角色授权", null},
            {"system:role:delete", "角色删除", null},`,
  'system:role:delete');

// 矩阵：机构/部门/险种 OPERATOR 可删；用户/角色仅 ADMIN（设计 §7.2）
edit('guarantee-web/src/main/java/com/guarantee/web/init/PermissionCatalog.java',
  `            "system:insurance:update", "system:insurance:disable",
            "system:org:view", "system:org:create", "system:org:update", "system:org:disable",
            "system:dept:view", "system:dept:create", "system:dept:update", "system:dept:disable",`,
  `            "system:insurance:update", "system:insurance:disable", "system:insurance:delete",
            "system:org:view", "system:org:create", "system:org:update", "system:org:disable",
            "system:org:delete",
            "system:dept:view", "system:dept:create", "system:dept:update", "system:dept:disable",
            "system:dept:delete",`,
  'system:org:delete",');

edit('guarantee-web/src/main/java/com/guarantee/web/init/PermissionCatalog.java',
  `    private static boolean isWritePermission(String code) {
        return code.endsWith(":create") || code.endsWith(":update") || code.endsWith(":disable")`,
  `    private static boolean isWritePermission(String code) {
        return code.endsWith(":create") || code.endsWith(":update") || code.endsWith(":disable")
                || code.endsWith(":delete")`,
  'code.endsWith(":delete")');

// =====================================================================
// 8. Controller：DELETE + /restore + includeDeleted 权限校验
// =====================================================================
const controllers = [
  {
    file: CTL + 'OrgController.java',
    domain: 'orgs',
    perm: 'ORG_DELETE',
    vo: 'OrgVO',
    entity: 'OrgDto',
    service: 'orgService',
    label: '机构',
    scope: true,
    listAnchor: '        return Result.ok(orgService.page(query, currentScope()));',
  },
  {
    file: CTL + 'DepartmentController.java',
    domain: 'departments',
    perm: 'DEPT_DELETE',
    vo: 'DepartmentVO',
    entity: 'DepartmentDto',
    service: 'departmentService',
    label: '部门',
    scope: true,
    listAnchor: '        return Result.ok(departmentService.page(query, currentScope()));',
  },
  {
    file: CTL + 'UserController.java',
    domain: 'users',
    perm: 'USER_DELETE',
    vo: 'UserVO',
    entity: 'UserDto',
    service: 'userService',
    label: '用户',
    scope: true,
    listAnchor: '        return Result.ok(userService.page(query, currentScope()));',
  },
  {
    file: CTL + 'RoleController.java',
    domain: 'roles',
    perm: 'ROLE_DELETE',
    vo: 'RoleVO',
    entity: 'RoleDto',
    service: 'roleService',
    label: '角色',
    scope: false,
    listAnchor: '        return Result.ok(roleService.page(query, currentScope()));',
  },
  {
    file: CTL + 'InsuranceTypeController.java',
    domain: 'insurance-types',
    perm: 'INSURANCE_DELETE',
    vo: 'InsuranceTypeVO',
    entity: 'InsuranceTypeDto',
    service: 'insuranceTypeService',
    label: '险种',
    scope: false,
    listAnchor: '        return Result.ok(insuranceTypeService.page(query));',
  },
];

for (const c of controllers) {
  const path = join(root, c.file);
  let src = readFileSync(path, 'utf8');
  if (src.includes('DeleteMapping')) {
    console.log(`skip controller: ${c.file}`);
    continue;
  }
  if (!src.includes('import org.springframework.web.bind.annotation.GetMapping;')) {
    throw new Error('导入锚点未命中: ' + c.file);
  }
  src = src.replace('import org.springframework.web.bind.annotation.GetMapping;',
    'import org.springframework.web.bind.annotation.DeleteMapping;\n'
    + 'import org.springframework.web.bind.annotation.GetMapping;');
  if (!src.includes('import org.springframework.web.bind.annotation.PostMapping;')) {
    src = src.replace('import org.springframework.web.bind.annotation.PathVariable;',
      'import org.springframework.web.bind.annotation.PathVariable;\n'
      + 'import org.springframework.web.bind.annotation.PostMapping;');
  }
  if (!src.includes('import com.guarantee.common.security.CurrentUser;')) {
    src = src.replace('import com.guarantee.common.security.Permissions;',
      'import com.guarantee.common.security.CurrentUser;\n'
      + 'import com.guarantee.common.security.Permissions;');
  }
  if (!src.includes('import com.guarantee.common.api.ResultCode;')) {
    src = src.replace('import com.guarantee.common.api.Result;',
      'import com.guarantee.common.api.Result;\nimport com.guarantee.common.api.ResultCode;');
  }
  if (!src.includes('import com.guarantee.common.exception.BizException;')) {
    src = src.replace('import com.guarantee.common.api.ResultCode;',
      'import com.guarantee.common.api.ResultCode;\n'
      + 'import com.guarantee.common.exception.BizException;');
  }

  // 列表方法：includeDeleted 的服务端权限校验
  if (!src.includes(c.listAnchor)) {
    throw new Error('list 锚点未命中: ' + c.file + '\n---\n' + c.listAnchor);
  }
  src = src.replace(c.listAnchor,
    `        requireDeletePermissionWhenIncludingDeleted(query.getIncludeDeleted());\n`
    + c.listAnchor);

  // 删除 / 恢复端点
  const scopeArgs = c.scope ? 'currentScope(), ' : '';
  const userIdHelper = src.match(/private (static )?Long currentUserId\(\)/)
    ? '' : `
    private static Long currentUserId() {
        return CurrentUser.userId();
    }
`;
  const endpoints = `
    /**
     * 逻辑删除（设计 §7.1）。删除不是物理删除：记录仍在库中，可在「显示已删除」中恢复。
     *
     * <p>恢复与删除共用 {@code ${c.perm}} 权限：能删就能恢复，避免"删了不能恢复"的单向能力（LD-04）。</p>
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.${c.perm} + "')")
    public Result<${c.vo}> delete(@PathVariable Long id) {
        return Result.ok(${c.service}.delete(id, ${scopeArgs}currentUserId()));
    }

    /** 恢复（POST /{id}/restore）：语义是"执行一个逆向动作"，非幂等 PATCH（设计 §7.1）。 */
    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('" + Permissions.${c.perm} + "')")
    public Result<${c.vo}> restore(@PathVariable Long id) {
        return Result.ok(${c.service}.restore(id, currentUserId()));
    }

    /**
     * 「显示已删除」（includeDeleted=true）需要 ${c.perm} 权限（设计 §7.1）。
     *
     * <p>参数级权限无法用 {@code @PreAuthorize} 表达，因此在方法体内显式判定：
     * **服务端强制**，前端隐藏开关只是体验优化（SYS-NF-04）。</p>
     */
    private static void requireDeletePermissionWhenIncludingDeleted(Boolean includeDeleted) {
        if (Boolean.TRUE.equals(includeDeleted) && !CurrentUser.hasPermission(Permissions.${c.perm})) {
            throw new BizException(ResultCode.FORBIDDEN,
                    "${c.label}的「显示已删除」需要权限：" + Permissions.${c.perm});
        }
    }
${userIdHelper}`;
  const lastBrace = src.lastIndexOf('\n}');
  src = src.slice(0, lastBrace) + endpoints + src.slice(lastBrace);
  writeFileSync(path, src, 'utf8');
  console.log(`controller updated: ${c.file}`);
}

console.log('batch4b done');
