package com.guarantee.system.service;

import com.guarantee.common.api.PageResult;
import com.guarantee.common.exception.BizException;
import com.guarantee.common.security.Roles;
import com.guarantee.common.security.UserTokenRevoker;
import com.guarantee.system.dto.UserDto;
import com.guarantee.system.dto.UserRoleRef;
import com.guarantee.system.entity.SysUser;
import com.guarantee.system.entity.SysDepartment;
import com.guarantee.system.entity.SysOrg;
import com.guarantee.system.mapper.SysDepartmentMapper;
import com.guarantee.system.mapper.SysOrgMapper;
import com.guarantee.system.mapper.SysRoleMapper;
import com.guarantee.system.mapper.SysUserMapper;
import com.guarantee.system.scope.DataScope;
import com.guarantee.system.scope.DataScopeService;
import com.guarantee.system.scope.QueryScope;
import com.guarantee.system.vo.UserVO;
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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 用户配置服务。
 *
 * <p><b>安全约束</b>：所有对外返回的 VO 都不含 password；只有
 * {@link #getEntityByUsername(String)}（登录专用）返回带密码散列的实体。</p>
 *
 * <p><b>D-2 收敛</b>：写操作只有 UPDATE / ENABLE-DISABLE / ASSIGN_ROLES 三类，
 * 没有 create 与 reset-password —— 这是"新建账号与密码重置单独立项"的代码级落点。</p>
 *
 * <p><b>危险动作保护</b>（SYS-W-04）：禁止停用自己、禁止停用最后一个启用 ADMIN、
 * 禁止给自己增删 ADMIN 角色、禁止移除最后一个启用 ADMIN 的 ADMIN 角色。
 * 这些规则同时被"提案生成期预检"与"确认执行期复核"调用（SYS-C-05）。</p>
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    /** 用户写操作目标解析的候选上限。 */
    public static final int CANDIDATE_LIMIT = 20;

    /** 手机号：11 位大陆手机号（SYS-W-04 格式校验）。 */
    private static final Pattern PHONE = Pattern.compile("1[3-9]\\d{9}");

    /** 邮箱：宽松匹配（SYS-W-04 格式校验）。 */
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    private final SysUserMapper sysUserMapper;
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
    }

    // ==================================================================
    // 读
    // ==================================================================

    @Transactional(readOnly = true)
    public PageResult<UserVO> page(UserDto.Query query, DataScope scope) {
        query.setScope(QueryScope.of(scope));
        long total = sysUserMapper.countByQuery(query);
        if (total == 0) {
            return PageResult.empty(query.getPageNum(), query.getPageSize());
        }
        List<UserVO> list = sysUserMapper.selectPage(query, query.offset(), query.getPageSize());
        fillRoles(list);
        return PageResult.of(query.getPageNum(), query.getPageSize(), total, list);
    }

    /** 无数据范围版本（登录链路与内部调用使用，不对外暴露）。 */
    @Transactional(readOnly = true)
    public UserVO getById(Long id) {
        UserDto.Query query = new UserDto.Query();
        query.setId(id);
        query.setScope(QueryScope.unrestricted());
        UserVO vo = sysUserMapper.selectVoScoped(query);
        if (vo == null) {
            throw BizException.notFound("用户不存在: " + id);
        }
        fillRoles(List.of(vo));
        return vo;
    }

    /** 带数据范围的明细（SYS-P-14）。 */
    @Transactional(readOnly = true)
    public UserVO getById(Long id, DataScope scope) {
        requireVisible(id, scope);
        return getById(id);
    }

    /**
     * 登录专用：按账号读取用户实体（包含 password 散列）。
     * 用户不存在返回 {@code null}，由认证层决定如何响应。
     */
    @Transactional(readOnly = true)
    public SysUser getEntityByUsername(String username) {
        return sysUserMapper.selectByUsername(username);
    }

    /** 当前用户的启用角色编码，供认证与鉴权使用。 */
    @Transactional(readOnly = true)
    public List<String> listRoleCodesByUserId(Long userId) {
        return sysUserMapper.listRoleCodesByUserId(userId);
    }

    /** 当前用户的启用权限编码（多角色去重），供认证与鉴权使用。 */
    @Transactional(readOnly = true)
    public List<String> listPermissionCodesByUserId(Long userId) {
        return sysUserMapper.listPermissionCodesByUserId(userId);
    }

    /** 登录成功后更新最近登录时间。 */
    @Transactional
    public void updateLastLoginAt(Long userId) {
        sysUserMapper.updateLastLoginAt(userId);
    }

    /** 批量回填角色，避免逐条查询。 */
    private void fillRoles(List<UserVO> users) {
        if (users.isEmpty()) {
            return;
        }
        List<Long> userIds = users.stream().map(UserVO::getId).toList();
        Map<Long, List<UserRoleRef>> grouped = sysUserMapper.selectRoleRefsByUserIds(userIds).stream()
                .collect(Collectors.groupingBy(UserRoleRef::getUserId));
        for (UserVO user : users) {
            List<UserRoleRef> refs = grouped.getOrDefault(user.getId(), List.of());
            user.setRoleIds(refs.stream().map(UserRoleRef::getRoleId).toList());
            user.setRoleNames(refs.stream().map(UserRoleRef::getRoleName).toList());
            user.setRoleCodes(refs.stream().map(UserRoleRef::getRoleCode).toList());
        }
    }

    // ==================================================================
    // 写（SYS-W-04，D-2 收敛后的三类动作）
    // ==================================================================

    /** 修改用户资料：realName / phone / email / deptId。 */
    @Transactional
    public UserVO updateProfile(Long id, UserDto.UpdateRequest request, DataScope scope, Long operatorUserId) {
        SysUser existing = validateUpdateProfile(id, request, scope, operatorUserId);
        SysUser entity = new SysUser();
        entity.setId(id);
        entity.setRealName(request.getRealName());
        entity.setPhone(request.getPhone());
        entity.setEmail(request.getEmail());
        entity.setDeptId(request.getDeptId());
        sysUserMapper.updateProfile(entity);
        if (Boolean.TRUE.equals(request.getClearDept())) {
            sysUserMapper.clearDept(id);
        }
        log.info("修改用户资料成功 id={} 操作者={} 变更字段={}", id, operatorUserId,
                describeChangedFields(existing, request));

        // 页面直连审计（SYS-A-07 / AC-22）。
        // 关键：phone / email 传**原值**，由 OperationAuditService 统一脱敏（D-4 / SYS-A-02b）。
        // 绝不能在这里自己拼掩码——脱敏只允许有一个实现（RK-12）。
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("realName", existing.getRealName());
        before.put("phone", existing.getPhone());
        before.put("email", existing.getEmail());
        before.put("deptId", existing.getDeptId());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("realName", request.getRealName() == null ? existing.getRealName() : request.getRealName());
        after.put("phone", request.getPhone() == null ? existing.getPhone() : request.getPhone());
        after.put("email", request.getEmail() == null ? existing.getEmail() : request.getEmail());
        after.put("deptId", Boolean.TRUE.equals(request.getClearDept())
                ? null : (request.getDeptId() == null ? existing.getDeptId() : request.getDeptId()));
        webAuditor.success("UPDATE", "USER", id, existing.getUsername(), before, after);

        return getById(id);
    }

    /**
     * 用户启停。
     *
     * <p>停用后必须撤销该用户全部令牌，并在结果中明示"该用户需重新登录"（SYS-C-07）。</p>
     */
    @Transactional
    public UserVO changeStatus(Long id, Integer targetStatus, DataScope scope, Long operatorUserId) {
        SysUser existing = requireVisible(id, scope);
        validateStatusChange(existing, targetStatus, operatorUserId);
        int affected = sysUserMapper.updateStatus(id, targetStatus, existing.getStatus());
        if (affected != 1) {
            throw new BizException("用户状态已被他人修改，请刷新后重试");
        }
        if (targetStatus == 0) {
            revokeTokens(List.of(id), "用户被停用: " + existing.getUsername());
        }
        log.info("用户启停成功 id={} username={} status={} 操作者={}",
                id, existing.getUsername(), targetStatus, operatorUserId);

        webAuditor.success(targetStatus == 1 ? "ENABLE" : "DISABLE", "USER", id, existing.getUsername(),
                Map.of("status", existing.getStatus()), Map.of("status", targetStatus));

        return getById(id);
    }

    /**
     * 角色分配。
     *
     * <p>变更后必须撤销该用户全部令牌，使其权限**立即**生效（SYS-C-07）。</p>
     */
    @Transactional
    public UserVO assignRoles(Long id, List<String> roleCodes, DataScope scope, Long operatorUserId) {
        SysUser existing = requireVisible(id, scope);
        // 变更前的角色必须在写入之前取，否则审计的 before 会等于 after（Diff 恒为空）
        List<String> currentRolesSnapshot = sysUserMapper.listRoleCodesByUserId(id);
        List<String> normalized = validateAssignRoles(existing, roleCodes, operatorUserId);
        List<Long> roleIds = resolveRoleIds(normalized);
        // 关联表改造（设计 §4）：不再"先清后插"——逻辑删除后旧行仍在表中，
        // (user_id, role_id) 唯一键会拒绝 INSERT。改为一次 UPSERT 表达最终状态，两条语句顺序无关。
        String operator = operatorUserId == null ? null : String.valueOf(operatorUserId);
        sysUserMapper.softDeleteUserRolesNotIn(id, roleIds, operator);
        if (!roleIds.isEmpty()) {
            sysUserMapper.upsertUserRoles(id, roleIds);
        }
        revokeTokens(List.of(id), "用户角色被变更: " + existing.getUsername() + " -> " + normalized);
        log.info("用户角色分配成功 id={} username={} roles={} 操作者={}",
                id, existing.getUsername(), normalized, operatorUserId);

        // 页面直连审计：before 用写入前快照，after 用最终角色集
        webAuditor.success("ASSIGN_ROLES", "USER", id, existing.getUsername(),
                Map.of("roleCodes", currentRolesSnapshot),
                Map.of("roleCodes", normalized));

        return getById(id);
    }

    // ==================================================================
    // 预检（提案生成期调用，与执行期复核共用同一实现，SYS-C-05）
    // ==================================================================

    public SysUser validateUpdateProfile(Long id, UserDto.UpdateRequest request,
                                         DataScope scope, Long operatorUserId) {
        SysUser existing = requireVisible(id, scope);
        if (request.isEmpty()) {
            throw BizException.badRequest("至少需要提供一个待修改字段（realName / phone / email / deptId）");
        }
        if (request.getPhone() != null && !request.getPhone().isBlank()
                && !PHONE.matcher(request.getPhone().trim()).matches()) {
            throw BizException.badRequest("手机号格式不正确，应为 11 位大陆手机号");
        }
        if (request.getEmail() != null && !request.getEmail().isBlank()
                && !EMAIL.matcher(request.getEmail().trim()).matches()) {
            throw BizException.badRequest("邮箱格式不正确");
        }
        // 不得修改自己的部门：改变自己的数据范围属于提权风险（SYS-W-04）
        if (request.getDeptId() != null && id.equals(operatorUserId)) {
            throw BizException.badRequest("不允许修改自己的所属部门");
        }
        return existing;
    }

    /**
     * 启停预检（危险动作保护，SYS-W-04）。
     *
     * <p>三项拒绝：停用自己、停用最后一个启用 ADMIN、目标已在目标状态。</p>
     */
    public void validateStatusChange(SysUser existing, Integer targetStatus, Long operatorUserId) {
        if (existing.getStatus() != null && existing.getStatus().equals(targetStatus)) {
            throw new BizException("用户已处于目标状态，无需变更：" + existing.getUsername());
        }
        if (targetStatus != 0) {
            return;
        }
        if (existing.getId().equals(operatorUserId)) {
            throw new BizException("不允许停用自己的账号");
        }
        if (hasRole(existing.getId(), Roles.ADMIN)
                && sysUserMapper.countOtherEnabledAdmins(existing.getId()) == 0) {
            throw new BizException("该用户是最后一个启用状态的超级管理员，不允许停用");
        }
    }

    /**
     * 角色分配预检（危险动作保护，SYS-W-04）。
     *
     * <p>校验：roleCodes 均来自启用状态角色；不得给自己增删 ADMIN；
     * 不得移除最后一个启用 ADMIN 的 ADMIN 角色。</p>
     *
     * @return 去重排序后的角色编码
     */
    public List<String> validateAssignRoles(SysUser existing, List<String> roleCodes, Long operatorUserId) {
        LinkedHashSet<String> target = new LinkedHashSet<>();
        if (roleCodes != null) {
            roleCodes.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(code -> !code.isEmpty())
                    .forEach(target::add);
        }
        if (target.isEmpty()) {
            throw BizException.badRequest("角色列表不能为空");
        }
        List<Long> roleIds = resolveRoleIds(new ArrayList<>(target));
        // 启用状态校验已由 resolveRoleIds 完成（不接受停用角色）

        List<String> currentRoles = sysUserMapper.listRoleCodesByUserId(existing.getId());
        boolean currentlyAdmin = currentRoles.contains(Roles.ADMIN);
        boolean targetAdmin = target.contains(Roles.ADMIN);

        if (existing.getId().equals(operatorUserId) && currentlyAdmin != targetAdmin) {
            throw new BizException("不允许给自己增加或移除超级管理员（ADMIN）角色");
        }
        if (currentlyAdmin && !targetAdmin
                && sysUserMapper.countOtherEnabledAdmins(existing.getId()) == 0) {
            throw new BizException("该用户是最后一个启用状态的超级管理员，不允许移除其 ADMIN 角色");
        }
        return new ArrayList<>(target);
    }

    /** 角色编码 -> 主键；存在不存在的编码时直接失败（不允许静默忽略）。 */
    public List<Long> resolveRoleIds(List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return List.of();
        }
        List<String> enabled = sysRoleMapper.selectEnabledCodes(roleCodes);
        List<String> missing = roleCodes.stream().filter(code -> !enabled.contains(code)).toList();
        if (!missing.isEmpty()) {
            throw new BizException("角色不存在或已停用，不能分配: " + missing);
        }
        List<Long> ids = sysRoleMapper.selectIdsByCodes(roleCodes);
        if (ids.size() != roleCodes.size()) {
            // 防御：编码存在但解析失败说明数据不一致，宁可失败也不静默少配角色
            throw new BizException("角色解析失败，请检查角色配置: " + roleCodes);
        }
        return ids;
    }

    // ==================================================================
    // 影响面（确认卡明示）
    // ==================================================================

    /** 停用影响面：未完结会话数、持有角色、是否最后一个 ADMIN（SYS-W-04）。 */
    public Map<String, Object> stopImpact(SysUser user) {
        Map<String, Object> impact = new LinkedHashMap<>();
        List<String> roles = sysUserMapper.listRoleCodesByUserId(user.getId());
        impact.put("持有角色", roles);
        impact.put("是否最后一个启用管理员",
                roles.contains(Roles.ADMIN) && sysUserMapper.countOtherEnabledAdmins(user.getId()) == 0);
        impact.put("影响", "该用户未完结的 AI 会话将失效，其持有的 JWT 将被撤销，需重新登录");
        return impact;
    }

    /** 角色变更影响面：当前 -> 变更后。 */
    public Map<String, Object> assignRolesImpact(SysUser user, List<String> targetRoleCodes) {
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("当前角色", sysUserMapper.listRoleCodesByUserId(user.getId()));
        impact.put("变更后角色", targetRoleCodes);
        impact.put("影响", "该用户持有的 JWT 将被撤销，其权限变更立即生效，需重新登录");
        return impact;
    }

    // ==================================================================
    // 内部
    // ==================================================================

    @Transactional(readOnly = true)
    public SysUser requireVisible(Long id, DataScope scope) {
        SysUser entity = sysUserMapper.selectEntityById(id);
        if (entity == null || !scope.contains(entity.getOrgId())) {
            throw BizException.notFound(DataScopeService.OUT_OF_SCOPE_MESSAGE);
        }
        return entity;
    }

    /** 用户是否持有指定角色（含停用角色过滤，与鉴权口径一致）。 */
    public boolean hasRole(Long userId, String roleCode) {
        return sysUserMapper.listRoleCodesByUserId(userId).contains(roleCode);
    }

    /** 撤销令牌；撤销器缺失或失败都不影响业务结果（见 {@link UserTokenRevoker} 约定）。 */
    private void revokeTokens(List<Long> userIds, String reason) {
        UserTokenRevoker revoker = tokenRevokerProvider.getIfAvailable();
        if (revoker == null) {
            log.warn("未装配 UserTokenRevoker，跳过令牌撤销（{}）", reason);
            return;
        }
        revoker.revokeUsers(userIds, reason);
    }

    private static String describeChangedFields(SysUser before, UserDto.UpdateRequest request) {
        List<String> fields = new ArrayList<>();
        if (request.getRealName() != null && !request.getRealName().equals(before.getRealName())) {
            fields.add("realName");
        }
        if (request.getPhone() != null && !request.getPhone().equals(before.getPhone())) {
            fields.add("phone");
        }
        if (request.getEmail() != null && !request.getEmail().equals(before.getEmail())) {
            fields.add("email");
        }
        if (request.getDeptId() != null && !request.getDeptId().equals(before.getDeptId())) {
            fields.add("deptId");
        }
        if (Boolean.TRUE.equals(request.getClearDept())) {
            fields.add("deptId");
        }
        return fields.toString();
    }
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
        // deleted_by 必须一并回填（混存字段，漏回填会让删除响应显示成列默认值 'DB'，设计 §2.1a）
        before.setDeletedBy(deleted.getDeletedBy());
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

    /** 删除人标识：应用侧写 sys_user.id 的字符串形式；未知/直连时由 SQL 落默认 'DB'（设计 §2.1a）。 */
    private static String operatorId(Long operatorUserId) {
        return operatorUserId == null ? null : String.valueOf(operatorUserId);
    }

}
