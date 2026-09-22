package com.guarantee.web;

import com.guarantee.auth.dto.LoginRequest;
import com.guarantee.auth.service.AuthService;
import com.guarantee.common.api.ResultCode;
import com.guarantee.common.exception.BizException;
import com.guarantee.common.security.CurrentUser;
import com.guarantee.common.security.Roles;
import com.guarantee.ai.service.ProposalSecretStore;
import com.guarantee.system.dto.OrgDto;
import com.guarantee.system.scope.DataScope;
import com.guarantee.system.scope.DataScopeService;
import com.guarantee.system.service.OrgService;
import com.guarantee.system.service.UserService;
import com.guarantee.system.vo.OrgVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 逻辑删除在**完整应用**（guarantee-web 装配全部模块）上的验收测试。
 *
 * <p>覆盖设计文档 §11 中只能在完整上下文里验证的三项：
 * LD-T8（已删除用户登录提示与密码错误完全一致，防账号枚举）、
 * LD-T12（删除 / 恢复都落 {@code ai_operation_audit}，source=WEB，敏感字段脱敏）、
 * LD-T21（{@code ai_operation_secret} 保持**物理删除**，LD-EX-01）。
 * 另外补一条 AC-8：被订单引用的机构删除被拒绝并给出引用数。</p>
 */
@SpringBootTest(
        classes = GuaranteeAiAdminApplication.class,
        properties = {
                "guarantee.data-init.enabled=false",
                "guarantee.ai.proposal-expire-interval-ms=3600000",
                "guarantee.ai.audit-inspect-interval-ms=3600000"
        })
class LogicalDeleteWebIT {

    private static final String P = "__ldtw_";

    @Autowired
    private AuthService authService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private UserService userService;
    @Autowired
    private OrgService orgService;
    @Autowired
    private ProposalSecretStore proposalSecretStore;
    @Autowired
    private DataScopeService dataScopeService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUpPrincipal() {
        // 机构已从用户上移除：Principal 不再携带 orgId
        CurrentUser.set(new CurrentUser.Principal(adminId(), "admin", "超级管理员",
                List.of(Roles.ADMIN), userService.listPermissionCodesByUserId(adminId())));
    }

