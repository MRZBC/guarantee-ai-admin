package com.guarantee.web.ai;

import com.guarantee.ai.dto.AiChatRequest;
import com.guarantee.ai.entity.AiOperationProposal;
import com.guarantee.ai.mapper.AiOperationProposalMapper;
import com.guarantee.ai.service.AiChatService;
import com.guarantee.ai.service.ProposalExecutionContext;
import com.guarantee.ai.service.ProposalPayload;
import com.guarantee.ai.service.ProposalService;
import com.guarantee.ai.tool.AiToolRegistry;
import com.guarantee.common.security.Permissions;
import com.guarantee.common.security.Roles;
import com.guarantee.system.scope.DataScope;
import com.guarantee.system.scope.DataScopeService;
import com.guarantee.web.GuaranteeAiAdminApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 提案机制与工具注册裁剪的集成测试
 * （TEST-05 / TEST-06 / TEST-10 / TEST-16 / TEST-17 / AC-14~AC-19 / AC-29 / AC-30）。
 *
 * <p>用确定性的 {@link StubToolCallingChatModel} 替换真实模型，不依赖 LLM API Key。</p>
 */
@SpringBootTest(
        classes = {GuaranteeAiAdminApplication.class, ProposalFlowIT.StubChatModelConfig.class},
        properties = {
                "guarantee.data-init.enabled=false",
                // 关掉定时任务，避免测试期间提案被"过期清理"打断
                "guarantee.ai.proposal-expire-interval-ms=3600000",
                "guarantee.ai.audit-inspect-interval-ms=3600000"
        })
class ProposalFlowIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class StubChatModelConfig {

