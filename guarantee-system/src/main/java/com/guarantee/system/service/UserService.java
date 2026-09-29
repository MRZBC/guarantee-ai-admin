package com.guarantee.system.service;

import com.guarantee.common.api.PageResult;
import com.guarantee.common.exception.BizException;
import com.guarantee.common.security.DefaultCredentials;
import com.guarantee.common.security.Roles;
import com.guarantee.common.security.UserTokenRevoker;
import com.guarantee.system.dto.UserDto;
import com.guarantee.system.dto.UserRoleRef;
import com.guarantee.system.entity.SysUser;
import com.guarantee.system.entity.SysRole;
import com.guarantee.system.entity.SysDepartment;
import com.guarantee.system.mapper.SysDepartmentMapper;
import com.guarantee.system.mapper.SysRoleMapper;
import com.guarantee.system.mapper.SysUserMapper;
import com.guarantee.system.scope.DataScope;
import com.guarantee.system.scope.DataScopeService;
import com.guarantee.system.scope.QueryScope;
import com.guarantee.system.vo.UserVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
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
 * <p><b>P-10 起解除了 D-2 的收敛</b>：新增 CREATE 与 RESET_PASSWORD 两类写操作
 * （见 docs/REQ-用户管理新增与修改.md）。两者都写入**固定默认密码**并把
 * {@code must_change_password} 置 1，要求目标用户下次登录先改密。</p>
 *
 * <p><b>危险动作保护</b>（SYS-W-04）：禁止停用自己、禁止停用最后一个启用 ADMIN、
 * 禁止给自己增删 ADMIN 角色、禁止移除最后一个启用 ADMIN 的 ADMIN 角色、
 * 禁止重置自己的密码。这些规则同时被"提案生成期预检"与"确认执行期复核"调用（SYS-C-05）。</p>
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

    /** 登录账号：4-64 位字母、数字、下划线、点、中划线（P-10 新建校验）。 */
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_.-]{4,64}");

    /** 新密码长度下界（D8=A：极简策略）。 */
    public static final int PASSWORD_MIN_LENGTH = 8;

    /**
     * 新密码长度上界。
     *
     * <p>注意这是**字符数**上界，不等于字节数：BCrypt 只处理前 **72 字节**，
     * 因此 64 个 ASCII 字符安全，而 64 个汉字是 192 字节、会在第 72 字节处被静默截断
     * （即"前 24 个汉字相同"的两个密码等价）。属低危，但如果将来要收紧，
     * 校验单位应从字符改成 {@code getBytes(UTF_8).length}。</p>
     */
    public static final int PASSWORD_MAX_LENGTH = 64;

    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    /**
     * 部门是用户唯一的组织归属（{@code sys_user.dept_id NOT NULL}）：
     * 更新资料时校验目标部门存在，恢复用户时校验该部门未被删除（LD-04a）。
     */
    private final SysDepartmentMapper sysDepartmentMapper;
    private final DataScopeService dataScopeService;
    private final ObjectProvider<UserTokenRevoker> tokenRevokerProvider;
    private final WebAuditor webAuditor;
    /**
     * 密码散列。bean 定义在 {@code guarantee-common} 的 {@code PasswordEncoderConfig}——
     * 依赖方向是 auth → system，此处不能依赖 auth 里原有的那个 bean。
     */
    private final PasswordEncoder passwordEncoder;
    /** 初始密码的单一来源（内置 {@code User@123}，可经配置覆盖）。 */
    private final DefaultCredentials defaultCredentials;

    public UserService(SysUserMapper sysUserMapper,
                       SysRoleMapper sysRoleMapper,
                       SysDepartmentMapper sysDepartmentMapper,
                       DataScopeService dataScopeService,
                       ObjectProvider<UserTokenRevoker> tokenRevokerProvider,
                       WebAuditor webAuditor,
                       PasswordEncoder passwordEncoder,
                       DefaultCredentials defaultCredentials) {
        this.sysUserMapper = sysUserMapper;
        this.sysRoleMapper = sysRoleMapper;
        this.sysDepartmentMapper = sysDepartmentMapper;
        this.dataScopeService = dataScopeService;
        this.tokenRevokerProvider = tokenRevokerProvider;
        this.webAuditor = webAuditor;
        this.passwordEncoder = passwordEncoder;
        this.defaultCredentials = defaultCredentials;
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

    /**
     * 账号类型（V10 的 {@code sys_user.account_type}）：{@code HUMAN} / {@code SERVICE}。
     *
     * <p>NULL / 空 / 未知取值一律按 {@code HUMAN} 处理（见 {@link SysUser#normalizeAccountType}）。
     * <b>用户不存在也返回 HUMAN</b> —— 本方法只回答"这一行写了什么类型"，不回答"这行在不在"；
     * 机器身份链路需要判存在性时请用 {@link #getAccountIdentity(Long)}（不存在返回 {@code null}），
     * 否则会把"账号已删除"误判成"普通人账号可用"。</p>
     */
    @Transactional(readOnly = true)
    public String getAccountType(Long userId) {
        if (userId == null) {
            return SysUser.ACCOUNT_TYPE_HUMAN;
        }
        return SysUser.normalizeAccountType(sysUserMapper.selectAccountTypeById(userId));
    }

    /**
     * 是否服务账号（{@code account_type='SERVICE'}）。
     *
     * <p>用于机器凭据链路（MCP 签发/校验）：只有明确 SERVICE 才返回 true，
     * 未知/缺失/不存在都返回 false（fail-closed）。</p>
     */
    @Transactional(readOnly = true)
    public boolean isServiceAccount(Long userId) {
        return SysUser.ACCOUNT_TYPE_SERVICE.equals(getAccountType(userId));
    }

    /**
     * 机器身份最小读（T5-06）：{@code id / username / status / accountType}，**不含 password**。
     *
     * <p>MCP 签发要判的三件事就在返回值里：{@code identity != null}（存在且未删除）、
     * {@link AccountIdentity#enabled()}（启用）、{@link AccountIdentity#serviceAccount()}
     * （{@code account_type='SERVICE'}）。不做数据范围过滤——机器凭据链路没有"当前登录用户"，
     * 调用方（服务端签发接口）本身已受管理权限保护。</p>
     */
    @Transactional(readOnly = true)
    public AccountIdentity getAccountIdentity(Long userId) {
        if (userId == null) {
            return null;
        }
        SysUser row = sysUserMapper.selectIdentityById(userId);
        if (row == null) {
            return null;
        }
        return new AccountIdentity(row.getId(), row.getUsername(), row.getStatus(), row.effectiveAccountType());
    }

    /**
     * 机器身份最小投影：只带"MCP 签发需要判的三件事"，不带密码散列、不带部门/联系方式。
     *
     * <p>用 record 而不是复用 {@link SysUser}：实体里有 password，
     * 让机器凭据链路只拿到它真正需要的字段，是这一层最省事也最有效的收窄。</p>
     */
    public record AccountIdentity(Long id, String username, Integer status, String accountType) {

        /** 是否启用（{@code status = 1}）。 */
        public boolean enabled() {
            return status != null && status == 1;
        }

        /** 是否服务账号（只有明确 SERVICE 为 true）。 */
        public boolean serviceAccount() {
            return SysUser.ACCOUNT_TYPE_SERVICE.equals(accountType);
        }

        /** 是否可用于签发机器凭据：存在（调用方已判 null）+ 启用 + 服务账号。 */
        public boolean machineIdentity() {
            return enabled() && serviceAccount();
        }
    }

    /** 当前用户的启用角色编码，供认证与鉴权使用。 */
    @Transactional(readOnly = true)
    public List<String> listRoleCodesByUserId(Long userId) {
        return sysUserMapper.listRoleCodesByUserId(userId);
    }

    /**
     * 角色编码 → 面向用户的展示名（{@code ADMIN} → {@code 超级管理员}）。
     *
     * <p><b>为什么必须转换</b>：返回的这些名称会进入确认卡的变更明细与影响面，
     * 那是**给业务用户看的正文**。直接显示编码等于把内部枚举码丢给用户——
     * 真机上出现过同一屏里正文写「该用户将从超级管理员降为数据分析师」、
     * 而确认卡写「当前角色 ADMIN；变更后角色 ANALYST」的割裂。</p>
     *
     * <p><b>查不到的编码原样返回</b>：宁可偶尔露出一个编码，也不能静默丢掉一个角色
     * ——那会让影响面与实际变更不一致，比不好看严重得多。</p>
     *
     * <p><b>注意与逻辑判定的区分</b>：{@code Roles.ADMIN.equals(...)} 这类判断必须继续用编码，
     * 不能改用展示名。本方法只服务展示。</p>
     */
    @Transactional(readOnly = true)
    public List<String> roleDisplayNames(java.util.Collection<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return List.of();
        }
        List<String> codes = roleCodes.stream().filter(Objects::nonNull).distinct().toList();
        if (codes.isEmpty()) {
            return List.of();
        }
        Map<String, String> names = sysRoleMapper.selectEntityByCodes(codes).stream()
                .filter(role -> role.getRoleCode() != null)
                .collect(Collectors.toMap(SysRole::getRoleCode,
                        role -> role.getRoleName() == null ? role.getRoleCode() : role.getRoleName(),
                        (first, second) -> first));
        return codes.stream().map(code -> names.getOrDefault(code, code)).toList();
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

    /**
     * 修改用户资料：realName / phone / email / deptId。
     *
     * <p>阶段一 O3：用户不再挂机构，"清空部门"能力（{@code clearDept}）已整体移除——
     * 用户**必须属于一个部门**，{@code deptId} 为 {@code null} 表示"不改"。</p>
     */
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
        after.put("deptId", request.getDeptId() == null ? existing.getDeptId() : request.getDeptId());
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
    // 写（P-10：新增 / 密码）
    // ==================================================================

    /**
     * 新建用户（P-10 / CREATE）。
     *
     * <p>密码不由前端提供：写入 {@link DefaultCredentials} 的固定默认密码，并置"首次登录强制改密"。
     * 因此<b>明文密码永不经过 HTTP</b>（既不在请求里，也不在响应里）。</p>
     */
    @Transactional
    public UserVO create(UserDto.CreateRequest request, DataScope scope, Long operatorUserId) {
        List<String> roleCodes = validateCreate(request, scope);
        List<Long> roleIds = resolveRoleIds(roleCodes);

        SysUser entity = new SysUser();
        entity.setUsername(request.getUsername().trim());
        entity.setRealName(request.getRealName().trim());
        entity.setDeptId(request.getDeptId());
        entity.setPhone(normalize(request.getPhone()));
        entity.setEmail(normalize(request.getEmail()));
        // 新账号固定启用：让创建时就能建停用账号没有实际价值，反而多一条"建好却是停用"的困惑路径
        entity.setStatus(1);
        applyInitialPassword(entity);

        sysUserMapper.insert(entity);
        if (!roleIds.isEmpty()) {
            sysUserMapper.upsertUserRoles(entity.getId(), roleIds);
        }
        log.info("新增用户成功 id={} username={} 角色={} 操作者={}",
                entity.getId(), entity.getUsername(), roleCodes, operatorUserId);

        // 页面直连审计（SYS-A-07 / AC-22）。phone / email 传原值，由 OperationAuditService 统一脱敏（D-4）。
        // 绝不记录任何密码信息——"需要改密"这个事实本身是可以记的，密码不行（SYS-A-05）。
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("username", entity.getUsername());
        after.put("realName", entity.getRealName());
        after.put("deptId", entity.getDeptId());
        after.put("phone", entity.getPhone());
        after.put("email", entity.getEmail());
        after.put("status", entity.getStatus());
        after.put("roleCodes", roleCodes);
        after.put("mustChangePassword", entity.getMustChangePassword());
        webAuditor.success("CREATE", "USER", entity.getId(), entity.getUsername(), null, after);

        return getById(entity.getId());
    }

    /**
     * 自助改密（P-10 / §5.5）。
     *
     * <p>顺序不可颠倒：取当前散列 → 校验旧密码 → 校验新密码策略 → 写新散列并清强制改密标记 →
     * <b>撤销该用户全部令牌</b>。</p>
     *
     * <p><b>撤销令牌是必须的</b>，不是可选的收尾：当前 JWT 里的 {@code mcp} claim 恒为 true，
     * 不撤销的话用户改完密码仍会被强制闸门拦住（表现为"改了但没生效"）；
     * 另外若密码是被他人改动，原持有者还能继续用旧会话，等于密码变更不生效。</p>
     *
     * <p>旧密码错误<b>不计入登录失败锁定</b>：走的是已认证会话，不是登录尝试，
     * 不存在需要爆破的对象。</p>
     */
    @Transactional
    public void changeOwnPassword(Long userId, String oldPassword, String newPassword) {
        SysUser existing = sysUserMapper.selectEntityById(userId);
        if (existing == null) {
            throw BizException.notFound(DataScopeService.OUT_OF_SCOPE_MESSAGE);
        }
        if (oldPassword == null || !passwordEncoder.matches(oldPassword, existing.getPassword())) {
            throw new BizException("原密码不正确");
        }
        if (newPassword != null && newPassword.equals(oldPassword)) {
            throw BizException.badRequest("新密码不能与原密码相同");
        }
        validateNewPassword(newPassword);

        int affected = sysUserMapper.updatePassword(userId, passwordEncoder.encode(newPassword), 0);
        if (affected != 1) {
            throw new BizException("密码修改失败，请重试");
        }
        revokeTokens(List.of(userId), "用户修改了自己的密码: " + existing.getUsername());
        log.info("用户自助改密成功 id={} username={}", userId, existing.getUsername());

        // 审计只记"发生了改密"与标记变化，不记任何密码信息（SYS-A-05）
        webAuditor.success("CHANGE_PASSWORD", "USER", userId, existing.getUsername(),
                Map.of("mustChangePassword", String.valueOf(existing.getMustChangePassword())),
                Map.of("mustChangePassword", "0"));
    }

    /**
     * 管理员重置他人密码（P-10 / §4.7，D3=B）。
     *
     * <p>语义是"把密码重置回固定默认密码，并要求其下次登录必须修改"——<b>不是</b>让管理员
     * 指定一个密码。让管理员指定会让密码经手他人并进入请求体，正是 D1=C 要避免的。</p>
     */
    @Transactional
    public UserVO resetPassword(Long id, DataScope scope, Long operatorUserId) {
        SysUser existing = validateResetPassword(id, scope, operatorUserId);
        int affected = sysUserMapper.updatePassword(id,
                passwordEncoder.encode(defaultCredentials.defaultPassword()), 1);
        if (affected != 1) {
            throw new BizException("密码重置失败，请刷新后重试");
        }
        revokeTokens(List.of(id), "管理员重置了密码: " + existing.getUsername());
        log.info("重置用户密码成功 id={} username={} 操作者={}",
                id, existing.getUsername(), operatorUserId);
        webAuditor.success("RESET_PASSWORD", "USER", id, existing.getUsername(),
                Map.of("mustChangePassword", String.valueOf(existing.getMustChangePassword())),
                Map.of("mustChangePassword", "1"));
        return getById(id);
    }

    /**
     * 是否处于"首次登录强制改密"状态（AuthService 构造 {@code CurrentUserVO} 用）。
     *
     * <p>为什么不把该字段加到 {@code UserVO}：那会让它随**每一次用户列表查询**返回。
     * 这个标记只在"我自己"的上下文里有意义。</p>
     */
    @Transactional(readOnly = true)
    public boolean mustChangePassword(Long userId) {
        SysUser entity = sysUserMapper.selectEntityById(userId);
        return entity != null && Integer.valueOf(1).equals(entity.getMustChangePassword());
    }

    /**
     * 新密码策略（D8=A：极简）。
     *
     * <p>复杂度 / 定期改密 / 历史密码不可复用仍属 {@code REQ-登录安全与令牌生命周期加固方案}
     * 的 N-4，本需求只采纳了其中的"首次登录强制改密"。</p>
     */
    private void validateNewPassword(String newPassword) {
        if (newPassword == null || newPassword.isBlank()) {
            throw BizException.badRequest("新密码不能为空");
        }
        if (newPassword.length() < PASSWORD_MIN_LENGTH || newPassword.length() > PASSWORD_MAX_LENGTH) {
            throw BizException.badRequest(
                    "新密码长度需在 " + PASSWORD_MIN_LENGTH + "~" + PASSWORD_MAX_LENGTH + " 位之间");
        }
        if (newPassword.equals(defaultCredentials.defaultPassword())) {
            throw BizException.badRequest("新密码不能与系统默认密码相同");
        }
    }

    /**
     * 写入固定默认密码并置"首次登录强制改密"。
     *
     * <p>创建与重置<b>共用这一处</b>：两处各写一遍必然漂移，而"其中一处忘了置标记"
     * 会让新账号直接可用（等于强制改密形同虚设）。</p>
     */
    private void applyInitialPassword(SysUser entity) {
        entity.setPassword(passwordEncoder.encode(defaultCredentials.defaultPassword()));
        entity.setMustChangePassword(1);
    }

    /** 空白字符串归一化为 null：避免手机号/邮箱存成空串，与"未填"难以区分。 */
    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 手机号 / 邮箱格式校验（修改资料与新建共用，避免两处文案漂移）。 */
    private static void validatePhoneAndEmail(String phone, String email) {
        if (phone != null && !phone.isBlank() && !PHONE.matcher(phone.trim()).matches()) {
            throw BizException.badRequest("手机号格式不正确，应为 11 位大陆手机号");
        }
        if (email != null && !email.isBlank() && !EMAIL.matcher(email.trim()).matches()) {
            throw BizException.badRequest("邮箱格式不正确");
        }
    }

    /** 角色编码去空、去重、保序；为空直接失败（D2=A：新建必须至少 1 个角色）。 */
    private static List<String> normalizeRoleCodes(List<String> roleCodes) {
        LinkedHashSet<String> target = new LinkedHashSet<>();
        if (roleCodes != null) {
            roleCodes.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(code -> !code.isEmpty())
                    .forEach(target::add);
        }
        if (target.isEmpty()) {
            throw BizException.badRequest("角色列表不能为空：新建用户必须至少分配一个角色");
        }
        return new ArrayList<>(target);
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
        validatePhoneAndEmail(request.getPhone(), request.getEmail());
        // 不得修改自己的部门：改变自己的组织归属属于提权风险（SYS-W-04）
        if (request.getDeptId() != null && id.equals(operatorUserId)) {
            throw BizException.badRequest("不允许修改自己的所属部门");
        }
        // 用户必须属于一个部门（sys_user.dept_id NOT NULL）：
        // 传了 deptId 就是要"改到某个部门"，因此该部门必须存在且未删除
        if (request.getDeptId() != null
                && sysDepartmentMapper.selectEntityById(request.getDeptId()) == null) {
            throw BizException.badRequest("部门不存在或已删除: " + request.getDeptId());
        }
        return existing;
    }

    /**
     * 新建用户预检（P-10）。
     *
     * @return 去重保序后的角色编码
     */
    public List<String> validateCreate(UserDto.CreateRequest request, DataScope scope) {
        String username = request.getUsername() == null ? null : request.getUsername().trim();
        if (username == null || username.isEmpty()) {
            throw BizException.badRequest("登录账号不能为空");
        }
        if (!USERNAME.matcher(username).matches()) {
            throw BizException.badRequest("登录账号由 4-64 位字母、数字、下划线、点或中划线组成");
        }
        if (request.getRealName() == null || request.getRealName().trim().isEmpty()) {
            throw BizException.badRequest("姓名不能为空");
        }
        // sys_user.dept_id 是 NOT NULL：用户必须属于一个部门
        if (request.getDeptId() == null) {
            throw BizException.badRequest("所属部门不能为空");
        }
        if (sysDepartmentMapper.selectEntityById(request.getDeptId()) == null) {
            throw BizException.badRequest("部门不存在或已删除: " + request.getDeptId());
        }
        validatePhoneAndEmail(request.getPhone(), request.getEmail());

        // 登录名唯一性只比对**未删除**账号：selectByUsername 已带 is_deleted = 0（LD-05b），
        // 与唯一键 (username, IFNULL(deleted_at, ...)) 的语义一致——已删除账号的名字可以被复用。
        // 若改用"含已删除"的查询，会把合法创建误判为冲突。
        if (sysUserMapper.selectByUsername(username) != null) {
            throw new BizException("登录账号已存在：" + username);
        }

        // 角色必填（D2=A）：resolveRoleIds 同时校验"角色存在且启用"
        List<String> roleCodes = normalizeRoleCodes(request.getRoleCodes());
        resolveRoleIds(roleCodes);
        return roleCodes;
    }

    /**
     * 重置密码预检（P-10 / §4.7 守卫表）。
     *
     * <p><b>刻意没有"不得重置最后一个启用 ADMIN"这一条。</b>默认权限矩阵下
     * {@code system:user:reset-password} 仅 ADMIN 持有，而"最后一个启用 ADMIN"必然就是
     * 操作者本人（两者都是启用的 ADMIN），已被下面的"不得重置自己"完整覆盖；
     * 加了就是一段永不触发的死代码，还会挡住"管理员 A 帮忘记密码的管理员 B 重置"这一合法场景。
     * 完整推演见需求文档 §4.7a 的纠错记录。</p>
     */
    public SysUser validateResetPassword(Long id, DataScope scope, Long operatorUserId) {
        SysUser existing = requireVisible(id, scope);
        if (existing.getId().equals(operatorUserId)) {
            throw new BizException("请使用「修改密码」修改自己的密码");
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
        // 逻辑判定用编码，展示用中文名——两者不能混（见 roleDisplayNames）
        List<String> roles = sysUserMapper.listRoleCodesByUserId(user.getId());
        impact.put("持有角色", roleDisplayNames(roles));
        impact.put("是否最后一个启用管理员",
                roles.contains(Roles.ADMIN) && sysUserMapper.countOtherEnabledAdmins(user.getId()) == 0);
        impact.put("影响", "该用户未完结的 AI 会话将失效；该用户会被立即强制下线，需要重新登录");
        return impact;
    }

    /** 角色变更影响面：当前 -> 变更后（均以中文角色名展示）。 */
    public Map<String, Object> assignRolesImpact(SysUser user, List<String> targetRoleCodes) {
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("当前角色", roleDisplayNames(sysUserMapper.listRoleCodesByUserId(user.getId())));
        impact.put("变更后角色", roleDisplayNames(targetRoleCodes));
        impact.put("影响", "该用户会被立即强制下线，需要重新登录（新权限随即生效）");
        return impact;
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /**
     * 用户可见性校验（SYS-P-14）。
     *
     * <p>阶段一 O3：用户已不再挂机构，数据范围恒为全量，因此这里只做**存在性**判定；
     * 跨范围目标返回与"不存在"一致的文案（SYS-P-09）。</p>
     */
    @Transactional(readOnly = true)
    public SysUser requireVisible(Long id, DataScope scope) {
        SysUser entity = sysUserMapper.selectEntityById(id);
        if (entity == null) {
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
        impact.put("持有角色", roleDisplayNames(sysUserMapper.listRoleCodesByUserId(user.getId())));
        impact.put("是否最后一个启用管理员",
                hasRole(user.getId(), Roles.ADMIN)
                        && sysUserMapper.countOtherEnabledAdmins(user.getId()) == 0);
        impact.put("影响", "该用户默认不再出现在列表中且无法登录；若当前处于登录状态，会被立即强制下线。"
                + "可在「显示已删除」中恢复");
        return impact;
    }

    /**
     * 用户恢复（LD-04a）。
     *
     * <p>用户必须属于一个部门，因此所属部门若不存在或仍处于已删除状态则拒绝——
     * 否则会出现"部门已删、用户被恢复"的悬挂引用。所属机构已不再是用户的归属维度
     * （阶段一 O3），故不再校验。</p>
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

    /**
     * 恢复阻碍项（LD-04a）：所属部门必须存在且已恢复（dept_id 为必填，故无"无部门"分支）；
     * 且登录账号不能被另一个有效账号占用。
     *
     * <p><b>登录名冲突这一条是 P-10 补上的</b>：唯一键是
     * {@code (username, IFNULL(deleted_at, …))}，所以已删除账号的登录名**可以被复用**。
     * 在 P-10 之前根本没有"新建用户"入口，这个冲突不可达，因此一直没有校验；
     * 一旦能新建，"新建同名账号 → 恢复旧账号"就会撞唯一键抛 SQL 异常 →
     * <b>500 而不是可读的业务提示</b>。这里与 {@code RoleService.restore} 的
     * "角色编码已被同名的有效角色占用" 对称。</p>
     */
    public List<String> restoreBlockers(SysUser user) {
        List<String> blockers = new ArrayList<>();
        SysUser byName = sysUserMapper.selectByUsername(user.getUsername());
        if (byName != null && !byName.getId().equals(user.getId())) {
            blockers.add("登录账号已被同名账号占用，无法恢复：" + user.getUsername());
        }
        SysDepartment dept = sysDepartmentMapper.selectEntityByIdIncludingDeleted(user.getDeptId());
        if (dept == null) {
            blockers.add("所属部门不存在");
        } else if (Integer.valueOf(1).equals(dept.getIsDeleted())) {
            blockers.add("请先恢复其所属部门：" + dept.getDeptName());
        }
        return blockers;
    }

    /** 删除人标识：应用侧写 sys_user.id 的字符串形式；未知/直连时由 SQL 落默认 'DB'（设计 §2.1a）。 */
    private static String operatorId(Long operatorUserId) {
        return operatorUserId == null ? null : String.valueOf(operatorUserId);
    }

}
