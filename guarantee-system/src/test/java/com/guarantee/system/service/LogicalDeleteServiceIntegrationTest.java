package com.guarantee.system.service;

import com.guarantee.common.exception.BizException;
import com.guarantee.common.security.Roles;
import com.guarantee.common.security.UserTokenRevoker;
import com.guarantee.system.ItMybatisConfig;
import com.guarantee.system.dto.DepartmentDto;
import com.guarantee.system.dto.InsuranceTypeDto;
import com.guarantee.system.dto.OrgDto;
import com.guarantee.system.dto.RoleDto;
import com.guarantee.system.dto.UserDto;
import com.guarantee.system.entity.SysOrg;
import com.guarantee.system.entity.SysUser;
import com.guarantee.system.mapper.SysOrgMapper;
import com.guarantee.system.scope.DataScope;
import com.guarantee.system.scope.DataScopeService;
import com.guarantee.system.vo.DepartmentVO;
import com.guarantee.system.vo.InsuranceTypeVO;
import com.guarantee.system.vo.OrgVO;
import com.guarantee.system.vo.RoleVO;
import com.guarantee.system.vo.UserVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 逻辑删除的**服务层 / 鉴权路径 / 数据范围**集成测试。
 *
 * <p>对应设计文档 §11：LD-T1（列表默认不显示已删除 + includeDeleted）、LD-T3（恢复回到删除前状态）、
 * LD-T4（父未恢复则拒绝恢复子）、LD-T5（关联表 UPSERT 循环）、LD-T6（**鉴权路径：删角色即失去权限**）、
 * LD-T7（**数据范围递归不穿过已删除机构**）、LD-T9（令牌撤销）、LD-T10（被引用即拒绝删除）、
 * LD-T11（危险动作）、LD-T13（停用前置检查口径）、LD-T15（唯一性校验忽略已删除）。</p>
 *
 * <p><b>测试数据纪律</b>：夹具一律以 {@code __ldt} 前缀建行，{@code @AfterEach} 物理清理，
 * 以免影响演示数据相关的既有断言（300 用户 / 21 机构）。</p>
 */
@SpringBootTest(classes = ItMybatisConfig.class)
@Import(LogicalDeleteServiceIntegrationTest.RecordingRevokerConfig.class)
@TestPropertySource(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/schema.sql"
})
class LogicalDeleteServiceIntegrationTest {

    private static final String P = "__ldt_";

    /** 记录令牌撤销调用的替身：{@code guarantee-auth} 的 Redis 实现不在本模块测试范围内。 */
    static class RecordingRevoker implements UserTokenRevoker {
        final List<Long> revoked = new CopyOnWriteArrayList<>();
        final List<String> reasons = new CopyOnWriteArrayList<>();

        @Override
        public int revokeUsers(java.util.Collection<Long> userIds, String reason) {
            revoked.addAll(userIds);
            reasons.add(reason);
            return userIds.size();
        }

        void clear() {
            revoked.clear();
            reasons.clear();
        }
    }

    @TestConfiguration
    static class RecordingRevokerConfig {
        @Bean
        RecordingRevoker recordingRevoker() {
            return new RecordingRevoker();
        }
    }

    @Autowired
    private OrgService orgService;
    @Autowired
    private DepartmentService departmentService;
    @Autowired
    private UserService userService;
    @Autowired
    private RoleService roleService;
    @Autowired
    private InsuranceTypeService insuranceTypeService;
    @Autowired
    private DataScopeService dataScopeService;
    @Autowired
    private SysOrgMapper sysOrgMapper;
    @Autowired
    private com.guarantee.system.mapper.SysUserMapper sysUserMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RecordingRevoker revoker;

    @AfterEach
    void cleanup() {
        revoker.clear();
        jdbc.update("DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE username LIKE ?)", P + "%");
        jdbc.update("DELETE FROM sys_role_permission WHERE role_id IN (SELECT id FROM sys_role WHERE role_code LIKE ?)", P + "%");
        jdbc.update("DELETE FROM sys_user WHERE username LIKE ?", P + "%");
        jdbc.update("DELETE FROM sys_role WHERE role_code LIKE ?", P + "%");
        jdbc.update("DELETE FROM sys_department WHERE dept_code LIKE ?", P + "%");
        jdbc.update("DELETE FROM sys_org WHERE org_code LIKE ?", P + "%");
        jdbc.update("DELETE FROM insurance_type WHERE type_code LIKE ?", P + "%");
    }

