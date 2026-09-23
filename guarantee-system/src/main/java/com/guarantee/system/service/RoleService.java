package com.guarantee.system.service;

import com.guarantee.common.api.PageResult;
import com.guarantee.common.exception.BizException;
import com.guarantee.common.security.Roles;
import com.guarantee.common.security.UserTokenRevoker;
import com.guarantee.system.dto.RoleCountRef;
import com.guarantee.system.dto.RoleDto;
import com.guarantee.system.dto.RolePermissionRef;
import com.guarantee.system.entity.SysPermission;
import com.guarantee.system.entity.SysRole;
import com.guarantee.system.mapper.SysRoleMapper;
import com.guarantee.system.mapper.SysUserMapper;
import com.guarantee.system.scope.DataScope;
import com.guarantee.system.scope.QueryScope;
import com.guarantee.system.vo.RoleVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 角色配置服务。权限列表由 Service 批量回填，避免 N+1。
 *
 * <p>写操作规则（SYS-W-05）：{@code roleCode} 不可改；{@code ADMIN} 角色不可改、
 * 不可新建；授权只能在 {@code sys_permission} 中既有权限码中选择；
 * 授权变更后必须撤销持有该角色用户的令牌。</p>
 *
 * <p>注意：{@code CANDIDATE_LIMIT} 之外没有任何"新建权限码"的入口——权限主数据不允许改（R-04）。</p>
 */
@Service
public class RoleService {

    private static final Logger log = LoggerFactory.getLogger(RoleService.class);

    /** 写操作目标解析的候选上限。 */
    public static final int CANDIDATE_LIMIT = 20;

    private final SysRoleMapper sysRoleMapper;
    /** 仅用于"持该角色的启用用户数"（停用影响面）；角色自身的数据都走 sysRoleMapper。 */
    private final SysUserMapper sysUserMapper;
    private final ObjectProvider<UserTokenRevoker> tokenRevokerProvider;
    private final WebAuditor webAuditor;

    public RoleService(SysRoleMapper sysRoleMapper,
                       SysUserMapper sysUserMapper,
                       ObjectProvider<UserTokenRevoker> tokenRevokerProvider,
                       WebAuditor webAuditor) {
        this.sysRoleMapper = sysRoleMapper;
        this.sysUserMapper = sysUserMapper;
        this.tokenRevokerProvider = tokenRevokerProvider;
        this.webAuditor = webAuditor;
    }

    // ==================================================================
    // 读
    // ==================================================================

    @Transactional(readOnly = true)
    public PageResult<RoleVO> page(RoleDto.Query query, DataScope scope) {
        query.setScope(QueryScope.of(scope));
        long total = sysRoleMapper.countByQuery(query);
        if (total == 0) {
            return PageResult.empty(query.getPageNum(), query.getPageSize());
        }
        List<RoleVO> list = sysRoleMapper.selectPage(query, query.offset(), query.getPageSize());
        fillPermissions(list);
        fillCounts(list);
        return PageResult.of(query.getPageNum(), query.getPageSize(), total, list);
    }

    @Transactional(readOnly = true)
    public RoleVO getById(Long id) {
        RoleVO vo = sysRoleMapper.selectVoById(id);
        if (vo == null) {
            throw BizException.notFound("角色不存在: " + id);
        }
        fillPermissions(List.of(vo));
        fillCounts(List.of(vo));
        return vo;
    }

    /** 权限主数据（只读）：全部权限码，供"只能在既有权限码中选择"的界面与工具使用。 */
    @Transactional(readOnly = true)
    public List<SysPermission> listPermissionEntities() {
        return sysRoleMapper.selectAllPermissionsOrdered();
    }

