package com.guarantee.system.scope;

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

/**
 * 数据范围与机构层级的集成测试（TEST-03 / TEST-13 / SYS-P-15~17 / AC-03 / AC-11）。
 *
 * <p>这些断言只能对着**真实 MySQL** 验证：机构层级由 {@code DataInitializer} 生成，
 * 数据范围靠递归 CTE（{@code selectVisibleOrgIds}）与 Mapper 里的 {@code IN (...)} 实现，
 * 用内存库或 mock 都验不出"省级用户是否真的只看到本省"。</p>
 *
 * <p>依赖演示数据已初始化（{@code guarantee.data-init.enabled=true} 时首次启动会生成）。
 * 若使用空库运行，本测试会在层级断言处失败——这是有意的：数据范围是本需求的核心能力，
 * 没有可验证的层级数据就等于没验证（SYS-P-15 的现状提示）。</p>
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

    @Test
    @DisplayName("部门层级：每机构 1 个总部为顶级，一级/二级部门挂在本机构内（SYS-P-25）")
    void departmentHierarchyShouldBeCorrect() {
        // 非顶级部门的父部门必须与它同机构，且父部门本身不能已删除
        Integer orphans = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_department d
                WHERE d.parent_id <> 0
                  AND NOT EXISTS (SELECT 1 FROM sys_department p
                                  WHERE p.id = d.parent_id AND p.org_id = d.org_id AND p.is_deleted = 0)
                """, Integer.class);
        assertThat(orphans).as("非顶级部门的父部门必须与它同机构且未删除").isZero();

        long orgTotal = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_org WHERE is_deleted = 0", Long.class);
        Integer topLevel = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_department WHERE parent_id = 0 AND is_deleted = 0", Integer.class);
        // 注意用"有部门的机构数"而不是"机构总数"：演示数据已收敛为只有 ORGHQ 有部门（评审要求），
        // 但仍断言"每个有部门的机构恰好 1 个顶级部门"这条不变量
        long deptOrgTotal = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT org_id) FROM sys_department WHERE is_deleted = 0", Long.class);
        assertThat(topLevel).as("每个有部门的机构恰有 1 个顶级部门").isEqualTo((int) deptOrgTotal);
        assertThat(deptOrgTotal).as("有部门的机构数不超过机构总数").isLessThanOrEqualTo((int) orgTotal);

        // 每机构部门数必须一致（规格表驱动，任何机构少一个都说明规格没套全）
        Integer distinctCounts = jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT cnt) FROM (
                    SELECT COUNT(*) AS cnt FROM sys_department WHERE is_deleted = 0 GROUP BY org_id) t
                """, Integer.class);
        assertThat(distinctCounts).as("所有机构的部门数必须相同（同一套 DEPT_SPEC）").isEqualTo(1);

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
    @DisplayName("演示账号的归属机构都是真实机构（SYS-P-24）")
    void demoAccountsBelongToRealOrgs() {
        for (String username : new String[]{"admin", "operator", "analyst", "user0004"}) {
            Long orgId = orgIdOf(username);
            assertThat(orgId).as("%s 必须有归属机构", username).isNotNull();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sys_org WHERE id = ? AND is_deleted = 0", Integer.class, orgId))
                    .as("%s 的归属机构必须存在且未删除", username).isEqualTo(1);
            assertThat(orgLevelOf(orgId)).as("%s 的机构层级必须有值", username).isNotNull();
        }
        assertThat(orgIdOf("admin")).as("admin 归属总部（数据范围演示以 admin 为准）")
                .isEqualTo(headquartersId());
        // 说明：演示数据已收敛为"只有 ORGHQ 一个机构的部门树、所有用户归属总部"，
        // 因此不再断言 operator/analyst 分别处于省级/市级——那需要独立夹具，见 ScopeFixture。
    }

    @Test
    @DisplayName("部门树为全量数据源：节点数不受分页影响，且父节点必在集合内（SYS-C-22 / SYS-C-24 同构）")
    void departmentTreeIsComplete() {
        DataScope all = dataScopeService.resolve(userIdOf("admin"), orgIdOf("admin"), List.of(Roles.ADMIN));

        // 用分页接口取"一页"作为对照：若树错用了分页接口，节点数会等于页大小（默认 10）而不是全量
        DepartmentDto.Query pageQuery = new DepartmentDto.Query();
        pageQuery.setPageNum(1);
        pageQuery.setPageSize(10);
        long pageTotal = departmentService.page(pageQuery, all).total();

        List<DepartmentVO> tree = departmentService.tree(new DepartmentDto.Query(), all);
        assertThat(tree).as("树形数据源必须是全量，绝不能只有一页").hasSize((int) pageTotal);
        // 全量条数必须与库内有效部门数一致（防"树少给了几条"这种静默缺失）
        long activeInDb = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_department WHERE is_deleted = 0", Long.class);
        assertThat(pageTotal).as("列表总数 = 库内有效部门数").isEqualTo(activeInDb);
        assertThat(deptOrgTotal() * DEPTS_PER_ORG).as("有部门的机构数 × 每机构部门数 = 部门总数")
                .isEqualTo(activeInDb);

        // 父节点必须在返回集合内（或为 0），否则前端会组装出游离节点
        List<Long> ids = tree.stream().map(DepartmentVO::getId).toList();
        for (DepartmentVO node : tree) {
            Long parentId = node.getParentId();
            if (parentId != null && parentId != 0L) {
                assertThat(ids).as("部门 %s 的上级 %s 必须在树数据里", node.getDeptName(), parentId)
                        .contains(parentId);
            }
        }

        // 层级形状：每个有部门的机构恰 1 个顶级部门，且每个部门都能带出所属机构
        assertThat(tree.stream().filter(n -> n.getParentId() != null && n.getParentId().equals(0L)).count())
                .as("每个有部门的机构恰有 1 个顶级部门").isEqualTo(deptOrgTotal());
        assertThat(tree.stream().filter(n -> n.getOrgId() == null).count())
                .as("部门必须能带出所属机构（「所属机构」列与根节点标签依赖它）").isZero();
    }

    @Test
    @DisplayName("部门树按数据范围过滤：市级用户只看到本市机构下的部门（SYS-P-08 / AC-08）")
    void departmentTreeIsScoped() {
        ScopeFixture fx = createScopeFixture();
        try {
            // 夹具的市级机构下原本没有部门，先补一个：否则"只看到本市"会退化成"什么都没看到"，验不出过滤
            long cityDeptId = insertDept("TESTFIX-P1C1-D1", "夹具市一部门", fx.city1OrgId, 0);
            long otherDeptId = insertDept("TESTFIX-P2C1-D1", "夹具省二市一部门", fx.otherCityOrgId, 0);
            try {
                List<DepartmentVO> tree = departmentService.tree(new DepartmentDto.Query(), fx.cityScope());
                assertThat(tree).extracting(DepartmentVO::getId).contains(cityDeptId);
                assertThat(tree).extracting(DepartmentVO::getId)
                        .as("跨机构部门的部门不能出现在本市用户的结果里").doesNotContain(otherDeptId);
                assertThat(tree).allSatisfy(vo ->
                        assertThat(vo.getOrgId()).as("不能出现范围外机构的部门").isEqualTo(fx.city1OrgId));
            } finally {
                jdbcTemplate.update("DELETE FROM sys_department WHERE id IN (?, ?)", cityDeptId, otherDeptId);
            }
        } finally {
            fx.cleanup();
        }
    }

    @Test
    @DisplayName("部门树与列表口径一致：同一条件树上的条数等于列表总数")
    void departmentTreeMatchesListCount() {
        DataScope all = dataScopeService.resolve(userIdOf("admin"), orgIdOf("admin"), List.of(Roles.ADMIN));

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
        DataScope all = dataScopeService.resolve(userIdOf("admin"), orgIdOf("admin"), List.of(Roles.ADMIN));

        int defaultSize = departmentService.tree(new DepartmentDto.Query(), all).size();
        long activeInDb = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_department WHERE is_deleted = 0", Long.class);
        assertThat(defaultSize).as("默认视图 = 库内有效部门数").isEqualTo((int) activeInDb);

        DepartmentDto.Query includingDeleted = new DepartmentDto.Query();
        includingDeleted.setIncludeDeleted(true);
        List<DepartmentVO> allNodes = departmentService.tree(includingDeleted, all);

        long deleted = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_department WHERE is_deleted = 1", Long.class);
        long active = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_department WHERE is_deleted = 0", Long.class);
        assertThat(allNodes).as("开启后 = 有效 + 已删除，且不能把有效行漏掉")
                .hasSize((int) (active + deleted));

        // 已删除行的 parentId 必须仍指向同机构的未删除父部门：
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
    // 数据范围（TEST-03 / SYS-P-07 / SYS-P-09 / AC-03）
    // ==================================================================

    @Test
    @DisplayName("ADMIN 为全量范围：可见机构数等于机构总数")
    void adminSeesEverything() {
        DataScope scope = dataScopeService.resolve(userIdOf("admin"), orgIdOf("admin"), List.of(Roles.ADMIN));
        assertThat(scope.unrestricted()).isTrue();
        assertThat(orgService.page(new OrgDto.Query(), scope).total()).isEqualTo(21);
    }

    @Test
    @DisplayName("省级用户只看到「本省 + 其下市级」范围内的机构")
    void provinceUserSeesOnlyOwnProvince() {
        ScopeFixture fx = createScopeFixture();
        try {
            DataScope scope = fx.provinceScope();

            assertThat(scope.unrestricted()).as("省级是受限范围").isFalse();
            assertThat(scope.orgLevel()).isEqualTo(2);
            assertThat(scope.orgIds())
                    .as("本省范围 = 自身 + 2 个市级")
                    .containsExactlyInAnyOrder(fx.provinceOrgId, fx.city1OrgId, fx.city2OrgId);

            long total = orgService.page(new OrgDto.Query(), scope).total();
            assertThat(total).as("该省范围内机构数 = 3（自身 + 2 个市）").isEqualTo(3);

            // 同省另一个市可见；跨省的市不可见（不能因同层级就穿透）
            assertThat(scope.contains(fx.city1OrgId)).as("同省市级应可见").isTrue();
            assertThat(scope.contains(fx.otherCityOrgId)).as("跨省市级不可见").isFalse();
            assertThat(scope.contains(fx.otherProvinceOrgId)).as("跨省省级不可见").isFalse();
            assertThat(scope.contains(fx.hqOrgId)).as("上级（总部）不在省级用户范围内").isFalse();
        } finally {
            fx.cleanup();
        }
    }

    @Test
    @DisplayName("市级用户仅见本市机构：范围为单一机构")
    void cityUserSeesOnlyOwnCity() {
        ScopeFixture fx = createScopeFixture();
        try {
            DataScope scope = fx.cityScope();

            assertThat(scope.orgLevel()).isEqualTo(3);
            assertThat(scope.isSingleOrg()).as("市级只可见本市").isTrue();
            assertThat(scope.orgIds()).containsExactly(fx.city1OrgId);

            List<OrgVO> list = orgService.page(new OrgDto.Query(), scope).list();
            assertThat(list).hasSize(1);
            assertThat(list.get(0).getId()).isEqualTo(fx.city1OrgId);

            // 同省的另一个市不可见——这是市级范围最容易出错的地方
            assertThat(scope.contains(fx.city2OrgId)).as("同省其它市不可见").isFalse();
        } finally {
            fx.cleanup();
        }
    }

    @Test
    @DisplayName("跨范围查询返回 0 结果，且不暴露存在性（SYS-P-09）")
    void crossScopeQueryReturnsNothing() {
        ScopeFixture fx = createScopeFixture();
        try {
            DataScope scope = fx.cityScope();

            // 明确查询同省另一个市的机构名称：目标是存在的，但当前用户看不到
            OrgDto.Query query = new OrgDto.Query();
            query.setPageNum(1);
            query.setPageSize(10);
            query.setOrgName(fx.city2Name);
            assertThat(orgService.page(query, scope).total())
                    .as("跨范围目标必须表现为 0 条，不能暴露它是否存在")
                    .isZero();
        } finally {
            fx.cleanup();
        }
    }

    @Test
    @DisplayName("用户列表同样受机构范围过滤（SYS-P-08 覆盖列表查询）")
    void userListIsScoped() {
        ScopeFixture fx = createScopeFixture();
        try {
            DataScope all = dataScopeService.resolve(userIdOf("admin"), orgIdOf("admin"), List.of(Roles.ADMIN));
            DataScope city = fx.cityScope();

            UserDto.Query allQuery = new UserDto.Query();
            allQuery.setPageNum(1);
            allQuery.setPageSize(1);
            long allTotal = userService.page(allQuery, all).total();

            UserDto.Query cityQuery = new UserDto.Query();
            cityQuery.setPageNum(1);
            cityQuery.setPageSize(1);
            long cityTotal = userService.page(cityQuery, city).total();

            long activeUsers = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sys_user WHERE is_deleted = 0", Long.class);
            assertThat(allTotal).as("平台总用户数（含夹具用户）").isEqualTo(activeUsers);
            assertThat(cityTotal).as("市级用户只看到本市 + 夹具用户，必然少于全量")
                    .isLessThan(allTotal).isGreaterThan(0);
        } finally {
            fx.cleanup();
        }
    }

    @Test
    @DisplayName("机构树的节点数等于范围内机构数，不因分页缺节点（SYS-C-24 / AC-31）")
    void orgTreeIsComplete() {
        DataScope all = dataScopeService.resolve(userIdOf("admin"), orgIdOf("admin"), List.of(Roles.ADMIN));
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
    @DisplayName("机构查询补齐 parentName / deptCount / userCount（SYS-Q-01 出参）")
    void orgQueryFillsDerivedFields() {
        DataScope all = dataScopeService.resolve(userIdOf("admin"), orgIdOf("admin"), List.of(Roles.ADMIN));
        OrgDto.Query query = new OrgDto.Query();
        query.setPageNum(1);
        query.setPageSize(50);
        List<OrgVO> list = orgService.listForQuery(query, all);

        // 演示数据已收敛为"只有 ORGHQ 有部门/用户"，因此派生化字段取总部验证
        OrgVO headquarters = list.stream()
                .filter(vo -> vo.getId().equals(headquartersId())).findFirst()
                .orElseThrow(() -> new AssertionError("总部不存在"));
        assertThat(headquarters.getParentName()).as("总部没有上级").isNull();
        assertThat(headquarters.getDeptCount()).as("总部下部门数不少于 3").isGreaterThanOrEqualTo(3L);
        assertThat(headquarters.getUserCount()).as("总部下启用用户数不为空").isNotNull();

        // 省级机构的上级仍是总部，用来验证 parentName 的关联取数（不依赖它下面有部门）
        OrgVO province = list.stream()
                .filter(vo -> vo.getId().equals(2L)).findFirst()
                .orElseThrow(() -> new AssertionError("浙江省省级机构不存在"));
        assertThat(province.getParentName()).as("省级机构的上级是总部").isEqualTo("平台总部");
    }

    @Test
    @DisplayName("queryUser 的白名单字段来源：VO 本身不含 password（SYS-P-11 前提）")
    void userVoHasNoPasswordField() {
        DataScope all = dataScopeService.resolve(userIdOf("admin"), orgIdOf("admin"), List.of(Roles.ADMIN));
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

    /**
     * 每机构部门数（= `DataInitializer.DEPT_SPEC` 的节点数）。
     *
     * <p>测试不该把这个数字写死在不同地方：规格表改动时只改这一处，
     * 其余断言用 {@code 机构数 × DEPTS_PER_ORG} 表达。</p>
     */
    private static final int DEPTS_PER_ORG = 11;

    private long orgTotal() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_org WHERE is_deleted = 0", Long.class);
    }

    /** 库内有效部门数（不写死，随部门树规格变化）。 */
    private long deptTotal() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_department WHERE is_deleted = 0", Long.class);
    }

    /** 库内部门涉及的机构数（用于"每机构 1 个顶级部门"这类断言）。 */
    private long deptOrgTotal() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT org_id) FROM sys_department WHERE is_deleted = 0", Long.class);
    }

    // ==================================================================
    // 数据范围夹具（SYS-P-07 / SYS-P-09）
    // ==================================================================

    /**
     * 数据范围夹具：3 层机构 + 2 个用户，用于验证"省级看本省及下级、市级只看本市"。
     *
     * <p><b>为什么需要夹具而不复用演示数据</b>：演示数据已被收敛为"只有 ORGHQ 一个机构的部门树、
     * 所有用户归属总部"（评审要求），那时所有账号的可见范围都与 ADMIN 相同，
     * 省级/市级这两条最关键的越权防线就没有数据可验了。数据范围是 SYS-P-07 的安全边界，
     * 不能因为演示数据变简单就失去覆盖，因此这里自建夹具。</p>
     *
     * <p>夹具用 {@code TESTFIX} 前缀命名，且**物理删除**——本测试类不在事务里，
     * 逻辑删除会留在库里影响其他用例的统计（本仓库踩过这个坑）。</p>
     */
    private final class ScopeFixture {
        private final long hqOrgId;
        private final long provinceOrgId;
        private final long city1OrgId;
        private final long city2OrgId;
        private final long otherProvinceOrgId;
        private final long otherCityOrgId;
        private final long provinceUserId;
        private final long cityUserId;
        private final String city2Name;

        private ScopeFixture(long hqOrgId, long provinceOrgId, long city1OrgId, long city2OrgId,
                             long otherProvinceOrgId, long otherCityOrgId,
                             long provinceUserId, long cityUserId, String city2Name) {
            this.hqOrgId = hqOrgId;
            this.provinceOrgId = provinceOrgId;
            this.city1OrgId = city1OrgId;
            this.city2OrgId = city2OrgId;
            this.otherProvinceOrgId = otherProvinceOrgId;
            this.otherCityOrgId = otherCityOrgId;
            this.provinceUserId = provinceUserId;
            this.cityUserId = cityUserId;
            this.city2Name = city2Name;
        }

        DataScope provinceScope() {
            return dataScopeService.resolve(provinceUserId, provinceOrgId, List.of(Roles.OPERATOR));
        }

        DataScope cityScope() {
            return dataScopeService.resolve(cityUserId, city1OrgId, List.of(Roles.ANALYST));
        }

        void cleanup() {
            for (long uid : new long[]{provinceUserId, cityUserId}) {
                jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id = ?", uid);
                jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", uid);
            }
            for (long orgId : new long[]{city1OrgId, city2OrgId, otherCityOrgId,
                    provinceOrgId, otherProvinceOrgId, hqOrgId}) {
                jdbcTemplate.update("DELETE FROM sys_org WHERE id = ?", orgId);
            }
        }
    }

    private ScopeFixture createScopeFixture() {
        long hq = insertOrg("TESTFIX-HQ", "夹具总部", "990000", "夹具区", 1, 0);
        long province = insertOrg("TESTFIX-P1", "夹具省一", "991000", "夹具省一", 2, hq);
        long city1 = insertOrg("TESTFIX-P1C1", "夹具省一市一", "991100", "夹具市一", 3, province);
        long city2 = insertOrg("TESTFIX-P1C2", "夹具省一市二", "991200", "夹具市二", 3, province);
        long otherProvince = insertOrg("TESTFIX-P2", "夹具省二", "992000", "夹具省二", 2, hq);
        long otherCity = insertOrg("TESTFIX-P2C1", "夹具省二市一", "992100", "夹具省市一", 3, otherProvince);

        long provinceUser = insertUser("testfix_province", province);
        long cityUser = insertUser("testfix_city", city1);

        return new ScopeFixture(hq, province, city1, city2, otherProvince, otherCity,
                provinceUser, cityUser, "夹具省一市二");
    }

    private long insertOrg(String code, String name, String regionCode, String regionName, int level, long parentId) {
        jdbcTemplate.update("""
                INSERT INTO sys_org (org_code, org_name, region_code, region_name, org_level, parent_id, status, sort_no)
                VALUES (?, ?, ?, ?, ?, ?, 1, 0)
                """, code, name, regionCode, regionName, level, parentId);
        return jdbcTemplate.queryForObject("SELECT id FROM sys_org WHERE org_code = ?", Long.class, code);
    }

    private long insertDept(String code, String name, long orgId, long parentId) {
        jdbcTemplate.update("""
                INSERT INTO sys_department (dept_code, dept_name, org_id, parent_id, status, sort_no)
                VALUES (?, ?, ?, ?, 1, 999)
                """, code, name, orgId, parentId);
        return jdbcTemplate.queryForObject("SELECT id FROM sys_department WHERE dept_code = ?", Long.class, code);
    }

    private long insertUser(String username, long orgId) {
        long roleId = jdbcTemplate.queryForObject("SELECT id FROM sys_role WHERE role_code = 'VIEWER'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO sys_user (username, password, real_name, org_id, status)
                VALUES (?, ?, ?, ?, 1)
                """, username, "$2a$10$fixturefixturefixturefixturefixturefixturefixturefixtur", "夹具用户", orgId);
        long uid = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
        jdbcTemplate.update("INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)", uid, roleId);
        return uid;
    }

    private Long userIdOf(String username) {
        return jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
    }

    private Long orgIdOf(String username) {
        return jdbcTemplate.queryForObject("SELECT org_id FROM sys_user WHERE username = ?", Long.class, username);
    }

    private Integer orgLevelOf(Long orgId) {
        return jdbcTemplate.queryForObject("SELECT org_level FROM sys_org WHERE id = ?", Integer.class, orgId);
    }

    private Long headquartersId() {
        return jdbcTemplate.queryForObject("SELECT id FROM sys_org WHERE org_level = 1", Long.class);
    }
}
