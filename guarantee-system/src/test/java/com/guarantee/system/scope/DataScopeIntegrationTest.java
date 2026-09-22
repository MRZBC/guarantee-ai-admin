package com.guarantee.system.scope;

import com.guarantee.common.exception.BizException;
import com.guarantee.common.security.Roles;
import com.guarantee.system.dto.DepartmentDto;
import com.guarantee.system.dto.OrgDto;
import com.guarantee.system.dto.UserDto;
import com.guarantee.system.service.DepartmentService;
import com.guarantee.system.service.OrgService;
import com.guarantee.system.service.UserService;
import com.guarantee.system.vo.DepartmentVO;
import com.guarantee.system.vo.OrgVO;
import com.guarantee.system.vo.UserVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 机构层级 / 部门层级 / 数据范围的集成测试（TEST-03 / TEST-13 / SYS-P-15~17 / AC-03 / AC-11）。
 *
 * <p><b>领域模型前提（本次重构后）</b>：</p>
 * <ul>
 *   <li>{@code sys_org} 是**外部出函机构**，服务于**订单**；</li>
 *   <li>{@code sys_department} 是内部组织单元，服务于**人**，用户必须属于一个部门；</li>
 *   <li>用户与部门**都不再有机构字段**（{@code sys_user.org_id} / {@code sys_department.org_id}
 *       已由 {@code V4__drop_org_from_user_and_dept.sql} 删除）。</li>
 * </ul>
 *
 * <p><b>分级数据范围已废弃（阶段一 O3 显式全量）</b>：
 * 原先"省级用户只看本省、市级用户只看本市"的用例需要给用户/部门设不同机构来构造场景，
 * 而机构归属已从用户与部门上移除，这些用例**无法成立，已整体删除**
 * （连同 {@code ScopeFixture} 夹具）。取而代之的是
 * {@link DataScopeService#resolve(Long, List)} 恒返回全量
 * （{@code DataScope.all(...)}）的断言：阶段一与重构前的实际行为等价（重构前所有人挂总部 = 全量）。
 * 阶段二将以权限码重建授权模型，详见
 * {@code docs/PLAN-移除用户与部门的机构归属.md} §2.3 / §3 / §8（C6、Q5）。</p>
 *
 * <p>这些断言只能对着**真实 MySQL** 验证：机构层级由 {@code DataInitializer} 生成，
 * 部门树靠 {@code parent_id} 组装，用内存库或 mock 都验不出"树是否完整、父节点是否都在集合内"。</p>
 *
 * <p>依赖演示数据已初始化（{@code guarantee.data-init.enabled=true} 时首次启动会生成）。
 * 若使用空库运行，本测试会在层级断言处失败——这是有意的：层级数据是这些不变量的验证前提。</p>
 */
@SpringBootTest(classes = com.guarantee.system.ItMybatisConfig.class)
@TestPropertySource(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/schema.sql"
})
class DataScopeIntegrationTest {

    @Autowired
    private DataScopeService dataScopeService;

    @Autowired
    private OrgService orgService;

    @Autowired
    private UserService userService;

    @Autowired
    private DepartmentService departmentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ==================================================================
    // 机构层级（SYS-P-16 / TEST-13 / AC-11）
    // ==================================================================

    @Test
    @DisplayName("机构层级：总部唯一、每个区域恰有 1 个省级、市级父级指向本区域省级")
    void orgHierarchyShouldBeCorrect() {
        Integer total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_org", Integer.class);
        assertThat(total).as("机构总数 = 1 总部 + 20 区域机构（SYS-P-16）").isEqualTo(21);

        Integer headquarters = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_org WHERE org_level = 1", Integer.class);
        assertThat(headquarters).as("总部节点唯一").isEqualTo(1);

        Map<String, Object> hq = jdbcTemplate.queryForMap(
                "SELECT id, org_code, parent_id FROM sys_org WHERE org_level = 1");
        assertThat(hq.get("org_code")).isEqualTo("ORGHQ");
        assertThat(((Number) hq.get("parent_id")).longValue()).isZero();

        long hqId = ((Number) hq.get("id")).longValue();

        Integer provinces = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_org WHERE org_level = 2", Integer.class);
        assertThat(provinces).as("8 个区域各 1 个省级机构").isEqualTo(8);

        Integer provinceParentOk = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_org WHERE org_level = 2 AND parent_id = ?",
                Integer.class, hqId);
        assertThat(provinceParentOk).as("所有省级机构的父级都是总部").isEqualTo(8);