    /** 按权限 id 取权限明细（queryRole 的 ROLE_PERMISSION 模式）。 */
    @Transactional(readOnly = true)
    public List<SysPermission> listPermissionsByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return sysRoleMapper.selectPermissionsByIds(ids);
    }

    /** 按编码读角色实体（工具与写操作使用）。 */
    @Transactional(readOnly = true)
    public SysRole findEntityByCode(String roleCode) {
        return sysRoleMapper.selectEntityByCode(roleCode);
    }

    /** 按主键读角色实体；不存在时抛 404。 */
    @Transactional(readOnly = true)
    public SysRole findEntityById(Long id) {
        SysRole role = sysRoleMapper.selectEntityById(id);
        if (role == null) {
            throw BizException.notFound("角色不存在: " + id);
        }
        return role;
    }

    /** 写操作目标解析（SYS-W-10）。 */
    @Transactional(readOnly = true)
    public List<SysRole> findCandidates(String keyword, int limit) {
        return sysRoleMapper.selectCandidates(keyword, limit);
    }

    /** 批量回填角色已授权权限。 */
    private void fillPermissions(List<RoleVO> roles) {
        if (roles.isEmpty()) {
            return;
        }
        List<Long> roleIds = roles.stream().map(RoleVO::getId).toList();
        Map<Long, List<RolePermissionRef>> grouped =
                sysRoleMapper.selectPermissionRefsByRoleIds(roleIds).stream()
                        .collect(Collectors.groupingBy(RolePermissionRef::getRoleId));
        for (RoleVO role : roles) {
            List<RolePermissionRef> refs = grouped.getOrDefault(role.getId(), List.of());
            role.setPermissionIds(refs.stream().map(RolePermissionRef::getPermissionId).toList());
            role.setPermissionNames(refs.stream().map(RolePermissionRef::getPermissionName).toList());
            role.setPermissionCodes(refs.stream().map(RolePermissionRef::getPermissionCode).toList());
            role.setPermissionCount(refs.size());
        }
    }

    /** 批量回填权限数与用户数（SYS-Q-04 ROLE 模式出参）。 */
    private void fillCounts(List<RoleVO> roles) {
        if (roles.isEmpty()) {
            return;
        }
        List<Long> roleIds = roles.stream().map(RoleVO::getId).toList();
        Map<Long, Long> permissionCounts = sysRoleMapper.countPermissionsByRoleIds(roleIds).stream()
                .collect(Collectors.toMap(RoleCountRef::getRoleId, RoleCountRef::getCnt, (a, b) -> a));
        Map<Long, Long> userCounts = sysRoleMapper.countUsersByRoleIds(roleIds).stream()
                .collect(Collectors.toMap(RoleCountRef::getRoleId, RoleCountRef::getCnt, (a, b) -> a));
        for (RoleVO role : roles) {
            role.setPermissionCount(permissionCounts.getOrDefault(role.getId(), 0L).intValue());
            role.setUserCount(userCounts.getOrDefault(role.getId(), 0L).intValue());
        }
    }

    // ==================================================================
    // 写（SYS-W-05）
    // ==================================================================

    @Transactional
    public RoleVO create(RoleDto.CreateRequest request) {
        validateCreate(request);
        SysRole entity = new SysRole();
        entity.setRoleCode(request.getRoleCode().trim());
        entity.setRoleName(request.getRoleName().trim());
        entity.setDescription(request.getDescription());
        entity.setStatus(1);
        sysRoleMapper.insert(entity);
        log.info("新增角色成功 id={} code={}", entity.getId(), entity.getRoleCode());

        // 页面直连审计（SYS-A-07 / AC-22）
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("roleCode", entity.getRoleCode());
        after.put("roleName", entity.getRoleName());
        after.put("description", entity.getDescription());
        after.put("status", entity.getStatus());
        webAuditor.success("CREATE", "ROLE", entity.getId(), entity.getRoleName(), null, after);

        return getById(entity.getId());
    }

    @Transactional
    public RoleVO update(Long id, RoleDto.UpdateRequest request) {
        SysRole existing = validateUpdate(id, request);
        SysRole entity = new SysRole();
        entity.setId(id);
        entity.setRoleName(request.getRoleName());
        entity.setDescription(request.getDescription());
        entity.setStatus(request.getStatus());
        sysRoleMapper.updateById(entity);
        log.info("修改角色成功 id={} code={}", id, existing.getRoleCode());

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("roleName", existing.getRoleName());
        before.put("description", existing.getDescription());
        before.put("status", existing.getStatus());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("roleName", request.getRoleName() == null ? existing.getRoleName() : request.getRoleName());
        after.put("description",
                request.getDescription() == null ? existing.getDescription() : request.getDescription());
        after.put("status", request.getStatus() == null ? existing.getStatus() : request.getStatus());
        webAuditor.success("UPDATE", "ROLE", id, existing.getRoleName(), before, after);

        return getById(id);
    }

    /**
     * 角色启停（ENABLE / DISABLE）—— 与机构/部门/险种/用户保持一致的独立启停端点。
     *
     * <p><b>停用前置检查（用户规则）：该角色不能有关联的正常用户</b>。角色停用会立即收回
     * 它带来的权限（鉴权路径 {@code SysUserMapper.listPermissionCodesByUserId} 按
     * {@code r.status = 1} 过滤），所以只要还有启用用户持有，就必须**拒绝**而不是
     * "停用 + 提示"——否则等于把这些人在无感知的情况下静默降权。</p>
     *
     * <p><b>守卫：ADMIN 角色不允许停用</b>。ADMIN 是管理员权限的最后来源，
     * 停用会让全体管理员瞬间失权、系统锁死——与 {@link #deleteBlockers} 里
     * "ADMIN 不可删除"、以及用户侧"禁止停用最后一个启用 ADMIN"是同一道保护。</p>
     *
     * <p>与"删除角色"的区别：删除要求**没有任何未删除用户**持有（更严），
     * 停用只要求没有任何**启用**用户持有（已停用/已删除的用户不阻塞）。</p>
     */
    @Transactional
    public RoleVO changeStatus(Long id, Integer targetStatus) {
        SysRole existing = findEntityById(id);
        if (existing.getStatus() != null && existing.getStatus().equals(targetStatus)) {
            throw new BizException("角色已处于目标状态，无需变更：" + existing.getRoleName());
        }
        if (targetStatus != null && targetStatus == 0) {
            if (Roles.ADMIN.equals(existing.getRoleCode())) {
                throw new BizException("超级管理员（ADMIN）角色不允许停用：停用会使全体管理员立即失去权限");
            }
            long users = sysUserMapper.countEnabledUsersByRoleCode(existing.getRoleCode());
            if (users > 0) {
                throw new BizException("仍有 " + users + " 个启用中的用户持有该角色，不能停用；请先解除绑定");
            }
        }
        // 兜底撤销：上面的检查只保证没有"启用"用户持有，仍可能有已停用用户持有。
        // 权限来自 JWT claims，不撤令牌的话其旧 JWT 里的该角色权限最长还能用 12 小时。
        List<Long> holders = (targetStatus != null && targetStatus == 0)
                ? sysRoleMapper.selectUserIdsByRoleCode(existing.getRoleCode())
                : List.of();
        int affected = sysRoleMapper.updateStatus(id, targetStatus, existing.getStatus());
        if (affected != 1) {
            throw new BizException("角色状态已被他人修改，请刷新后重试");
        }
        if (!holders.isEmpty()) {
            revokeTokens(holders, "角色被停用: " + existing.getRoleCode());
        }
        log.info("角色启停成功 id={} code={} status={} 兜底撤销持有者数={}",
                id, existing.getRoleCode(), targetStatus, holders.size());
        webAuditor.success(targetStatus != null && targetStatus == 1 ? "ENABLE" : "DISABLE", "ROLE", id,
                existing.getRoleName(), Map.of("status", existing.getStatus()),
                Map.of("status", targetStatus));
        return getById(id);
    }

    /**
     * 停用影响面（确认卡明示）：**持该角色的启用用户数**。
     *
     * <p>与停用前置检查同口径——该值大于 0 时停用会被拒绝，因此必须让操作者先看到它。
     * 已停用用户不计入（他们本就无法登录，不阻塞停用）。</p>
     */
    @Transactional(readOnly = true)
    public Map<String, Object> stopImpact(SysRole role) {
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("持该角色的启用用户数", sysUserMapper.countEnabledUsersByRoleCode(role.getRoleCode()));
        return impact;
    }

    /**
     * 角色授权（ASSIGN_PERMISSIONS）。
     *
     * <p>只接受 {@code sys_permission} 中已存在的权限码；变更后撤销所有持有该角色用户的令牌。</p>
     */
    @Transactional
    public RoleVO assignPermissions(String roleCode, List<String> permCodes) {
        SysRole existing = validateAssignPermissions(roleCode, permCodes);
        List<String> normalized = normalize(permCodes);
        // 变更前的权限必须在写入之前取，否则审计的 before 会等于 after
        RoleVO current = getById(existing.getId());
        List<String> beforePermCodes = current.getPermissionCodes() == null
                ? List.of() : current.getPermissionCodes();

        List<Long> permissionIds = resolvePermissionIds(normalized);
        // 关联表改造（设计 §4）：先清后插会撞 (role_id, permission_id) 唯一键，改为 UPSERT。
        sysRoleMapper.softDeleteRolePermissionsNotIn(existing.getId(), permissionIds, null);
        if (!permissionIds.isEmpty()) {
            sysRoleMapper.upsertRolePermissions(existing.getId(), permissionIds);
        }
        List<Long> affectedUsers = sysRoleMapper.selectUserIdsByRoleCode(existing.getRoleCode());
        revokeTokens(affectedUsers, "角色权限变更: " + existing.getRoleCode());
        log.info("角色授权成功 code={} 权限数={} 影响用户数={}",
                roleCode, permissionIds.size(), affectedUsers.size());

        // 页面直连审计（SYS-A-07 / AC-22）
        webAuditor.success("ASSIGN_PERMISSIONS", "ROLE", existing.getId(), existing.getRoleName(),
                Map.of("permissionCodes", beforePermCodes),
                Map.of("permissionCodes", normalized));

        return getById(existing.getId());
    }

    // ==================================================================
    // 预检
    // ==================================================================

    public void validateCreate(RoleDto.CreateRequest request) {
        String code = request.getRoleCode() == null ? null : request.getRoleCode().trim();
        if (code == null || code.isEmpty()) {
            throw BizException.badRequest("角色编码不能为空");
        }
        if (Roles.ADMIN.equals(code)) {
            throw new BizException("不允许使用保留角色编码 ADMIN");
        }
        if (sysRoleMapper.selectEntityByCode(code) != null) {
            throw new BizException("角色编码已存在: " + code);
        }
    }

    public SysRole validateUpdate(Long id, RoleDto.UpdateRequest request) {
        SysRole existing = sysRoleMapper.selectEntityById(id);
        if (existing == null) {
            throw BizException.notFound("角色不存在: " + id);
        }
        if (Roles.ADMIN.equals(existing.getRoleCode())) {
            throw new BizException("超级管理员（ADMIN）角色不允许修改");
        }
        return existing;
    }

    public SysRole validateAssignPermissions(String roleCode, List<String> permCodes) {
        SysRole existing = sysRoleMapper.selectEntityByCode(roleCode);
        if (existing == null) {
            throw BizException.notFound("角色不存在: " + roleCode);
        }
        if (Roles.ADMIN.equals(existing.getRoleCode())) {
            throw new BizException("超级管理员（ADMIN）角色不允许变更权限");
        }
        List<String> normalized = normalize(permCodes);
        if (normalized.isEmpty()) {
            throw BizException.badRequest("权限列表不能为空");
        }
        List<SysPermission> found = sysRoleMapper.selectPermissionEntitiesByCodes(normalized);
        List<String> foundCodes = found.stream().map(SysPermission::getPermCode).toList();
        List<String> missing = normalized.stream().filter(code -> !foundCodes.contains(code)).toList();
        if (!missing.isEmpty()) {
            throw new BizException("以下权限码不存在，不能授权（不支持自由构造权限码）: " + missing);
        }
        return existing;
    }

    /** 权限码 -> 主键。 */
    public List<Long> resolvePermissionIds(List<String> permCodes) {
        List<String> normalized = normalize(permCodes);
        if (normalized.isEmpty()) {
            return List.of();
        }
        List<Long> ids = sysRoleMapper.selectPermissionIdsByCodes(normalized);
        if (ids.size() != normalized.size()) {
            throw new BizException("权限解析失败，请检查权限码: " + normalized);
        }
        return ids;
    }

    /** 授权影响面（确认卡明示）。 */
    public Map<String, Object> assignPermissionsImpact(SysRole role, List<String> targetPermCodes) {
        Map<String, Object> impact = new LinkedHashMap<>();
        RoleVO current = getById(role.getId());
        impact.put("当前权限", current.getPermissionCodes());
        impact.put("变更后权限", normalize(targetPermCodes));
        impact.put("影响用户数", sysRoleMapper.selectUserIdsByRoleCode(role.getRoleCode()).size());
        impact.put("影响", "持有该角色的用户会被立即强制下线，需要重新登录（新权限随即生效）");
        return impact;
    }

    private static List<String> normalize(List<String> codes) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (codes != null) {
            codes.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(code -> !code.isEmpty())
                    .forEach(set::add);
        }
        return new ArrayList<>(set);
    }

    private void revokeTokens(List<Long> userIds, String reason) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        UserTokenRevoker revoker = tokenRevokerProvider.getIfAvailable();
        if (revoker == null) {
            log.warn("未装配 UserTokenRevoker，跳过令牌撤销（{}）", reason);
            return;
        }
        revoker.revokeUsers(userIds, reason);
    }
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
        // deleted_by 必须一并回填（混存字段，漏回填会让删除响应显示成列默认值 'DB'，设计 §2.1a）
        before.setDeletedBy(deleted.getDeletedBy());
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
        impact.put("影响", "该角色默认不再出现在列表中，其权限不再签发"
                + "（已登录的持有者会被强制下线、需重新登录）；可在「显示已删除」中恢复");
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

    /** 删除人标识：应用侧写 sys_user.id 的字符串形式；未知/直连时由 SQL 落默认 'DB'（设计 §2.1a）。 */
    private static String operatorId(Long operatorUserId) {
        return operatorUserId == null ? null : String.valueOf(operatorUserId);
    }

}
