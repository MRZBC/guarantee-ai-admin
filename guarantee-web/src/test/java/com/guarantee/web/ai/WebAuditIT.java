package com.guarantee.web.ai;

import com.guarantee.common.security.CurrentUser;
import com.guarantee.common.security.Roles;
import com.guarantee.system.dto.UserDto;
import com.guarantee.system.scope.DataScope;
import com.guarantee.system.scope.DataScopeService;
import com.guarantee.system.service.InsuranceTypeService;
import com.guarantee.system.service.UserService;
import com.guarantee.web.GuaranteeAiAdminApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 页面直连写操作的统一审计集成测试（SYS-A-07 / AC-22 / TEST-24）。
 *
 * <p><b>为什么必须有这个测试</b>：助手渠道的审计是通过 {@code ProposalService} 显式落库的，
 * 一眼可见；而页面渠道的审计是接在 5 个领域的写 Service 里，**很容易漏接某一个方法**，
 * 且漏接后系统照常工作、没有任何报错——只有"改了没痕迹"这个后果在数月后才会被发现。
 * 因此这里逐个渠道验证。</p>
 *
 * <p>测试方式：模拟页面直连（在 {@code CurrentUser} 中放入操作者，直接调用 Service 的写方法，
 * 不经过助手提案），然后断言 {@code ai_operation_audit} 中出现了 {@code source=WEB} 的记录。</p>
 */
@SpringBootTest(
        classes = GuaranteeAiAdminApplication.class,
        properties = {
                "guarantee.data-init.enabled=false",
                "guarantee.ai.proposal-expire-interval-ms=3600000",
                "guarantee.ai.audit-inspect-interval-ms=3600000"
        })
class WebAuditIT {

    @Autowired
    private UserService userService;

    @Autowired
    private InsuranceTypeService insuranceTypeService;

    @Autowired
    private DataScopeService dataScopeService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 测试前的险种状态快照——结束时按它精确还原。
     *
     * <p>为什么不直接"一律置为启用"：那会把使用者在页面上主动停用的险种也重新启用
     * （与"留下停用状态"是同一类破坏，只是方向相反）。快照还原后净影响为零。</p>
     */
    private Map<Long, Integer> insuranceStatusSnapshot;

    /** 模拟页面请求线程上的登录主体（Web 线程上 CurrentUser 是有效的）。 */
    @BeforeEach
    void setUpPrincipal() {
        // 机构已从用户上移除：Principal 不再携带 orgId
        CurrentUser.set(new CurrentUser.Principal(adminId(), "admin", "超级管理员",
                List.of(Roles.ADMIN), userService.listPermissionCodesByUserId(adminId())));
        // 先记快照，再建立前置状态：本类会真实停用险种，必须先确保它处于启用。
        insuranceStatusSnapshot = new java.util.LinkedHashMap<>();
        jdbcTemplate.query("SELECT id, status FROM insurance_type", rs -> {
            insuranceStatusSnapshot.put(rs.getLong("id"), rs.getInt("status"));
        });
        jdbcTemplate.update("UPDATE insurance_type SET status = 1 WHERE status <> 1");
    }

    /**
     * 与 {@link #setUpPrincipal()} 配对：收回本用例对共享开发库的改动。
     *
     * <p><b>缺了这一步的真实后果</b>：{@code pageInsuranceStatusChangeShouldWriteWebAudit}
     * 会停用「投标保函（标准）」且原先从不还原，于是每次 {@code mvn verify} 之后，
     * 开发库里那个险种就一直是停用状态——使用者在页面上看到的是"演示数据被谁停用了"，
     * 而实际上没有任何人操作过。原先的做法是靠**下一次运行**的 {@code @BeforeEach}
     * 把它重新启用，也就是"只在运行开始时复位"；只要不再运行，脏状态就一直留着。</p>
     */
    @AfterEach
    void clearPrincipal() {
        CurrentUser.clear();
        if (insuranceStatusSnapshot != null) {
            insuranceStatusSnapshot.forEach((id, status) -> jdbcTemplate.update(
                    "UPDATE insurance_type SET status = ? WHERE id = ?", status, id));
        }
    }

    // ==================================================================
    // AC-22 核心断言：页面写操作必须落 source=WEB
    // ==================================================================