        // 市级：父级必须是一个省级机构，且同区域
        Integer cityTotal = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_org WHERE org_level = 3", Integer.class);
        assertThat(cityTotal).as("区域机构 20 - 省级 8 = 12 个市级").isEqualTo(12);

        Integer cityBad = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_org c
                LEFT JOIN sys_org p ON p.id = c.parent_id
                WHERE c.org_level = 3
                  AND (p.id IS NULL OR p.org_level <> 2 OR p.region_code <> c.region_code)
                """, Integer.class);
        assertThat(cityBad).as("每个市级机构的父级必须是同区域的省级机构（不能平铺、不能跨区域）")
                .isZero();
    }

    // ==================================================================
    // 部门层级（SYS-P-25）：纯部门树，不再有机构归属
    // ==================================================================

    @Test
    @DisplayName("部门层级：整库恰好 1 个顶级部门，其余部门的父部门都真实存在且未删除")
    void departmentHierarchyShouldBeCorrect() {
        // 机构已从部门上移除，因此这里不再有"同机构"约束，只剩"父必须存在且未删除"
        Integer orphans = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_department d
                WHERE d.parent_id <> 0
                  AND NOT EXISTS (SELECT 1 FROM sys_department p
                                  WHERE p.id = d.parent_id AND p.is_deleted = 0)
                """, Integer.class);
        assertThat(orphans).as("非顶级部门的父部门必须存在且未删除").isZero();

        // 部门树已收敛为单棵纯部门树（原"每机构 1 个顶级部门"随机构归属一并失效）
        Integer topLevel = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_department WHERE parent_id = 0 AND is_deleted = 0", Integer.class);
        assertThat(topLevel).as("部门树只有 1 个顶级节点（没有机构根节点了）").isEqualTo(1);

        // 层级不止一层：必须存在"挂在非顶级部门下"的二级部门（否则就是平铺，层级语义没落地）
        Integer secondLevel = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_department d
                JOIN sys_department p ON p.id = d.parent_id
                WHERE d.is_deleted = 0 AND p.parent_id <> 0
                """, Integer.class);
        assertThat(secondLevel).as("存在二级部门（业务部/技术部下面还有部门），层级不是一层平铺")
                .isPositive();
    }

    @Test
    @DisplayName("用户必须挂在真实存在的部门上（「用户必须属于一个部门」）")
    void everyUserBelongsToRealDepartment() {
        // 列已收紧为 NOT NULL，库里不允许出现"无部门用户"
        Integer noDept = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE is_deleted = 0 AND dept_id IS NULL", Integer.class);
        assertThat(noDept).as("不存在无部门的有效用户（dept_id 已收紧为 NOT NULL）").isZero();

        Integer orphans = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_user u
                WHERE u.is_deleted = 0
                  AND NOT EXISTS (SELECT 1 FROM sys_department d
                                  WHERE d.id = u.dept_id AND d.is_deleted = 0)
                """, Integer.class);
        assertThat(orphans).as("每个有效用户都必须指向一个真实存在且未删除的部门").isZero();

        for (String username : new String[]{"admin", "operator", "analyst", "user0004"}) {
            Long deptId = deptIdOf(username);
            assertThat(deptId).as("%s 必须有归属部门", username).isNotNull();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sys_department WHERE id = ? AND is_deleted = 0",
                    Integer.class, deptId))
                    .as("%s 的归属部门必须存在且未删除", username).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("部门树为全量数据源：节点数不受分页影响，且父节点必在集合内（SYS-C-22 / SYS-C-24 同构）")
    void departmentTreeIsComplete() {
        DataScope all = dataScopeService.resolve(userIdOf("admin"), List.of(Roles.ADMIN));

        // 用分页接口取"一页"作为对照：若树错用了分页接口，节点数会等于页大小（默认 10）而不是全量
        DepartmentDto.Query pageQuery = new DepartmentDto.Query();
        pageQuery.setPageNum(1);
        pageQuery.setPageSize(10);
        long pageTotal = departmentService.page(pageQuery, all).total();

        List<DepartmentVO> tree = departmentService.tree(new DepartmentDto.Query(), all);
        assertThat(tree).as("树形数据源必须是全量，绝不能只有一页").hasSize((int) pageTotal);
        // 全量条数必须与库内有效部门数一致（防"树少给了几条"这种静默缺失）
        long activeInDb = deptTotal();
        assertThat(pageTotal).as("列表总数 = 库内有效部门数").isEqualTo(activeInDb);

        // 父节点必须在返回集合内（或为 0），否则前端会组装出游离节点
        List<Long> ids = tree.stream().map(DepartmentVO::getId).toList();
        for (DepartmentVO node : tree) {
            Long parentId = node.getParentId();
            if (parentId != null && parentId != 0L) {
                assertThat(ids).as("部门 %s 的上级 %s 必须在树数据里", node.getDeptName(), parentId)
                        .contains(parentId);
            }
        }

        // 层级形状：整棵树只有 1 个顶级部门（机构根节点已随机构归属移除）
        assertThat(tree.stream().filter(n -> n.getParentId() != null && n.getParentId().equals(0L)).count())
                .as("纯部门树只有 1 个顶级部门").isEqualTo(1L);
    }

    @Test
    @DisplayName("部门树与列表口径一致：同一条件树上的条数等于列表总数")
    void departmentTreeMatchesListCount() {
        DataScope all = dataScopeService.resolve(userIdOf("admin"), List.of(Roles.ADMIN));

        DepartmentDto.Query listQuery = new DepartmentDto.Query();
        listQuery.setPageNum(1);
        listQuery.setPageSize(1);
        listQuery.setDeptName("业务部");
        long listed = departmentService.page(listQuery, all).total();

        DepartmentDto.Query treeQuery = new DepartmentDto.Query();
        treeQuery.setDeptName("业务部");
        List<DepartmentVO> tree = departmentService.tree(treeQuery, all);

        long expectedInDb = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_department WHERE is_deleted = 0 AND dept_name LIKE '%业务部%'", Long.class);
        assertThat(listed).as("『业务部』命中的部门数应与库内一致").isEqualTo(expectedInDb);
        assertThat(tree).as("树与列表必须共用 queryWhere，条数应一致；不一致说明树另写了一套 WHERE")
                .hasSize((int) listed);
    }

    @Test
    @DisplayName("部门树的 includeDeleted：默认不含已删除，开启后已删除节点仍在原层级（LD-04b）")
    void departmentTreeHonoursIncludeDeleted() {
        DataScope all = dataScopeService.resolve(userIdOf("admin"), List.of(Roles.ADMIN));

        int defaultSize = departmentService.tree(new DepartmentDto.Query(), all).size();
        long activeInDb = deptTotal();
        assertThat(defaultSize).as("默认视图 = 库内有效部门数").isEqualTo((int) activeInDb);

        DepartmentDto.Query includingDeleted = new DepartmentDto.Query();
        includingDeleted.setIncludeDeleted(true);
        List<DepartmentVO> allNodes = departmentService.tree(includingDeleted, all);

        long deleted = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_department WHERE is_deleted = 1", Long.class);
        assertThat(allNodes).as("开启后 = 有效 + 已删除，且不能把有效行漏掉")
                .hasSize((int) (activeInDb + deleted));

        // 已删除行的 parentId 必须仍指向未删除的父部门：
        // 删父部门要求"无未删除下级"，所以正常情况下不会出现"父已删除、子还在"的孤儿
        List<Long> nodeIds = allNodes.stream().map(DepartmentVO::getId).toList();
        for (DepartmentVO node : allNodes) {
            Long parentId = node.getParentId();
            if (parentId != null && parentId != 0L) {
                assertThat(nodeIds).as("已删除部门 %s 的父级也应出现在「显示已删除」视图里",
                        node.getDeptName()).contains(parentId);
            }
        }
    }

    // ==================================================================
    // 数据范围（阶段一 O3：显式全量）
    // ==================================================================

    @Test
    @DisplayName("O3 显式全量：ADMIN 的范围不受限，机构查询返回全部机构")
    void adminSeesEverything() {
        DataScope scope = dataScopeService.resolve(userIdOf("admin"), List.of(Roles.ADMIN));
        assertThat(scope.unrestricted()).isTrue();
        assertThat(orgService.page(new OrgDto.Query(), scope).total()).isEqualTo(21);
    }

    @Test
    @DisplayName("O3 显式全量：非 ADMIN（含只读角色）同样看到全部机构与全部用户")
    void nonAdminAlsoSeesEverything() {
        long activeOrgs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_org WHERE is_deleted = 0", Long.class);
        long activeUsers = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE is_deleted = 0", Long.class);

        for (String role : new String[]{Roles.OPERATOR, Roles.ANALYST, Roles.VIEWER}) {
            DataScope scope = dataScopeService.resolve(userIdOf("analyst"), List.of(role));

            assertThat(scope.unrestricted()).as("%s 也是全量范围（阶段一 O3）", role).isTrue();

            OrgDto.Query orgQuery = new OrgDto.Query();
            orgQuery.setPageNum(1);
            orgQuery.setPageSize(1);
            assertThat(orgService.page(orgQuery, scope).total())
                    .as("%s 可见机构数 = 机构总数（阶段一与重构前等价：所有人都挂总部）", role)
                    .isEqualTo(activeOrgs);

            UserDto.Query userQuery = new UserDto.Query();
            userQuery.setPageNum(1);
            userQuery.setPageSize(1);
            assertThat(userService.page(userQuery, scope).total())
                    .as("%s 可见用户数 = 用户总数", role)
                    .isEqualTo(activeUsers);
        }
    }

    @Test
    @DisplayName("机构树的节点数等于范围内机构数，不因分页缺节点（SYS-C-24 / AC-31）")
    void orgTreeIsComplete() {
        DataScope all = dataScopeService.resolve(userIdOf("admin"), List.of(Roles.ADMIN));
        List<OrgVO> tree = orgService.tree(new OrgDto.Query(), all);
        assertThat(tree).as("树形数据源必须是全量，不受分页影响").hasSize(21);

        // parentId 必须在返回集合里（或为 0），否则前端会组装出游离节点
        List<Long> ids = tree.stream().map(OrgVO::getId).toList();
        for (OrgVO node : tree) {
            Long parentId = node.getParentId();
            if (parentId != null && parentId != 0L) {
                assertThat(ids).as("节点 %s 的父级 %s 必须在树数据里", node.getOrgName(), parentId)
                        .contains(parentId);
            }
        }
    }

    @Test
    @DisplayName("机构查询补齐 parentName（机构只有自身属性，deptCount/userCount 已随机构归属移除）")
    void orgQueryFillsParentName() {
        DataScope all = dataScopeService.resolve(userIdOf("admin"), List.of(Roles.ADMIN));
        OrgDto.Query query = new OrgDto.Query();
        query.setPageNum(1);
        query.setPageSize(50);
        List<OrgVO> list = orgService.listForQuery(query, all);

        OrgVO headquarters = list.stream()
                .filter(vo -> vo.getId().equals(headquartersId())).findFirst()
                .orElseThrow(() -> new AssertionError("总部不存在"));
        assertThat(headquarters.getParentName()).as("总部没有上级").isNull();

        // 省级机构的上级仍是总部，用来验证 parentName 的关联取数（不依赖部门/用户）
        OrgVO province = list.stream()
                .filter(vo -> vo.getId().equals(2L)).findFirst()
                .orElseThrow(() -> new AssertionError("浙江省省级机构不存在"));
        assertThat(province.getParentName()).as("省级机构的上级是总部").isEqualTo("平台总部");
    }

    @Test
    @DisplayName("目标不存在时仍抛统一文案的 404 语义，不暴露存在性（SYS-P-09）")
    void requireVisibleOrgUsesUnifiedMessage() {
        DataScope scope = dataScopeService.resolve(userIdOf("admin"), List.of(Roles.ADMIN));

        assertThatThrownBy(() -> dataScopeService.requireVisibleOrg(scope, -1L))
                .as("目标不存在时的文案必须与越权一致，使探测无法区分两种情况")
                .isInstanceOf(BizException.class)
                .hasMessage(DataScopeService.OUT_OF_SCOPE_MESSAGE);
    }

    @Test
    @DisplayName("queryUser 的白名单字段来源：VO 本身不含 password（SYS-P-11 前提）")
    void userVoHasNoPasswordField() {
        DataScope all = dataScopeService.resolve(userIdOf("admin"), List.of(Roles.ADMIN));
        UserDto.Query query = new UserDto.Query();
        query.setPageNum(1);
        query.setPageSize(1);
        List<UserVO> list = userService.page(query, all).list();
        assertThat(list).isNotEmpty();
        // VO 类型上不存在 password 属性，工具层再做白名单组装（RK-08 的双保险）
        assertThat(java.util.Arrays.stream(UserVO.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName))
                .doesNotContain("password");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** 库内有效部门数（不写死，随部门树规格变化）。 */
    private long deptTotal() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_department WHERE is_deleted = 0", Long.class);
    }

    private Long userIdOf(String username) {
        return jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
    }

    private Long deptIdOf(String username) {
        return jdbcTemplate.queryForObject("SELECT dept_id FROM sys_user WHERE username = ?", Long.class, username);
    }

    private Long headquartersId() {
        return jdbcTemplate.queryForObject("SELECT id FROM sys_org WHERE org_level = 1", Long.class);
    }
}