    @AfterEach
    void cleanup() {
        CurrentUser.clear();
        jdbcTemplate.update("UPDATE sys_user SET status = 1 WHERE username LIKE ?", P + "%");
        jdbcTemplate.update("DELETE FROM ai_operation_audit WHERE target_type = 'USER' AND target_id IN "
                + "(SELECT id FROM sys_user WHERE username LIKE ?)", P + "%");
        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username LIKE ?)", P + "%");
        jdbcTemplate.update("DELETE FROM sys_user WHERE username LIKE ?", P + "%");
        jdbcTemplate.update("DELETE FROM ai_operation_audit WHERE target_type = 'ORG' AND target_id IN "
                + "(SELECT id FROM sys_org WHERE org_code LIKE ?)", P + "%");
        jdbcTemplate.update("DELETE FROM sys_org WHERE org_code LIKE ?", P + "%");
        jdbcTemplate.update("DELETE FROM ai_operation_secret WHERE proposal_id = ?", 987654321L);
    }

    // ==================================================================
    // LD-T8：防账号枚举（AC-7）
    // ==================================================================

    @Test
    @DisplayName("LD-T8 已删除用户登录返回与密码错误**完全相同**的提示（LD-05 / AC-7）")
    void deletedUserLoginIsIndistinguishableFromWrongPassword() {
        String username = P + "login";
        String rawPassword = "Test@123456";
        jdbcTemplate.update("INSERT INTO sys_user (username, password, real_name, dept_id, status) "
                + "VALUES (?, ?, '夹具', ?, 1)", username, passwordEncoder.encode(rawPassword), deptId());
        long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = ?", Long.class, username);

        // ① 正常可登录
        assertThat(authService.login(loginRequest(username, rawPassword)).token()).isNotBlank();

        // ② 密码错误：作为"提示文案基准"
        BizException wrongPassword = catchThrowableOfType(
                () -> authService.login(loginRequest(username, "Wrong@123456")), BizException.class);
        assertThat(wrongPassword).isNotNull();
        assertThat(wrongPassword.getCode()).isEqualTo(ResultCode.LOGIN_FAILED.code());

        // ③ 已停用仍返回 ACCOUNT_DISABLED（LD-05a：停用是给用户的明确信号，现状不变）
        jdbcTemplate.update("UPDATE sys_user SET status = 0 WHERE id = ?", userId);
        BizException disabled = catchThrowableOfType(
                () -> authService.login(loginRequest(username, rawPassword)), BizException.class);
        assertThat(disabled.getCode()).as("停用与删除必须可区分（LD-05a）")
                .isEqualTo(ResultCode.ACCOUNT_DISABLED.code());
        jdbcTemplate.update("UPDATE sys_user SET status = 1 WHERE id = ?", userId);

        // ④ 逻辑删除后：提示必须与密码错误逐字相同——否则可用它探测账号是否存在
        userService.delete(userId, adminScope(), adminId());
        BizException deleted = catchThrowableOfType(
                () -> authService.login(loginRequest(username, rawPassword)), BizException.class);
        assertThat(deleted).isNotNull();
        assertThat(deleted.getCode()).as("已删除用户必须返回 LOGIN_FAILED").isEqualTo(ResultCode.LOGIN_FAILED.code());
        assertThat(deleted.getMessage()).as("提示文案必须与密码错误完全一致（防账号枚举）")
                .isEqualTo(wrongPassword.getMessage());

        // ⑤ 登录链路的第二道保险：selectByUsername 必须只查未删除行（LD-05b）
        assertThat(userService.getEntityByUsername(username)).as("复合唯一键后不能再依赖\"用户名唯一\"").isNull();
    }

    // ==================================================================
    // LD-T12：删除 / 恢复都落审计（AC-6）
    // ==================================================================

    @Test
    @DisplayName("LD-T12 页面删除与恢复都落 source=WEB 审计，action=DELETE/RESTORE，快照为 isDeleted")
    void deleteAndRestoreAreAudited() {
        // 注意：夹具机构必须用 level=2 挂在总部下，**不能**再建一个 level=1 的机构——
        // 否则本类与其它类的 "SELECT id FROM sys_org WHERE org_level = 1" 会返回 2 行
        long hq = headquartersId();
        OrgDto.CreateRequest request = new OrgDto.CreateRequest();
        request.setOrgCode(P + "audit");
        request.setOrgName("LD-T12 审计夹具机构");
        request.setRegionCode("000000");
        request.setOrgLevel(2);
        request.setParentId(hq);
        OrgVO org = orgService.create(request, adminScope());

        orgService.delete(org.getId(), adminScope(), adminId());
        Map<String, Object> deleteAudit = latestAudit("ORG", org.getId());
        assertThat(deleteAudit.get("source")).as("页面删除必须是 WEB（AC-6）").isEqualTo("WEB");
        assertThat(deleteAudit.get("action")).isEqualTo("DELETE");
        assertThat(deleteAudit.get("result")).isEqualTo("SUCCESS");
        assertThat(deleteAudit.get("operator_username")).isEqualTo("admin");
        assertThat(String.valueOf(deleteAudit.get("before_value"))).contains("\"isDeleted\":0");
        assertThat(String.valueOf(deleteAudit.get("after_value"))).contains("\"isDeleted\":1");
        assertThat(deleteAudit.get("changed_fields")).isEqualTo("isDeleted");

        orgService.restore(org.getId(), adminId());
        Map<String, Object> restoreAudit = latestAudit("ORG", org.getId());
        assertThat(restoreAudit.get("source")).isEqualTo("WEB");
        assertThat(restoreAudit.get("action")).isEqualTo("RESTORE");
        assertThat(String.valueOf(restoreAudit.get("before_value"))).contains("\"isDeleted\":1");
        assertThat(String.valueOf(restoreAudit.get("after_value"))).contains("\"isDeleted\":0");
    }

    // ==================================================================
    // AC-8：被引用的机构删除被拒绝并给出引用数
    // ==================================================================

    @Test
    @DisplayName("AC-8 被订单引用的机构删除被拒绝，错误信息带引用条数")
    void referencedOrgCannotBeDeleted() {
        Long orgId = jdbcTemplate.queryForObject(
                "SELECT org_id FROM tender_order GROUP BY org_id LIMIT 1", Long.class);
        long orders = jdbcTemplate.queryForObject(
                "SELECT (SELECT COUNT(*) FROM tender_order WHERE org_id = ?) "
                        + "+ (SELECT COUNT(*) FROM performance_order WHERE org_id = ?)",
                Long.class, orgId, orgId);

        assertThatThrownBy(() -> orgService.delete(orgId, adminScope(), adminId()))
                .as("删除比停用更严格：被引用即拒绝（设计 §6.2）")
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能删除")
                .hasMessageContaining(String.valueOf(orders));

        Integer isDeleted = jdbcTemplate.queryForObject(
                "SELECT is_deleted FROM sys_org WHERE id = ?", Integer.class, orgId);
        assertThat(isDeleted).as("被拒绝的删除不得留下任何痕迹").isZero();
    }

    // ==================================================================
    // LD-T21：ai_operation_secret 保持物理删除（LD-EX-01）
    // ==================================================================

    @Test
    @DisplayName("LD-T21 提案密文表保持物理删除：purge 后行物理消失，且不存在软删除入口")
    void secretStoreKeepsPhysicalDelete() {
        long proposalId = 987654321L;
        String plainJson = "{\"phone\":\"13800000000\"}";
        proposalSecretStore.save(proposalId, plainJson, LocalDateTime.now().plusMinutes(15));

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_operation_secret WHERE proposal_id = ?", Integer.class, proposalId);
        assertThat(rows).as("密文应已暂存").isEqualTo(1);

        String cipherText = jdbcTemplate.queryForObject(
                "SELECT cipher_text FROM ai_operation_secret WHERE proposal_id = ?", String.class, proposalId);
        assertThat(cipherText).as("落库必须是密文，不得出现明文").doesNotContain("13800000000", "phone");

        proposalSecretStore.purge(proposalId);
        Integer afterPurge = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_operation_secret WHERE proposal_id = ?", Integer.class, proposalId);
        assertThat(afterPurge).as("必须**物理删除**（COUNT=0），而不是 is_deleted=1 的逻辑删除")
                .isZero();

        // 回归护栏：不允许后续给该表加"软删除"入口——那会让手机号密文从 15 分钟驻留变成永久
        assertThat(ProposalSecretStore.class.getDeclaredMethods())
                .as("密文表不得出现 softDelete / logicalDelete 之类的方法")
                .noneMatch(m -> m.getName().toLowerCase().contains("soft")
                        || m.getName().toLowerCase().contains("logical"));

        Integer columns = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_operation_secret'
                  AND COLUMN_NAME IN ('is_deleted', 'deleted_at', 'deleted_by')
                """, Integer.class);
        assertThat(columns).as("该表不得有逻辑删除字段（LD-EX-01 / 设计 §7.3a）").isZero();
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private static LoginRequest loginRequest(String username, String password) {
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        return request;
    }

    private Map<String, Object> latestAudit(String targetType, long targetId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT source, action, result, operator_username, before_value, after_value, changed_fields
                FROM ai_operation_audit
                WHERE target_type = ? AND target_id = ?
                ORDER BY id DESC LIMIT 1
                """, targetType, targetId);
        assertThat(rows).as("目标 %s:%s 缺少审计记录（写路径漏接审计）", targetType, targetId).isNotEmpty();
        return rows.get(0);
    }

    private DataScope adminScope() {
        return dataScopeService.resolve(adminId(), List.of(Roles.ADMIN));
    }

    private long adminId() {
        return jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = 'admin'", Long.class);
    }

    /** 一个真实存在的部门 id（用户夹具用；{@code dept_id} 已是 NOT NULL）。 */
    private long deptId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM sys_department WHERE is_deleted = 0 ORDER BY id LIMIT 1", Long.class);
    }

    private long headquartersId() {
        return jdbcTemplate.queryForObject("SELECT id FROM sys_org WHERE org_level = 1", Long.class);
    }
}