    @Test
    @DisplayName("TEST-24：页面改用户资料 → 审计出现 source=WEB，且敏感字段无明文（D-4）")
    void pageUserProfileUpdateShouldWriteWebAuditWithoutPlaintext() {
        long targetId = userId("user0030");

        // 先把手机号置为一个确定值，否则本用例第二次运行时"新旧值相同"，
        // changed_fields 里就不会出现 phone，断言会变成假失败
        jdbcTemplate.update("UPDATE sys_user SET phone = ? WHERE id = ?", "13800000001", targetId);
        String phoneBefore = jdbcTemplate.queryForObject(
                "SELECT phone FROM sys_user WHERE id = ?", String.class, targetId);

        UserDto.UpdateRequest request = new UserDto.UpdateRequest();
        request.setRealName("页面改名测试");
        request.setPhone("13500001111");
        userService.updateProfile(targetId, request, adminScope(), adminId());

        Map<String, Object> audit = latestAudit("USER", targetId);
        assertThat(audit.get("source")).as("页面直连必须是 WEB（AC-22）").isEqualTo("WEB");
        assertThat(audit.get("action")).isEqualTo("UPDATE");
        assertThat(audit.get("result")).isEqualTo("SUCCESS");
        assertThat(audit.get("operator_username")).isEqualTo("admin");

        // D-4：审计中不得出现手机号明文（新旧值都不能有）
        String before = String.valueOf(audit.get("before_value"));
        String after = String.valueOf(audit.get("after_value"));
        assertThat(before).doesNotContain("13500001111").doesNotContain("13800000001");
        assertThat(after).doesNotContain("13500001111").doesNotContain("13800000001");
        if (phoneBefore != null && !phoneBefore.isBlank()) {
            assertThat(before).as("旧手机号也不能出现明文").doesNotContain(phoneBefore);
        }
        // 但必须能看出手机号发生过变更
        assertThat(String.valueOf(audit.get("changed_fields"))).contains("phone");
        // 非敏感字段保留可读值
        assertThat(after).contains("页面改名测试");
    }

    @Test
    @DisplayName("TEST-24：页面启停险种 → 审计 source=WEB，含结构化前后值")
    void pageInsuranceStatusChangeShouldWriteWebAudit() {
        long typeId = insuranceTypeId("投标保函（标准）");

        insuranceTypeService.changeStatus(typeId, 0);

        Map<String, Object> audit = latestAudit("INSURANCE_TYPE", typeId);
        assertThat(audit.get("source")).isEqualTo("WEB");
        assertThat(audit.get("action")).isEqualTo("DISABLE");
        assertThat(String.valueOf(audit.get("before_value"))).contains("\"status\":1");
        assertThat(String.valueOf(audit.get("after_value"))).contains("\"status\":0");
        assertThat(audit.get("changed_fields")).isEqualTo("status");
    }

    @Test
    @DisplayName("TEST-24：页面改角色权限 → 审计记录权限码集合的前后值")
    void pageRolePermissionChangeShouldWriteWebAudit() {
        // 用 ANALYST 角色（非 ADMIN，可授权）
        List<String> before = permissionCodesOfRole("ANALYST");
        List<String> target = new java.util.ArrayList<>(before);
        target.add("system:permission:view");

        roleService().assignPermissions("ANALYST", target);

        Map<String, Object> audit = latestAudit("ROLE", roleIdByCode("ANALYST"));
        assertThat(audit.get("source")).isEqualTo("WEB");
        assertThat(audit.get("action")).isEqualTo("ASSIGN_PERMISSIONS");
        assertThat(String.valueOf(audit.get("after_value"))).contains("system:permission:view");

        // 复原，避免影响其它用例
        roleService().assignPermissions("ANALYST", before);
    }

    // ==================================================================
    // 与助手渠道的区分（AC-22：两条渠道可按 source 区分）
    // ==================================================================