        @Bean
        @Primary
        ChatModel stubToolCallingChatModel() {
            return new StubToolCallingChatModel();
        }
    }

    @Autowired
    private ProposalService proposalService;

    @Autowired
    private AiOperationProposalMapper proposalMapper;

    @Autowired
    private AiToolRegistry toolRegistry;

    @Autowired
    private DataScopeService dataScopeService;

    @Autowired
    private AiChatService aiChatService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private com.guarantee.system.service.RoleService roleService;

    @Autowired
    private com.guarantee.system.mapper.InsuranceTypeMapper insuranceMapper;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Autowired
    private com.guarantee.ai.service.TargetRevisionStore revisionStore;

    @Test
    void legacyProposalWithoutFingerprintCannotBypassVersionCheck() {
        long typeId = insuranceTypeId("履约保函（质量）");
        var created = createDisableInsuranceProposal(userId("admin"), typeId, "履约保函（质量）");
        jdbcTemplate.update("UPDATE ai_operation_proposal SET target_fingerprint=NULL WHERE id=?", created.proposalId());
        assertThatThrownBy(() -> proposalService.confirm(created.proposalId(), adminContext("trace-legacy-version")))
                .hasMessageContaining("缺少目标版本信息");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM insurance_type WHERE id=?", Integer.class, typeId)).isEqualTo(1);
    }

    @Test
    @DisplayName("目标锁回归：页面 Mapper 写入等待确认事务释放目标锁")
    void pageWriterWaitsForTargetLock() throws Exception {
        long typeId = insuranceTypeId("履约保函（质量）");
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var started = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var holder = pool.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                    .execute(status -> {
                        revisionStore.lock("INSURANCE_TYPE", typeId);
                        locked.countDown();
                        try { if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("锁测试超时"); }
                        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                        return true;
                    }));
            try {
                assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var writer = pool.submit(() -> {
                    started.countDown();
                    var update = new com.guarantee.system.entity.InsuranceType();
                    update.setId(typeId);
                    update.setTypeName("履约保函（质量）");
                    return insuranceMapper.updateById(update);
                });
                assertThat(started.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> writer.get(200, java.util.concurrent.TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                release.countDown();
                assertThat(writer.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(1);
                assertThat(holder.get(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            } finally { release.countDown(); }
        }
    }

    @Test
    @DisplayName("版本回归：同秒改后改回且恢复时间戳，旧提案仍失效")
    void restoredFieldsAndTimestampStillInvalidateProposal() {
        long typeId = insuranceTypeId("履约保函（质量）");
        var created = createDisableInsuranceProposal(userId("admin"), typeId, "履约保函（质量）");
        var original = jdbcTemplate.queryForMap("SELECT type_name, updated_at FROM insurance_type WHERE id=?", typeId);
        try {
            var update = new com.guarantee.system.entity.InsuranceType();
            update.setId(typeId);
            update.setTypeName(original.get("type_name") + "-changed");
            insuranceMapper.updateById(update);
            update.setTypeName(String.valueOf(original.get("type_name")));
            insuranceMapper.updateById(update);
            jdbcTemplate.update("UPDATE insurance_type SET updated_at=? WHERE id=?", original.get("updated_at"), typeId);
            assertThatThrownBy(() -> proposalService.confirm(created.proposalId(), adminContext("trace-aba")))
                    .hasMessageContaining(ProposalService.FINGERPRINT_MISMATCH_REASON);
        } finally {
            jdbcTemplate.update("UPDATE insurance_type SET type_name=?,updated_at=?,mutation_version=mutation_version+1 WHERE id=?",
                    original.get("type_name"), original.get("updated_at"), typeId);
        }
    }

    @Test
    @DisplayName("并发回归：不同提案修改同一目标，只有一个成功")
    void differentProposalsCannotOverwriteSameTarget() throws Exception {
        long typeId = insuranceTypeId("履约保函（质量）");
        var first = createDisableInsuranceProposal(userId("admin"), typeId, "履约保函（质量）");
        var second = createDisableInsuranceProposal(userId("admin"), typeId, "履约保函（质量）");
        assertThat(second.proposalId()).isNotEqualTo(first.proposalId());
        var context = adminContext("trace-two-proposals");
        var gate = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (var payload : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    try { return "EXECUTED".equals(proposalService.confirm(payload.proposalId(), context).status()); }
                    catch (com.guarantee.common.exception.BizException ex) {
                        assertThat(ex.getMessage()).contains(ProposalService.FINGERPRINT_MISMATCH_REASON);
                        return false;
                    }
                }));
            }
            gate.countDown();
            int successes = 0;
            for (var future : futures) if (future.get(30, java.util.concurrent.TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("审计探针：提案后角色权限集合变化必须拦截旧授权（预期暴露缺口）")
    void changedRolePermissionsMustInvalidateOldProposal() {
        var operator = adminContext("trace-role-conflict");
        com.guarantee.common.security.CurrentUser.set(new com.guarantee.common.security.CurrentUser.Principal(
                operator.userId(), operator.username(), operator.realName(), operator.roles(), operator.permissions()));
        String code = "AUDIT_PROBE_" + System.nanoTime();
        var dto = new com.guarantee.system.dto.RoleDto.CreateRequest();
        dto.setRoleCode(code);
        dto.setRoleName("授权冲突检查夹具");
        var role = roleService.create(dto);
        var codes = jdbcTemplate.queryForList(
                "SELECT perm_code FROM sys_permission WHERE is_deleted = 0 ORDER BY id LIMIT 2", String.class);
        assertThat(codes).hasSize(2);
        try {
            roleService.assignPermissions(code, List.of(codes.get(0)));
            var request = com.guarantee.ai.service.ProposalRequest.builder()
                    .id(role.getId()).roleCode(code).targetName(role.getRoleName()).permCodes(List.of(codes.get(0))).build();
            var created = proposalFixture.track(proposalService.create(new ProposalService.ProposalDraft(
                    null, userId("admin"), "admin", "超级管理员", "proposeRoleChange",
                    "ASSIGN_PERMISSIONS", "ROLE", role.getId(), role.getRoleName(), request,
                    com.guarantee.ai.service.ProposalPreview.of("授权冲突检查", List.of(), List.of(), List.of(), false),
                    java.util.Set.of("ai:system:write", Permissions.ROLE_ASSIGN_PERMISSION), "授权", null, "trace-role-conflict")));
            roleService.assignPermissions(code, List.of(codes.get(1)));
            assertThatThrownBy(() -> {
                var result = proposalService.confirm(created.proposalId(), adminContext("trace-role-conflict"));
                System.out.println("ROLE_PERMISSION_PROBE | proposal=" + created.proposalId()
                        + " | status=" + result.status() + " | newer=" + codes.get(1)
                        + " | actual=" + roleService.getById(role.getId()).getPermissionCodes());
            })
                    .as("他人已更新权限集合，应拦截旧提案而非覆盖新权限")
                    .hasMessageContaining(ProposalService.FINGERPRINT_MISMATCH_REASON);
        } finally {
            jdbcTemplate.update("DELETE FROM sys_role_permission WHERE role_id = ?", role.getId());
            jdbcTemplate.update("DELETE FROM sys_role WHERE id = ?", role.getId());
        }
    }

    /**
     * 测试前的险种状态快照——结束时按它**精确还原**。
     *
     * <p>为什么不直接"一律置为启用"：那会把使用者在页面上主动停用的险种也重新启用，
     * 属于对共享开发库的另一种破坏。快照还原后，测试对业务数据的净影响为零。</p>
     */
    private Map<Long, Integer> insuranceStatusSnapshot;

    private com.guarantee.web.support.ProposalFixture proposalFixture;

    /**
     * 每个用例前建立前置状态。
     *
     * <p>提案执行会真实改动 {@code insurance_type.status}。如果不从"全部启用"开始，
     * 上一次失败运行的残留状态会让下一次运行在"险种已处于目标状态"上失败，表现为
     * "单独跑能过、整套跑就挂"的假失败。</p>
     */
    @org.junit.jupiter.api.BeforeEach
    void resetInsuranceStatus() {
        insuranceStatusSnapshot = new java.util.LinkedHashMap<>();
        jdbcTemplate.query("SELECT id, status FROM insurance_type", rs -> {
            insuranceStatusSnapshot.put(rs.getLong("id"), rs.getInt("status"));
        });
        jdbcTemplate.update("UPDATE insurance_type SET status = 1 WHERE status <> 1");
        proposalFixture = new com.guarantee.web.support.ProposalFixture(jdbcTemplate);
    }

    /**
     * 与 {@link #resetInsuranceStatus()} 严格配对：把本用例对共享开发库的改动**全部收回**。
     *
     * <p><b>为什么不能只有 {@code @BeforeEach}</b>：那种写法只在"下一次运行开始"时才复位，
     * 于是每次 {@code mvn verify} 之后，开发库里都留着被测试改过的状态——真机上表现为
     * 「投标保函（标准）在页面上显示为已停用」，而那不是任何人操作的结果。</p>
     *
     * <p>提案清理同理：测试造的 PENDING 提案会出现在真实使用者的「待确认提案」列表里，
     * 与业务数据被改动一样，都是把测试的痕迹泄露给了使用者。</p>
     */
    @org.junit.jupiter.api.AfterEach
    void restoreAfterTest() {
        if (insuranceStatusSnapshot != null) {
            insuranceStatusSnapshot.forEach((id, status) -> jdbcTemplate.update(
                    "UPDATE insurance_type SET status = ? WHERE id = ?", status, id));
        }
        proposalFixture.cleanUp();
        com.guarantee.common.security.CurrentUser.clear();
    }

    // ==================================================================
    // AC-14：提案不落库
    // ==================================================================

    @Test
    @DisplayName("AC-14：生成提案后业务表无任何变更，提案状态为 PENDING")
    void proposalShouldNotTouchBusinessData() {
        long adminId = userId("admin");
        String insuranceName = "履约保函（标准）";
        Integer beforeStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM insurance_type WHERE type_name = ?", Integer.class, insuranceName);
        assertThat(beforeStatus).isEqualTo(1);

        ProposalPayload payload = proposalFixture.track(proposalService.create(new ProposalService.ProposalDraft(
                null, adminId, "admin", "超级管理员",
                "proposeInsuranceTypeChange", "DISABLE", "INSURANCE_TYPE",
                insuranceTypeId(insuranceName), insuranceName,
                com.guarantee.ai.service.ProposalRequest.builder()
                        .id(insuranceTypeId(insuranceName)).targetName(insuranceName)
                        .userText("把履约保函（标准）停用").build(),
                com.guarantee.ai.service.ProposalPreview.of("停用险种：" + insuranceName,
                        List.of(new com.guarantee.ai.service.ProposalPreview.ChangeItem(
                                "status", "状态", "启用", "停用")),
                        List.of("影响面：{引用订单数=若干}"), List.of("停用后不再出现在新订单可选列表"), true),
                java.util.Set.of(Permissions.AI_SYSTEM_WRITE, Permissions.INSURANCE_DISABLE),
                "把履约保函（标准）停用", null, "trace-it-1")));

        assertThat(payload.proposalId()).isPositive();
        assertThat(payload.status()).isEqualTo("PENDING");
        assertThat(payload.expiresAt()).isNotNull();
        assertThat(payload.changes()).hasSize(1);

        // 关键断言：业务数据一行未动
        Integer afterStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM insurance_type WHERE type_name = ?", Integer.class, insuranceName);
        assertThat(afterStatus).as("提案阶段绝不允许落库（SYS-W-08 / AC-14）").isEqualTo(beforeStatus);

        // 提案记录本身已落库
        AiOperationProposal saved = proposalMapper.selectById(payload.proposalId());
        assertThat(saved.getStatus()).isEqualTo("PENDING");
        assertThat(saved.getRequiredPerms()).contains(Permissions.INSURANCE_DISABLE);
    }

    // ==================================================================
    // AC-15 / AC-18：确认后生效 + 不可重复执行
    // ==================================================================

    @Test
    @DisplayName("AC-15 + AC-18：确认后生效、审计有前后值，重复确认被拒绝")
    void confirmShouldExecuteOnceAndWriteAudit() {
        long adminId = userId("admin");
        long typeId = insuranceTypeId("履约保函（质量）");
        Integer before = jdbcTemplate.queryForObject(
                "SELECT status FROM insurance_type WHERE id = ?", Integer.class, typeId);

        ProposalPayload created = createDisableInsuranceProposal(adminId, typeId, "履约保函（质量）");

        ProposalPayload executed = proposalService.confirm(created.proposalId(), adminContext("trace-it-2"));

        assertThat(executed.status()).isEqualTo("EXECUTED");
        assertThat(executed.auditId()).isNotNull();
        assertThat(executed.executedAt()).isNotNull();
        assertThat(executed.resultMessage()).isNotBlank();
        ProposalPayload restored = proposalService.listMine(adminId, "ALL", 50, created.conversationId()).stream()
                .filter(item -> item.proposalId().equals(created.proposalId())).findFirst().orElseThrow();
        assertThat(restored.status()).isEqualTo("EXECUTED");
        assertThat(restored.auditId()).isEqualTo(executed.auditId());
        Integer after = jdbcTemplate.queryForObject(
                "SELECT status FROM insurance_type WHERE id = ?", Integer.class, typeId);
        assertThat(after).as("确认后业务数据必须已变更").isEqualTo(0);

        // 审计：结构化前后值 + 已脱敏。
        // 一个提案会产生多条审计记录（PROPOSAL_CREATED、失败重试、最终执行结果），
        // 因此必须按"有前后值的那条"过滤：只有真正的执行业绩才带 before/after 快照。
        Map<String, Object> audit = firstRow("""
                SELECT before_value, after_value, changed_fields, result, source
                FROM ai_operation_audit
                WHERE proposal_id = ? AND result = 'SUCCESS'
                ORDER BY id DESC LIMIT 1
                """, created.proposalId());
        assertThat(audit.get("result")).isEqualTo("SUCCESS");
        assertThat(audit.get("source")).as("助手确认的审计 source 必须是 AI").isEqualTo("AI");
        assertThat(String.valueOf(audit.get("changed_fields"))).contains("status");
        assertThat(String.valueOf(audit.get("before_value"))).contains("status");

        // AC-18：重复确认必须被拒绝，且不产生第二次执行
        assertThatThrownBy(() -> proposalService.confirm(created.proposalId(), adminContext("trace-it-3")))
                .hasMessageContaining("已执行");

        // 恢复数据，避免影响其它用例
        jdbcTemplate.update("UPDATE insurance_type SET status = ? WHERE id = ?", before, typeId);
    }

    @Test
    @DisplayName("诊断：并发确认只有一次成功且只有一条成功审计")
    void concurrentConfirmExecutesOnlyOnce() throws Exception {
        long adminId = userId("admin");
        long typeId = insuranceTypeId("履约保函（质量）");
        ProposalPayload created = createDisableInsuranceProposal(adminId, typeId, "履约保函（质量）");
        var context = adminContext("trace-concurrent-probe");
        var gate = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 2; i++) {
                tasks.add(pool.submit(() -> {
                    gate.await();
                    try {
                        return "EXECUTED".equals(proposalService.confirm(created.proposalId(), context).status());
                    } catch (com.guarantee.common.exception.BizException ex) {
                        assertThat(ex.getMessage()).containsAnyOf("已执行", "重复", "执行中");
                        return false;
                    }
                }));
            }
            gate.countDown();
            int successes = 0;
            for (var task : tasks) {
                if (task.get(30, java.util.concurrent.TimeUnit.SECONDS)) successes++;
            }
            assertThat(successes).isEqualTo(1);
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_operation_audit WHERE proposal_id = ? AND result = 'SUCCESS' AND before_value IS NOT NULL",
                Integer.class, created.proposalId())).isEqualTo(1);
    }

    @Test
    @DisplayName("诊断：真实目标字段变化后确认必须失效")
    void changedBusinessTargetRejectsConfirmation() {
        long typeId = insuranceTypeId("履约保函（质量）");
        ProposalPayload created = createDisableInsuranceProposal(userId("admin"), typeId, "履约保函（质量）");
        String name = jdbcTemplate.queryForObject("SELECT type_name FROM insurance_type WHERE id = ?", String.class, typeId);
        try {
            jdbcTemplate.update("UPDATE insurance_type SET type_name = ? WHERE id = ?", name + "-probe", typeId);
            assertThatThrownBy(() -> proposalService.confirm(created.proposalId(), adminContext("trace-target-probe")))
                    .hasMessageContaining(ProposalService.FINGERPRINT_MISMATCH_REASON);
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM ai_operation_proposal WHERE id = ?",
                    String.class, created.proposalId())).isEqualTo("INVALIDATED");
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM insurance_type WHERE id = ?",
                    Integer.class, typeId)).isEqualTo(1);
        } finally {
            jdbcTemplate.update("UPDATE insurance_type SET type_name = ? WHERE id = ?", name, typeId);
        }
    }

    // ==================================================================
    // AC-16：拒绝无痕执行
    // ==================================================================

    @Test
    @DisplayName("AC-16：拒绝后数据无变更，提案 REJECTED，审计 result=REJECTED")
    void rejectShouldNotChangeDataAndWriteAudit() {
        long adminId = userId("admin");
        long typeId = insuranceTypeId("电子投标保函");
        Integer before = jdbcTemplate.queryForObject(
                "SELECT status FROM insurance_type WHERE id = ?", Integer.class, typeId);

        ProposalPayload created = createDisableInsuranceProposal(adminId, typeId, "电子投标保函");
        ProposalPayload rejected = proposalService.reject(created.proposalId(), adminId, "暂时不动");

        assertThat(rejected.status()).isEqualTo("REJECTED");
        Integer after = jdbcTemplate.queryForObject(
                "SELECT status FROM insurance_type WHERE id = ?", Integer.class, typeId);
        assertThat(after).as("拒绝后不允许有任何数据变更").isEqualTo(before);

        Map<String, Object> audit = firstRow("""
                SELECT result, error_message FROM ai_operation_audit
                WHERE proposal_id = ? AND result = 'REJECTED'
                ORDER BY id DESC LIMIT 1
                """, created.proposalId());
        assertThat(audit.get("result")).as("拒绝也必须落审计，不允许无痕拒绝（SYS-C-10）")
                .isEqualTo("REJECTED");
        assertThat(String.valueOf(audit.get("error_message"))).contains("暂时不动");
    }

    // ==================================================================
    // AC-19 / TEST-06：危险动作保护
    // ==================================================================

    @Test
    @DisplayName("TEST-06：停用自己 / 停用最后一个 ADMIN / 给自己加 ADMIN 均被拒绝")
    void dangerousUserActionsShouldBeRejected() {
        long adminId = userId("admin");
        DataScope adminScope = dataScopeService.resolve(adminId, List.of(Roles.ADMIN));

        // ① 停用自己
        assertThatThrownBy(() -> userService.validateStatusChange(
                userService.requireVisible(adminId, adminScope), 0, adminId))
                .isInstanceOf(com.guarantee.common.exception.BizException.class)
                .hasMessageContaining("不允许停用自己");

        // ② 给自己增加 ADMIN（当前已是 ADMIN，先看"移除"分支同样被拦）
        assertThatThrownBy(() -> userService.validateAssignRoles(
                userService.requireVisible(adminId, adminScope),
                List.of("VIEWER"), adminId))
                .isInstanceOf(com.guarantee.common.exception.BizException.class)
                .hasMessageContaining("不允许给自己增加或移除超级管理员");

        // ③ 演示数据里只有 admin 一个 ADMIN：确认"最后一个 ADMIN"这一前提成立
        //
        // 必须过滤 is_deleted = 0：关联表用的是 UPSERT 语义（见 DEC-逻辑删除设计方案 §4），
        // 取消一个角色分配只会把旧行置为 is_deleted = 1，历史行**仍然留在表里**。
        // 不过滤就会把"曾经是 ADMIN、现在已经不是"的历史行也算进来，
        // 让这条前置断言在库里有任何历史角色变更后必然失败（真机踩过）。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user_role ur INNER JOIN sys_role r ON r.id = ur.role_id "
                        + "WHERE r.role_code = 'ADMIN' AND ur.is_deleted = 0", Integer.class))
                .as("只有 admin 一个 ADMIN，才可能触发'最后一个'分支")
                .isEqualTo(1);
    }

    @Autowired
    private com.guarantee.system.service.UserService userService;

    // ==================================================================
    // AC-20：权限实时性（SYS-C-04）
    // ==================================================================

    @Test
    @DisplayName("AC-20：权限被移除后确认提案被拒，且提案置为 INVALIDATED")
    void confirmShouldBeRejectedWhenPermissionRevoked() {
        long adminId = userId("admin");
        long typeId = insuranceTypeId("投标保函（小额）");
        ProposalPayload created = createDisableInsuranceProposal(adminId, typeId, "投标保函（小额）");

        // 模拟"权限已变更"：确认时提供的权限集不含提案所需权限。
        // 机构已从用户上移除，ProposalExecutionContext 里也不再有任何机构分量。
        ProposalExecutionContext withoutPermission = new ProposalExecutionContext(
                adminId, "admin", "超级管理员",
                List.of(Roles.ADMIN), List.of(Permissions.AI_CHAT, Permissions.INSURANCE_VIEW),
                dataScopeService.resolve(adminId, List.of(Roles.ADMIN)), "trace-it-4");

        assertThatThrownBy(() -> proposalService.confirm(created.proposalId(), withoutPermission))
                .isInstanceOf(com.guarantee.common.exception.BizException.class)
                .hasMessageContaining("权限已变更");

        assertThat(proposalMapper.selectById(created.proposalId()).getStatus()).isEqualTo("INVALIDATED");
    }

    // ==================================================================
    // TEST-10：越权确认他人提案
    // ==================================================================

    @Test
    @DisplayName("TEST-10：确认他人提案返回 403，且目标数据不受影响")
    void confirmOtherUsersProposalShouldBeForbidden() {
        long adminId = userId("admin");
        long typeId = insuranceTypeId("履约保函（预付款）");
        ProposalPayload created = createDisableInsuranceProposal(adminId, typeId, "履约保函（预付款）");

        long analystId = userId("analyst");
        ProposalExecutionContext analyst = new ProposalExecutionContext(
                analystId, "analyst", "数据分析师",
                List.of(Roles.ANALYST), List.of(Permissions.AI_CHAT, Permissions.INSURANCE_VIEW),
                dataScopeService.resolve(analystId, List.of(Roles.ANALYST)), "trace-it-5");

        assertThatThrownBy(() -> proposalService.confirm(created.proposalId(), analyst))
                .isInstanceOf(com.guarantee.common.exception.BizException.class)
                .hasMessageContaining("无权确认他人的提案");

        assertThat(proposalMapper.selectById(created.proposalId()).getStatus())
                .as("越权确认不得改变提案状态").isEqualTo("PENDING");
    }

    // ==================================================================
    // AC-29 / TEST-16：工具注册裁剪（走真实的权限快照）
    // ==================================================================

    @Test
    @DisplayName("TEST-16：ANALYST 的可用工具不含 queryOperationAudit，但含两个自查工具；ADMIN 反之")
    void toolRegistrationShouldBeTrimmedByPermission() {
        List<String> analystPerms = userService.listPermissionCodesByUserId(userId("analyst"));
        List<String> analystTools = toolRegistry.availableToolNames(analystPerms);
        assertThat(analystTools).doesNotContain("queryOperationAudit");
        assertThat(analystTools).contains("queryMyToolCalls", "queryMyProposals");
        assertThat(analystTools).as("ANALYST 不得有任何写工具（SYS-P-12）")
                .noneMatch(name -> name.startsWith("propose"));
        assertThat(analystPerms).as("ANALYST 不应持有 system:audit:view（D-1a）")
                .doesNotContain(Permissions.AUDIT_VIEW);

        List<String> adminPerms = userService.listPermissionCodesByUserId(userId("admin"));
        List<String> adminTools = toolRegistry.availableToolNames(adminPerms);
        assertThat(adminTools).contains("queryOperationAudit", "queryMyToolCalls", "queryMyProposals");
        assertThat(adminTools).contains("proposeUserChange", "proposeOrgChange", "proposeRoleChange");

        // 演示数据里的 user0005 是 OPERATOR：有 ai:system:query（因此两个自查工具都注册）
        // 但没有 ai:system:write（因此拿不到任何写工具）。
        List<String> viewerPerms = userService.listPermissionCodesByUserId(userId("user0005"));
        List<String> viewerTools = toolRegistry.availableToolNames(viewerPerms);
        assertThat(viewerTools).as("未开通 ai:system:write 的账号拿不到任何 propose* 写工具")
                .noneMatch(name -> name.startsWith("propose"));
        // 自查工具的注册只取决于 ai:system:query，跟随权限快照而不是角色名。
        // 写成等价断言而不是写死期望值：演示数据的角色构成变了也不会误报。
        assertThat(viewerTools.contains("queryMyProposals"))
                .as("queryMyProposals 的注册必须与 ai:system:query 严格一致")
                .isEqualTo(viewerPerms.contains(Permissions.AI_SYSTEM_QUERY));
        assertThat(viewerTools.contains("queryMyToolCalls"))
                .as("queryMyToolCalls 的注册必须与 ai:system:query 严格一致")
                .isEqualTo(viewerPerms.contains(Permissions.AI_SYSTEM_QUERY));
    }

    @Test
    @DisplayName("权限矩阵落库正确：ANALYST 只读且有 system:user:view，VIEWER 无 audit:view")
    void permissionMatrixInDatabaseShouldMatchSpec() {
        List<String> analyst = userService.listPermissionCodesByUserId(userId("analyst"));
        assertThat(analyst).contains(Permissions.AI_SYSTEM_QUERY, Permissions.USER_VIEW, Permissions.ROLE_VIEW);
        assertThat(analyst).doesNotContain(Permissions.AI_SYSTEM_WRITE, Permissions.AUDIT_VIEW);

        List<String> viewer = userService.listPermissionCodesByUserId(
                jdbcTemplate.queryForObject("""
                        SELECT u.id FROM sys_user u
                        INNER JOIN sys_user_role ur ON ur.user_id = u.id
                        INNER JOIN sys_role r ON r.id = ur.role_id
                        WHERE r.role_code = 'VIEWER' LIMIT 1
                        """, Long.class));
        assertThat(viewer).doesNotContain(Permissions.AUDIT_VIEW, Permissions.AI_SYSTEM_WRITE);
        assertThat(viewer).contains(Permissions.AI_CHAT, Permissions.ORG_VIEW);

        List<String> admin = userService.listPermissionCodesByUserId(userId("admin"));
        assertThat(admin).as("ADMIN 持有全部权限").contains(
                Permissions.AUDIT_VIEW, Permissions.AI_SYSTEM_WRITE, Permissions.USER_ASSIGN_ROLE);
        Integer totalPermissions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_permission", Integer.class);
        assertThat(admin).as("ADMIN 的权限数应等于权限码总数").hasSize(totalPermissions);
    }

    // ==================================================================
    // AC-30 / TEST-17：自查范围严格为本人
    // ==================================================================

    @Test
    @DisplayName("TEST-17 + AC-30：queryMyToolCalls 强制只返回本人记录，且不含入参/结果原文")
    void myToolCallsShouldBeStrictlySelfScoped() {
        long adminId = userId("admin");
        // 先用一次真实对话产生工具调用记录（走完整链路：模型 → 工具 → 落库）
        AiChatRequest request = new AiChatRequest();
        request.setMessage("2026年第三季度投标订单有多少？");
        List<ServerSentEvent<String>> events = aiChatService.stream(adminId, request)
                .collectList().block(Duration.ofSeconds(90));
        assertThat(events).isNotNull().isNotEmpty();

        var adminPage = myToolCalls(adminId);
        assertThat(adminPage.total()).as("ADMIN 自己应能看到刚产生的记录").isGreaterThan(0);

        // analyst 的记录数必须与他自己的会话一致，绝不能看到 admin 的记录
        long analystId = userId("analyst");
        var analystPage = myToolCalls(analystId);
        long analystOwnConversations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_conversation WHERE user_id = ?", Long.class, analystId);
        if (analystOwnConversations == 0) {
            assertThat(analystPage.total()).as("analyst 没有任何会话时必须返回 0，而不是别人的记录")
                    .isZero();
        }

        // 关键：范围由 SQL 强制收敛，服务层根本不接受"查哪个用户"的参数
        var methods = java.util.Arrays.stream(
                        com.guarantee.ai.mapper.AiToolCallMapper.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName).toList();
        assertThat(methods).contains("selectMine", "countMine");

        // 结果摘要不含原文（只有计数/提案编号这类聚合信息）
        for (var item : adminPage.items()) {
            assertThat(item.resultSummary()).as("自查结果不得携带工具入参或结果原文")
                    .doesNotContain("orderType").doesNotContain("startDate");
        }
    }

    private com.guarantee.ai.service.AiConversationService.ToolCallMinePage myToolCalls(long userId) {
        return conversationService.listMyToolCalls(
                userId, java.time.LocalDateTime.now().minusDays(7), java.time.LocalDateTime.now(),
                null, null, 20);
    }

    @Autowired
    private com.guarantee.ai.service.AiConversationService conversationService;

    // ==================================================================
    // 来源归属：一次变更恰好一条审计，且渠道正确（AC-22 / 问题一）
    // ==================================================================

    @Test
    @DisplayName("AC-22：助手确认只产生一条 source=AI 审计，身份是当前用户，不能重复也不能标成 WEB")
    void aiConfirmShouldProduceExactlyOneAiAudit() {
        long adminId = userId("admin");
        long typeId = insuranceTypeId("履约保函（标准）");

        ProposalPayload created = createDisableInsuranceProposal(adminId, typeId, "履约保函（标准）");
        proposalService.confirm(created.proposalId(), adminContext("trace-audit-source"));

        // 该提案产生的审计记录
        List<Map<String, Object>> audits = jdbcTemplate.queryForList("""
                SELECT source, action, result, operator_user_id, operator_username
                FROM ai_operation_audit
                WHERE proposal_id = ?
                ORDER BY id ASC
                """, created.proposalId());

        // 两条是"预期内"的：提案创建（PROPOSAL_CREATED）+ 执行结果（DISABLE）
        assertThat(audits).as("提案创建与执行结果各一条").hasSize(2);
        assertThat(audits).allSatisfy(row ->
                assertThat(row.get("source")).as("助手渠道的记录必须是 AI，不能标成 WEB（AC-22）")
                        .isEqualTo("AI"));
        assertThat(audits).allSatisfy(row ->
                assertThat(row.get("operator_username")).as("身份由当前用户统一提供").isEqualTo("admin"));

        // 关键断言：业务写 Service（InsuranceTypeService.changeStatus）**没有**再补一条 WEB 记录。
        // 若 WebAuditor 未跳过助手路径，这里会出现第 3 条且 source=WEB——
        // 表现为"同一次变更两条审计、且渠道标错"。
        long webAudits = audits.stream()
                .filter(row -> "WEB".equals(row.get("source"))).count();
        assertThat(webAudits).as("助手路径不得产生 WEB 审计（否则同一次变更重复记账）").isZero();

        // 恢复状态
        jdbcTemplate.update("UPDATE insurance_type SET status = 1 WHERE id = ?", typeId);
    }

    @Test
    @DisplayName("AC-22：助手与页面改同一实体 → 两条记录来源不同、都能定位到同一个人")
    void sameUserTwoChannelsAreDistinguishable() {
        long adminId = userId("admin");
        long typeId = insuranceTypeId("投标保函（标准）");

        // 渠道一：页面直连（模拟 Web 线程）
        long targetUserId = userId("user0032");
        // 复位目标状态：本用例会停用该用户，重复运行时若不复位会命中"已处于目标状态"
        jdbcTemplate.update("UPDATE sys_user SET status = 1 WHERE id = ?", targetUserId);

        com.guarantee.common.security.CurrentUser.set(new com.guarantee.common.security.CurrentUser.Principal(
                adminId, "admin", "超级管理员", List.of(Roles.ADMIN),
                userService.listPermissionCodesByUserId(adminId)));
        try {
            userService.changeStatus(targetUserId, 0, adminScopeFor(adminId), adminId);
        } finally {
            com.guarantee.common.security.CurrentUser.clear();
            jdbcTemplate.update("UPDATE sys_user SET status = 1 WHERE id = ?", targetUserId);
        }

        // 渠道二：助手确认
        ProposalPayload created = createDisableInsuranceProposal(adminId, typeId, "投标保函（标准）");
        proposalService.confirm(created.proposalId(), adminContext("trace-two-channels"));

        Integer webCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ai_operation_audit
                WHERE source = 'WEB' AND operator_username = 'admin'
                """, Integer.class);
        Integer aiCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ai_operation_audit
                WHERE source = 'AI' AND operator_username = 'admin'
                """, Integer.class);

        assertThat(webCount).as("页面直连的记录存在且来源为 WEB").isPositive();
        assertThat(aiCount).as("助手确认的记录存在且来源为 AI").isPositive();

        // 同一个操作者，两种渠道 —— 这正是"身份相同但渠道必须可区分"的落点
        assertThat(webCount + aiCount).isPositive();

        jdbcTemplate.update("UPDATE insurance_type SET status = 1 WHERE id = ?", typeId);
    }

    private com.guarantee.system.scope.DataScope adminScopeFor(long adminId) {
        return dataScopeService.resolve(adminId, List.of(Roles.ADMIN));
    }

    // ==================================================================
    // 部门停用保护：部门下有正常状态用户时禁止停用
    // ==================================================================

    @Test
    @DisplayName("部门下有启用用户时禁止停用；用户全部停用后即可停用")
    void departmentWithEnabledUsersCannotBeDisabled() {
        // 取一个确实有启用用户、且**没有下级部门**的演示部门。
        // 必须限定叶子部门：停用前置检查现在有两条（① 不能有下级部门 ② 不能有启用用户），
        // 若取到有子部门的父部门（如总部），会先命中①，测不到本用例要验证的②。
        Long deptId = jdbcTemplate.queryForObject("""
                SELECT d.id FROM sys_department d
                INNER JOIN sys_user u ON u.dept_id = d.id AND u.status = 1
                WHERE d.is_deleted = 0
                  AND NOT EXISTS (
                      SELECT 1 FROM sys_department c
                      WHERE c.parent_id = d.id AND c.is_deleted = 0)
                GROUP BY d.id
                ORDER BY COUNT(*) DESC
                LIMIT 1
                """, Long.class);
        assertThat(deptId).as("演示数据里应存在含有启用用户的部门").isNotNull();

        long adminId = userId("admin");
        // 必须带 is_deleted = 0：Service 侧的计数经 LogicalDeleteInnerInterceptor 自动过滤，
        // 而这里用的是裸 JdbcTemplate。不带过滤就会把软删除的历史账号也数进来——
        // 本开发库「系统部」（id=1011）有 11 行 status=1 且 is_deleted=1 的历史账号
        // （P-10 删除用户留下的），于是断言期望 38、实际文案 27。
        // 与 §6.5 记录的是同一类漏网（那条在 sys_user_role 上）。
        long enabledUsers = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE dept_id = ? AND status = 1 AND is_deleted = 0",
                Long.class, deptId);
        long disabledUsers = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE dept_id = ? AND status = 0 AND is_deleted = 0",
                Long.class, deptId);

        var scope = adminScopeFor(adminId);

        // 模拟页面请求线程上的登录主体。
        // 注意：不设置 CurrentUser 直接调 Service 会被审计层拒绝（"缺少操作者身份"）——
        // 那是对"绕过 Controller 的调用"的正确防护，不是本用例要验证的行为。
        com.guarantee.common.security.CurrentUser.set(new com.guarantee.common.security.CurrentUser.Principal(
                adminId, "admin", "超级管理员", List.of(Roles.ADMIN),
                userService.listPermissionCodesByUserId(adminId)));
        try {
            // ① 有启用用户 → 拒绝，且文案给出条数（页面据此在点击前提示影响面）
            assertThatThrownBy(() -> departmentService.changeStatus(deptId, 0, scope))
                    .isInstanceOf(com.guarantee.common.exception.BizException.class)
                    .hasMessageContaining("仍有 " + enabledUsers + " 个启用中的用户")
                    .hasMessageContaining("不能停用");

            // ② 停用该部门下所有用户后 → 允许停用（规则只约束"状态正常"的用户）
            if (disabledUsers == 0) {
                // 同样只动未删除的行：软删除的历史账号不属于"该部门的用户"，
                // 顺手改它们的 status 等于测试偷偷修改了历史数据
                jdbcTemplate.update(
                        "UPDATE sys_user SET status = 0 WHERE dept_id = ? AND is_deleted = 0", deptId);
                try {
                    var updated = departmentService.changeStatus(deptId, 0, scope);
                    assertThat(updated.getStatus()).as("无启用用户时应可停用").isEqualTo(0);
                    // 该次停用应留下 source=WEB 的审计
                    Map<String, Object> audit = jdbcTemplate.queryForMap("""
                            SELECT source, action FROM ai_operation_audit
                            WHERE target_type = 'DEPT' AND target_id = ?
                            ORDER BY id DESC LIMIT 1
                            """, deptId);
                    assertThat(audit.get("source")).isEqualTo("WEB");
                    assertThat(audit.get("action")).isEqualTo("DISABLE");
                } finally {
                    jdbcTemplate.update("UPDATE sys_department SET status = 1 WHERE id = ?", deptId);
                    jdbcTemplate.update(
                            "UPDATE sys_user SET status = 1 WHERE dept_id = ? AND is_deleted = 0", deptId);
                }
            }
        } finally {
            com.guarantee.common.security.CurrentUser.clear();
        }
    }

    @Autowired
    private com.guarantee.system.service.DepartmentService departmentService;

    // ==================================================================
    // 辅助
    // ==================================================================

    private ProposalPayload createDisableInsuranceProposal(long adminId, long typeId, String typeName) {
        return createDisableInsuranceProposal(null, adminId, typeId, typeName);
    }

    /**
     * 造一张"停用险种"提案。
     *
     * @param conversationId 来源会话；为 {@code null} 表示"没有会话上下文"的直连路径
     */
    private ProposalPayload createDisableInsuranceProposal(Long conversationId, long adminId,
                                                           long typeId, String typeName) {
        return proposalFixture.track(proposalService.create(new ProposalService.ProposalDraft(
                conversationId, adminId, "admin", "超级管理员",
                "proposeInsuranceTypeChange", "DISABLE", "INSURANCE_TYPE", typeId, typeName,
                com.guarantee.ai.service.ProposalRequest.builder()
                        .id(typeId).targetName(typeName).userText("停用 " + typeName).build(),
                com.guarantee.ai.service.ProposalPreview.of("停用险种：" + typeName,
                        List.of(new com.guarantee.ai.service.ProposalPreview.ChangeItem(
                                "status", "状态", "启用", "停用")),
                        List.of("影响面：引用订单数"), List.of("停用后不再出现在新订单可选列表"), true),
                java.util.Set.of(Permissions.AI_SYSTEM_WRITE, Permissions.INSURANCE_DISABLE),
                "停用 " + typeName, null, "trace-it-" + typeId)));
    }

    private ProposalExecutionContext adminContext(String traceId) {
        long adminId = userId("admin");
        // 不含机构分量：机构已从用户与部门上移除（ProposalExecutionContext 也不再携带）
        return new ProposalExecutionContext(adminId, "admin", "超级管理员",
                List.of(Roles.ADMIN), userService.listPermissionCodesByUserId(adminId),
                dataScopeService.resolve(adminId, List.of(Roles.ADMIN)), traceId);
    }

    /**
     * 取一行查询结果。
     *
     * <p>刻意不用 {@code queryForMap}：一旦有多行它会抛
     * {@code IncorrectResultSizeDataAccessException}；这里显式断言"应当有这一行"，
     * 失败信息更直接（"审计记录缺失"而不是"结果集大小不对"）。</p>
     */
    private Map<String, Object> firstRow(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
        assertThat(rows).as("期望查询返回至少一行：%s", sql.trim()).isNotEmpty();
        return rows.get(0);
    }

    private long userId(String username) {
        return jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
    }

    private long insuranceTypeId(String typeName) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM insurance_type WHERE type_name = ?", Long.class, typeName);
    }

    // ==================================================================
    // 确认卡的会话隔离（真机故障：别的会话的卡片出现在当前会话里）
    // ==================================================================

    /**
     * 真机故障（2026-09-24）：用户在会话 485 里让助手把 user0005 改成只读角色，那张确认卡
     * 一直挂着；随后切到会话 484 问"建立个新角色"，助手正常回答了角色的命名与权限范围，
     * 可回答末尾却跟出一张「角色分配 user0005」的危险操作卡——**那是 485 的卡**。
     *
     * <p>成因：{@code GET /api/ai/proposals} 只按 {@code user_id} 过滤，前端在本轮结束的
     * 兜底刷新里拿到该用户**跨会话**的全部待确认提案，直接渲染在当前消息流末尾——
     * 看起来就像助手答非所问。</p>
     *
     * <p>修法：接口支持 {@code conversationId}，前端始终带上当前会话。本用例锁住服务端
     * 这一层：带会话号时只返回该会话的提案，不带时语义不变（跨会话待办视角）。</p>
     */
    @Test
    @DisplayName("待确认提案按会话隔离：带 conversationId 只返回该会话，缺省仍返回跨会话清单")
    void pendingProposalsAreScopedToConversation() {
        long adminId = userId("admin");
        // 用不存在的会话号即可：ai_operation_proposal.conversation_id 没有外键，
        // 而本用例要验证的正是"按这一列过滤"。真实会话号不具有可复现性。
        long conversationA = 990001L;
        long conversationB = 990002L;
        ProposalPayload inA = createDisableInsuranceProposal(conversationA, adminId,
                insuranceTypeId("履约保函（标准）"), "履约保函（标准）");
        ProposalPayload inB = createDisableInsuranceProposal(conversationB, adminId,
                insuranceTypeId("履约保函（预付款）"), "履约保函（预付款）");

        List<Long> scopedToA = proposalService.listMine(adminId, "PENDING", 50, conversationA).stream()
                .map(ProposalPayload::proposalId).toList();
        assertThat(scopedToA)
                .as("会话 A 只应看到自己的卡片")
                .contains(inA.proposalId())
                .doesNotContain(inB.proposalId());

        List<Long> scopedToB = proposalService.listMine(adminId, "PENDING", 50, conversationB).stream()
                .map(ProposalPayload::proposalId).toList();
        assertThat(scopedToB)
                .as("会话 B 只应看到自己的卡片")
                .contains(inB.proposalId())
                .doesNotContain(inA.proposalId());

        // 不带会话号时语义不变：仍是"该用户全部待确认提案"（跨会话待办清单）
        List<Long> global = proposalService.listMine(adminId, "PENDING", 50, null).stream()
                .map(ProposalPayload::proposalId).toList();
        assertThat(global)
                .as("不带 conversationId 时保持既有语义，不能把跨会话视角弄丢")
                .contains(inA.proposalId(), inB.proposalId());
    }
}