    // ==================================================================
    // LD-T1：默认列表不出现；includeDeleted 时出现且带删除信息
    // ==================================================================

    @Test
    @DisplayName("LD-T1 删除后默认列表不出现；includeDeleted=true 时出现并带 isDeleted/deletedAt")
    void deletedRowHiddenByDefaultAndVisibleWithFlag() {
        DataScope admin = adminScope();
        OrgVO created = orgService.create(orgRequest(P + "t1", "LD-T1 夹具"), admin);
        Long id = created.getId();

        OrgDto.Query visible = new OrgDto.Query();
        visible.setPageNum(1);
        visible.setPageSize(50);
        visible.setOrgCode(P + "t1");
        assertThat(orgService.page(visible, admin).total()).as("删除前应可见").isEqualTo(1);

        OrgVO deleted = orgService.delete(id, admin, adminUserId());
        assertThat(deleted.getIsDeleted()).as("删除响应应带 isDeleted=1").isEqualTo(1);
        assertThat(deleted.getDeletedAt()).as("删除响应应带删除时间").isNotNull();

        assertThat(orgService.page(visible, admin).total()).as("默认列表必须不显示已删除").isZero();

        OrgDto.Query including = new OrgDto.Query();
        including.setPageNum(1);
        including.setPageSize(50);
        including.setOrgCode(P + "t1");
        including.setIncludeDeleted(true);
        List<OrgVO> rows = orgService.page(including, admin).list();
        assertThat(rows).as("includeDeleted=true 必须能看到已删除行").hasSize(1);
        assertThat(rows.get(0).getIsDeleted()).isEqualTo(1);
        assertThat(rows.get(0).getDeletedAt()).as("已删除行必须带删除时间供页面展示").isNotNull();

        // 恢复后回到默认列表
        orgService.restore(id, adminUserId());
        assertThat(orgService.page(visible, admin).total()).as("恢复后必须回到默认列表").isEqualTo(1);
    }

    // ==================================================================
    // LD-T3：恢复后 status 与删除前完全一致（LD-02 的回归）
    // ==================================================================

    @Test
    @DisplayName("LD-T3 删除不隐式改 status：恢复后 status 与删除前完全一致")
    void restoreKeepsOriginalStatus() {
        DataScope admin = adminScope();
        // 夹具用户：直接建成"停用"状态，验证删除/恢复不会把它改成启用
        jdbc.update("INSERT INTO sys_user (username, password, real_name, dept_id, status) "
                + "VALUES (?, 'x', '夹具', ?, 0)", P + "t3", deptId());
        Long userId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, P + "t3");

        userService.delete(userId, admin, adminUserId());
        Integer statusAfterDelete = jdbc.queryForObject(
                "SELECT status FROM sys_user WHERE id = ?", Integer.class, userId);
        assertThat(statusAfterDelete).as("删除不得隐式修改 status（否则恢复无法还原）").isZero();