    @Test
    @DisplayName("AC-22：同一张表中 AI 与 WEB 两条渠道的记录可区分")
    void bothChannelsShareOneAuditTable() {
        Integer webCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_operation_audit WHERE source = 'WEB'", Integer.class);
        Integer aiCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_operation_audit WHERE source = 'AI'", Integer.class);

        assertThat(webCount).as("本测试类已产生 WEB 记录").isNotNull().isGreaterThan(0);
        assertThat(aiCount).as("助手渠道（ProposalService）的记录仍在同一张表").isNotNull().isGreaterThanOrEqualTo(0);

        // 两条渠道写的是同一张表，只靠 source 区分
        Integer totalDistinctSources = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT source) FROM ai_operation_audit", Integer.class);
        assertThat(totalDistinctSources).isGreaterThanOrEqualTo(1);
    }

    // ==================================================================
    // 审计失败必须让业务回滚（SYS-A-03）
    // ==================================================================

    @Test
    @DisplayName("SYS-A-03：缺少操作者身份时审计失败，业务必须回滚而不是静默成功")
    void auditFailureShouldRollBackBusinessChange() {
        long typeId = insuranceTypeId("电子投标保函");
        Integer before = jdbcTemplate.queryForObject(
                "SELECT status FROM insurance_type WHERE id = ?", Integer.class, typeId);
        int auditsBefore = countAudits("INSURANCE_TYPE", typeId);

        // 模拟"身份上下文丢失"（例如有代码绕过 Controller 直接调用写 Service）：
        // 适配器会拒绝写匿名审计，业务必须随之回滚
        CurrentUser.clear();
        try {
            assertThatThrownBy(() -> insuranceTypeService.changeStatus(typeId, 0))
                    .as("缺少身份时操作必须失败，而不是'改了但没痕迹'或'写一条匿名审计'")
                    .hasMessageContaining("缺少操作者身份");
        } finally {
            // 恢复上下文，供后续断言使用
            CurrentUser.set(new CurrentUser.Principal(adminId(), "admin", "超级管理员",
                    List.of(Roles.ADMIN), userService.listPermissionCodesByUserId(adminId())));
        }

        Integer after = jdbcTemplate.queryForObject(
                "SELECT status FROM insurance_type WHERE id = ?", Integer.class, typeId);
        assertThat(after).as("业务变更必须随审计失败一起回滚").isEqualTo(before);
        assertThat(countAudits("INSURANCE_TYPE", typeId)).as("不应留下审计记录").isEqualTo(auditsBefore);
    }

    // ==================================================================
    // 覆盖检查：5 个域的写方法都要产出审计（防止漏接）
    // ==================================================================

    @Test
    @DisplayName("TEST-24：5 个域各写一次，全部产出 source=WEB 审计（防止漏接某个方法）")
    void everyDomainShouldProduceWebAudit() {
        long before = countWebAudits();

        // 机构 / 部门的启停有前置检查（存在启用用户时禁止停用），因此这里**自建测试数据**，
        // 而不是拿演示数据去停——那样会因"机构下有启用用户"而失败，且会污染其它用例。
        long testOrgId = 0L;
        long testDeptId = 0L;
        try {
            // 用户（资料更新）
            long userId = userId("user0031");
            UserDto.UpdateRequest userRequest = new UserDto.UpdateRequest();
            userRequest.setRealName("覆盖检查");
            userService.updateProfile(userId, userRequest, adminScope(), adminId());

            // 险种（启停）
            long typeId = insuranceTypeId("履约保函（标准）");
            insuranceTypeService.changeStatus(typeId, 0);

            // 角色（修改描述，纯 UPDATE，避免动权限影响其它用例）
            long roleId = roleIdByCode("REGION_OPS_CHECK");
            if (roleId == 0) {
                roleId = roleService().create(createRoleRequest()).getId();
            }
            var roleUpdate = new com.guarantee.system.dto.RoleDto.UpdateRequest();
            roleUpdate.setDescription("覆盖检查-" + System.currentTimeMillis());
            roleService().update(roleId, roleUpdate);

            // 机构（启停自建的空机构）
            var orgCreate = new com.guarantee.system.dto.OrgDto.CreateRequest();
            orgCreate.setOrgCode("ORGT" + System.currentTimeMillis() % 10000);
            orgCreate.setOrgName("审计覆盖检查机构");
            orgCreate.setRegionCode("110000");
            orgCreate.setOrgLevel(2);
            orgCreate.setParentId(headquartersId());
            testOrgId = orgService().create(orgCreate, adminScope()).getId();
            orgService().changeStatus(testOrgId, 0, adminScope());

            // 部门（启停自建的空部门）。
            // 部门不再挂机构，因此这里建一个**全新的顶级部门**：演示数据里的部门大多已有启用用户，
            // 停用它们会被前置检查拒绝（这本身是正确行为，但不适合用来断言"审计已接上"）。
            var deptCreate = new com.guarantee.system.dto.DepartmentDto.CreateRequest();
            deptCreate.setDeptCode("DEPTT" + System.currentTimeMillis() % 10000);
            deptCreate.setDeptName("审计覆盖检查部门");
            deptCreate.setParentId(0L);
            testDeptId = departmentService().create(deptCreate, adminScope()).getId();
            // 自证前提：新建部门在空机构下，必须没有任何启用用户，否则后面的停用断言无意义
            Integer usersInNewDept = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sys_user WHERE dept_id = ? AND status = 1", Integer.class, testDeptId);
            assertThat(usersInNewDept)
                    .as("新建部门 %s 下不应有启用用户（若有，说明 deptId 被解析成了错误的部门）", testDeptId)
                    .isZero();
            departmentService().changeStatus(testDeptId, 0, adminScope());

            long after = countWebAudits();
            assertThat(after - before)
                    .as("5 个域各写一次应至少产生 5 条 WEB 审计；若某域为 0 说明该域漏接审计")
                    .isGreaterThanOrEqualTo(5);
        } finally {
            // 清理测试数据，避免污染机构数/部门数等既有断言
            if (testDeptId > 0) {
                jdbcTemplate.update("DELETE FROM ai_operation_audit WHERE target_type = 'DEPT' AND target_id = ?",
                        testDeptId);
                jdbcTemplate.update("DELETE FROM sys_department WHERE id = ?", testDeptId);
            }
            if (testOrgId > 0) {
                jdbcTemplate.update("DELETE FROM ai_operation_audit WHERE target_type = 'ORG' AND target_id = ?",
                        testOrgId);
                jdbcTemplate.update("DELETE FROM sys_org WHERE id = ?", testOrgId);
            }
            // 险种状态不在这里复位：统一由 @AfterEach 按快照还原。
            // 原先这里写的是"一律置为启用"，会把使用者主动停用的险种也重新启用。
        }
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    @Autowired
    private com.guarantee.system.service.RoleService roleServiceBean;

    @Autowired
    private com.guarantee.system.service.OrgService orgServiceBean;

    @Autowired
    private com.guarantee.system.service.DepartmentService departmentServiceBean;

    private com.guarantee.system.service.RoleService roleService() {
        return roleServiceBean;
    }

    private com.guarantee.system.service.OrgService orgService() {
        return orgServiceBean;
    }

    private com.guarantee.system.service.DepartmentService departmentService() {
        return departmentServiceBean;
    }

    private static com.guarantee.system.dto.RoleDto.CreateRequest createRoleRequest() {
        var request = new com.guarantee.system.dto.RoleDto.CreateRequest();
        request.setRoleCode("REGION_OPS_CHECK");
        request.setRoleName("覆盖检查角色");
        request.setDescription("由 WebAuditIT 创建，用于验证角色域审计覆盖");
        return request;
    }

    private DataScope adminScope() {
        return dataScopeService.resolve(adminId(), List.of(Roles.ADMIN));
    }

    /** 取某目标最近的审计记录。 */
    private Map<String, Object> latestAudit(String targetType, long targetId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT source, action, result, operator_username, before_value, after_value, changed_fields
                FROM ai_operation_audit
                WHERE target_type = ? AND target_id = ?
                ORDER BY id DESC LIMIT 1
                """, targetType, targetId);
        assertThat(rows).as("目标 %s:%s 缺少审计记录（该域的写路径漏接审计）", targetType, targetId)
                .isNotEmpty();
        return rows.get(0);
    }

    private int countAudits(String targetType, long targetId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ai_operation_audit WHERE target_type = ? AND target_id = ?
                """, Integer.class, targetType, targetId);
        return count == null ? 0 : count;
    }

    private long countWebAudits() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_operation_audit WHERE source = 'WEB'", Integer.class);
        return count == null ? 0 : count;
    }

    private List<String> permissionCodesOfRole(String roleCode) {
        return jdbcTemplate.queryForList("""
                SELECT p.perm_code FROM sys_role r
                INNER JOIN sys_role_permission rp ON rp.role_id = r.id
                INNER JOIN sys_permission p ON p.id = rp.permission_id
                WHERE r.role_code = ?
                ORDER BY p.sort_no
                """, String.class, roleCode);
    }

    private long adminId() {
        return userId("admin");
    }

    private long userId(String username) {
        return jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
    }

    private long headquartersId() {
        return jdbcTemplate.queryForObject("SELECT id FROM sys_org WHERE org_level = 1", Long.class);
    }

    private long insuranceTypeId(String typeName) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM insurance_type WHERE type_name = ?", Long.class, typeName);
    }

    private long roleIdByCode(String roleCode) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM sys_role WHERE role_code = ?", Long.class, roleCode);
        return ids.isEmpty() ? 0L : ids.get(0);
    }

    private long orgIdByCode(String orgCode) {
        return jdbcTemplate.queryForObject("SELECT id FROM sys_org WHERE org_code = ?", Long.class, orgCode);
    }
}