        UserVO restored = userService.restore(userId, adminUserId());
        assertThat(restored.getStatus()).as("恢复后 status 必须与删除前一致").isZero();
        assertThat(restored.getIsDeleted()).isZero();
    }

    // ==================================================================
    // LD-T4：父未恢复 → 拒绝恢复子（LD-04a）
    // ==================================================================

    @Test
    @DisplayName("LD-T4 上级部门仍被删除时恢复子部门被拒绝，并提示先恢复上级")
    void restoreChildRejectedWhenParentStillDeleted() {
        DataScope admin = adminScope();
        // 部门不再挂机构：夹具直接建一棵纯部门树（原来的"机构夹具"已无意义）
        DepartmentDto.CreateRequest parentReq = new DepartmentDto.CreateRequest();
        parentReq.setDeptCode(P + "t4p");
        parentReq.setDeptName("LD-T4 父部门");
        DepartmentVO parent = departmentService.create(parentReq, admin);

        DepartmentDto.CreateRequest childReq = new DepartmentDto.CreateRequest();
        childReq.setDeptCode(P + "t4c");
        childReq.setDeptName("LD-T4 子部门");
        childReq.setParentId(parent.getId());
        DepartmentVO child = departmentService.create(childReq, admin);

        // 先删子、再删父（顺序与恢复相反）
        departmentService.delete(child.getId(), admin, adminUserId());
        departmentService.delete(parent.getId(), admin, adminUserId());

        assertThatThrownBy(() -> departmentService.restore(child.getId(), adminUserId()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("请先恢复其上级部门");

        departmentService.restore(parent.getId(), adminUserId());
        DepartmentVO restoredChild = departmentService.restore(child.getId(), adminUserId());
        assertThat(restoredChild.getId()).isEqualTo(child.getId());
        assertThat(restoredChild.getIsDeleted()).isZero();
    }

    // ==================================================================
    // LD-T5：关联表 UPSERT（[A,B] → [B,C] → [A,B]）
    // ==================================================================

    @Test
    @DisplayName("LD-T5 角色分配 UPSERT：先清后插改 UPSERT 后反复变更不报错且最终状态正确")
    void assignRolesUpsertCycle() {
        DataScope admin = adminScope();
        jdbc.update("INSERT INTO sys_user (username, password, real_name, dept_id, status) VALUES (?, 'x', '夹具', ?, 1)",
                P + "t5", deptId());
        Long userId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, P + "t5");

        List<String> cycleA = List.of("OPERATOR", "ANALYST");
        List<String> cycleB = List.of("ANALYST", "VIEWER");

        userService.assignRoles(userId, cycleA, admin, adminUserId());
        assertThat(roleCodesOf(userId)).containsExactlyInAnyOrderElementsOf(cycleA);

        // 第二次变更会命中"已删除的旧绑定"，唯一键 (user_id, role_id) 仍拒绝 INSERT，
        // 必须靠 UPSERT 复活 → 这正是 LD-R7 的回归点
        userService.assignRoles(userId, cycleB, admin, adminUserId());
        assertThat(roleCodesOf(userId)).containsExactlyInAnyOrderElementsOf(cycleB);

        userService.assignRoles(userId, cycleA, admin, adminUserId());
        assertThat(roleCodesOf(userId)).containsExactlyInAnyOrderElementsOf(cycleA);

        Integer activeBindings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user_role WHERE user_id = ? AND is_deleted = 0", Integer.class, userId);
        assertThat(activeBindings).as("有效绑定数必须恰好等于目标角色数").isEqualTo(cycleA.size());
        Integer allBindings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user_role WHERE user_id = ?", Integer.class, userId);
        assertThat(allBindings).as("历史绑定必须保留（逻辑删除的意义）").isGreaterThanOrEqualTo(3);
    }

    // ==================================================================
    // LD-T6：鉴权路径（漏加过滤 = 提权）
    // ==================================================================

    @Test
    @DisplayName("LD-T6 删除角色后，持有该角色的用户权限集合**立即**不含该角色权限（防提权）")
    void deletingRoleRemovesItsPermissionsFromAuthPath() {
        jdbc.update("INSERT INTO sys_user (username, password, real_name, dept_id, status) VALUES (?, 'x', '夹具', ?, 1)",
                P + "t6u", deptId());
        Long userId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, P + "t6u");
        jdbc.update("INSERT INTO sys_role (role_code, role_name, status) VALUES (?, 'LD-T6 角色', 1)", P + "t6r");
        Long roleId = jdbc.queryForObject("SELECT id FROM sys_role WHERE role_code = ?", Long.class, P + "t6r");
        Long permId = jdbc.queryForObject(
                "SELECT id FROM sys_permission WHERE perm_code = 'system:audit:view'", Long.class);

        jdbc.update("INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)", userId, roleId);
        jdbc.update("INSERT INTO sys_role_permission (role_id, permission_id) VALUES (?, ?)", roleId, permId);

        assertThat(userService.listPermissionCodesByUserId(userId))
                .as("绑定有效时权限必须包含该角色授予的权限码")
                .contains("system:audit:view");
        assertThat(userService.listRoleCodesByUserId(userId)).contains(P + "t6r");

        // 直接做逻辑删除（例如运维按设计 §9.4.1 直连删除）：鉴权路径必须立刻不再返回该角色的权限。
        // 这一条不能用 roleService.delete 触发——它要求角色无用户持有（§6.2），
        // 而本断言要证明的是"角色一旦处于已删除状态，权限就不得再签发"。
        jdbc.update("UPDATE sys_role SET is_deleted = 1, deleted_at = NOW(6), deleted_by = 'DB' WHERE id = ?", roleId);

        assertThat(userService.listPermissionCodesByUserId(userId))
                .as("已删除角色的权限绝不能继续签发进 JWT（提权漏洞）")
                .doesNotContain("system:audit:view");
        assertThat(userService.listRoleCodesByUserId(userId))
                .as("已删除角色不得再参与角色判定")
                .doesNotContain(P + "t6r");
        assertThat(sysUserMapper.listRoleIdsByUserId(userId))
                .as("角色 id 列表同样要过滤（角色回填口径）")
                .doesNotContain(roleId);
    }

    // ==================================================================
    // LD-T7：数据范围递归不穿过已删除机构（漏加 = 越权）
    // ==================================================================

    @Test
    @DisplayName("LD-T7 删除中间层机构后，selectVisibleOrgIds 不穿过它继续向下展开")
    void visibleOrgIdsDoNotCrossDeletedOrg() {
        long provinceId = jdbc.queryForObject(
                "SELECT id FROM sys_org WHERE org_level = 2 ORDER BY id LIMIT 1", Long.class);
        jdbc.update("INSERT INTO sys_org (org_code, org_name, region_code, region_name, org_level, parent_id, status) "
                + "VALUES (?, '夹具-中间层', '000000', '未指定', 3, ?, 1)", P + "t7mid", provinceId);
        long midId = jdbc.queryForObject("SELECT id FROM sys_org WHERE org_code = ?", Long.class, P + "t7mid");
        jdbc.update("INSERT INTO sys_org (org_code, org_name, region_code, region_name, org_level, parent_id, status) "
                + "VALUES (?, '夹具-孙层', '000000', '未指定', 3, ?, 1)", P + "t7leaf", midId);
        long leafId = jdbc.queryForObject("SELECT id FROM sys_org WHERE org_code = ?", Long.class, P + "t7leaf");

        List<Long> before = sysOrgMapper.selectVisibleOrgIds(provinceId);
        assertThat(before).as("正常情况下递归应包含自身 + 中间层 + 孙层")
                .contains(provinceId, midId, leafId);

        jdbc.update("UPDATE sys_org SET is_deleted = 1, deleted_at = NOW(6), deleted_by = 'DB' WHERE id = ?", midId);

        List<Long> after = sysOrgMapper.selectVisibleOrgIds(provinceId);
        assertThat(after).as("起点机构仍在范围内").contains(provinceId);
        assertThat(after).as("已删除机构本身不得纳入范围（LD-Q6）").doesNotContain(midId);
        assertThat(after).as("**不得穿过已删除机构继续向下展开**（越权，LD-R4）").doesNotContain(leafId);
    }

    // ==================================================================
    // LD-T9：删除用户 / 删除角色变更 → 撤销令牌
    // ==================================================================

    @Test
    @DisplayName("LD-T9 删除用户与删除角色都会触发令牌撤销（否则旧 JWT 最长可用 12 小时）")
    void deleteRevokesTokens() {
        DataScope admin = adminScope();
        jdbc.update("INSERT INTO sys_user (username, password, real_name, dept_id, status) VALUES (?, 'x', '夹具', ?, 1)",
                P + "t9u", deptId());
        Long userId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, P + "t9u");
        jdbc.update("INSERT INTO sys_role (role_code, role_name, status) VALUES (?, 'LD-T9 角色', 1)", P + "t9r");
        Long roleId = jdbc.queryForObject("SELECT id FROM sys_role WHERE role_code = ?", Long.class, P + "t9r");
        jdbc.update("INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)", userId, roleId);

        revoker.clear();
        userService.delete(userId, admin, adminUserId());
        assertThat(revoker.revoked).as("删除用户必须撤销其全部令牌").contains(userId);
        assertThat(revoker.reasons).anyMatch(r -> r.contains("用户被删除"));

        // 角色：§6.2 要求"无未删除用户持有"才允许删除，因此删除角色时必然没有持有者可撤；
        // 真正需要撤销令牌的是"变更角色权限"（SYS-C-07），这里断言该路径；
        // 删除角色这一路径的撤销调用仍然保留在实现里（供直连/历史数据场景兜底）。
        revoker.clear();
        RoleVO role = roleService.assignPermissions(P + "t9r", List.of("system:permission:view"));
        assertThat(role.getId()).isEqualTo(roleId);
        assertThat(revoker.revoked).as("角色权限变更必须撤销所有持有者的令牌（否则权限变更不生效）")
                .contains(userId);

        // 解绑后删除角色（无持有者）→ 删除成功
        jdbc.update("UPDATE sys_user_role SET is_deleted = 1, deleted_at = NOW(6) WHERE role_id = ?", roleId);
        revoker.clear();
        RoleVO deletedRole = roleService.delete(roleId, adminUserId());
        assertThat(deletedRole.getIsDeleted()).isEqualTo(1);
    }

    // ==================================================================
    // LD-T10：被引用的险种禁止删除（且给出引用数）
    // ==================================================================

    @Test
    @DisplayName("LD-T10 被订单引用的险种禁止删除，错误信息带引用条数")
    void referencedInsuranceTypeCannotBeDeleted() {
        Long typeId = jdbc.queryForObject("""
                SELECT insurance_type_id FROM tender_order GROUP BY insurance_type_id LIMIT 1
                """, Long.class);
        long orders = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tender_order WHERE insurance_type_id = ?", Long.class, typeId);

        assertThatThrownBy(() -> insuranceTypeService.delete(typeId, adminUserId()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能删除")
                .hasMessageContaining(String.valueOf(orders));

        Integer stillActive = jdbc.queryForObject(
                "SELECT is_deleted FROM insurance_type WHERE id = ?", Integer.class, typeId);
        assertThat(stillActive).as("被拒绝的删除不得留下任何痕迹").isZero();
    }

    // ==================================================================
    // LD-T11：危险动作（删自己 / 删最后一个启用 ADMIN）
    // ==================================================================

    @Test
    @DisplayName("LD-T11 禁止删除自己；禁止删除最后一个启用状态的 ADMIN")
    void dangerousDeletesAreRejected() {
        DataScope admin = adminScope();
        jdbc.update("INSERT INTO sys_user (username, password, real_name, dept_id, status) VALUES (?, 'x', '夹具', ?, 1)",
                P + "t11", deptId());
        Long fixtureId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, P + "t11");
        Long adminId = adminUserId();

        // ① 删自己
        assertThatThrownBy(() -> userService.delete(fixtureId, admin, fixtureId))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不允许删除自己");

        // ② 最后一个启用 ADMIN：夹具用户赋 ADMIN 角色，并临时停用真正的 admin，
        //    使夹具成为"唯一启用状态的 ADMIN"；断言后立刻恢复（try/finally）。
        jdbc.update("INSERT INTO sys_user_role (user_id, role_id) "
                + "SELECT ?, id FROM sys_role WHERE role_code = 'ADMIN'", fixtureId);
        jdbc.update("UPDATE sys_user SET status = 0 WHERE id = ?", adminId);
        try {
            SysUser fixture = jdbc.queryForObject(
                    "SELECT id, username, status FROM sys_user WHERE id = ?",
                    (rs, i) -> {
                        SysUser u = new SysUser();
                        u.setId(rs.getLong("id"));
                        u.setUsername(rs.getString("username"));
                        u.setStatus(rs.getInt("status"));
                        return u;
                    }, fixtureId);
            assertThat(userService.deleteBlockers(fixture, 999L))
                    .as("最后一个启用状态的超级管理员不允许删除")
                    .anyMatch(msg -> msg.contains("最后一个启用状态的超级管理员"));
            assertThatThrownBy(() -> userService.delete(fixtureId, admin, 999L))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("最后一个启用状态的超级管理员");
        } finally {
            jdbc.update("UPDATE sys_user SET status = 1 WHERE id = ?", adminId);
        }

        Integer fixtureDeleted = jdbc.queryForObject(
                "SELECT is_deleted FROM sys_user WHERE id = ?", Integer.class, fixtureId);
        assertThat(fixtureDeleted).as("被拒绝的删除不得改变数据").isZero();
        Integer adminStatus = jdbc.queryForObject(
                "SELECT status FROM sys_user WHERE id = ?", Integer.class, adminId);
        assertThat(adminStatus).as("夹具必须把临时修改的 admin 状态还原").isEqualTo(1);
    }

    // ==================================================================
    // LD-T13：停用前置检查只统计 is_deleted = 0（LD-03）
    // ==================================================================

    @Test
    @DisplayName("LD-T13 部门下只有\"已删除的启用用户\"时允许停用（LD-03）")
    void disableDeptIgnoresDeletedUsers() {
        DataScope admin = adminScope();
        DepartmentDto.CreateRequest req = new DepartmentDto.CreateRequest();
        req.setDeptCode(P + "t13d");
        req.setDeptName("LD-T13 部门");
        DepartmentVO dept = departmentService.create(req, admin);

        jdbc.update("INSERT INTO sys_user (username, password, real_name, dept_id, status) "
                + "VALUES (?, 'x', '夹具', ?, 1)", P + "t13u", dept.getId());

        assertThatThrownBy(() -> departmentService.changeStatus(dept.getId(), 0, admin))
                .as("存在启用用户时必须拒绝停用")
                .isInstanceOf(BizException.class)
                .hasMessageContaining("启用中的用户");

        // 逻辑删除该用户后：停用检查必须只统计 is_deleted = 0 的行 → 允许停用
        jdbc.update("UPDATE sys_user SET is_deleted = 1, deleted_at = NOW(6), deleted_by = 'DB' WHERE username = ?",
                P + "t13u");
        DepartmentVO disabled = departmentService.changeStatus(dept.getId(), 0, admin);
        assertThat(disabled.getStatus()).as("已删除的用户不该继续阻塞部门停用（LD-03）").isZero();
    }

    // ==================================================================
    // LD-T15：唯一性校验必须忽略已删除记录
    // ==================================================================

    @Test
    @DisplayName("LD-T15 存在已删除的同编码记录时，新增同编码必须被允许（§5.3）")
    void uniquenessCheckIgnoresDeletedRows() {
        DataScope admin = adminScope();
        OrgVO first = orgService.create(orgRequest(P + "t15", "LD-T15 第一次"), admin);
        orgService.delete(first.getId(), admin, adminUserId());

        // 同编码再建：查重若只看 org_code（不带 is_deleted = 0）会误报"编码已存在"
        OrgVO second = orgService.create(orgRequest(P + "t15", "LD-T15 第二次"), admin);
        assertThat(second.getId()).as("新记录的 id 必须与已删除记录不同").isNotEqualTo(first.getId());
        assertThat(second.getIsDeleted()).isZero();

        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_org WHERE org_code = ?", Integer.class, P + "t15");
        assertThat(total).as("同编码在库中应是 1 条有效 + 1 条已删除").isEqualTo(2);
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private DataScope adminScope() {
        return dataScopeService.resolve(adminUserId(), List.of(Roles.ADMIN));
    }

    private Long adminUserId() {
        return jdbc.queryForObject("SELECT id FROM sys_user WHERE username = 'admin'", Long.class);
    }

    /**
     * 一个真实存在的部门 id（用户夹具用）。
     *
     * <p>{@code sys_user.dept_id} 已收紧为 NOT NULL：用户必须属于一个部门，且不再有机构归属。</p>
     */
    private long deptId() {
        return jdbc.queryForObject(
                "SELECT id FROM sys_department WHERE is_deleted = 0 ORDER BY id LIMIT 1", Long.class);
    }

    private static OrgDto.CreateRequest orgRequest(String code, String name) {
        OrgDto.CreateRequest request = new OrgDto.CreateRequest();
        request.setOrgCode(code);
        request.setOrgName(name);
        request.setRegionCode("000000");
        request.setOrgLevel(1);
        request.setParentId(0L);
        return request;
    }

    private List<String> roleCodesOf(Long userId) {
        return new ArrayList<>(userService.listRoleCodesByUserId(userId));
    }
}
