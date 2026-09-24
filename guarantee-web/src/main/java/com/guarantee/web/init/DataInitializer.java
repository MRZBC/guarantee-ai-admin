package com.guarantee.web.init;

import com.guarantee.common.security.DefaultCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 演示数据初始化器。
 *
 * <p><b>不是纯随机数据。</b>固定随机种子 {@code 20260920}，并显式注入业务规律，
 * 使后续 AI 分析可以产生可验证的结论：</p>
 * <ul>
 *   <li>区域分布：浙江省占比最高，江苏次之，其后广东/山东/四川/湖北/北京/上海；</li>
 *   <li>机构季节性：部分机构 2026 Q3 环比增长，部分机构下降；</li>
 *   <li>月份季节性：2 月（春节）最低，Q3 为全年高点；</li>
 *   <li>险种分布：不同区域在投标/履约保函上的结构不同。</li>
 * </ul>
 *
 * <p>生成顺序与随机数消耗顺序完全固定，因此每次初始化得到完全相同的数据。</p>
 */
@Component
@ConditionalOnProperty(name = "guarantee.data-init.enabled", havingValue = "true", matchIfMissing = true)
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private static final LocalDate DATA_START = LocalDate.of(2025, 1, 1);
    private static final LocalDate DATA_END = LocalDate.of(2026, 9, 30);

    /**
     * 总部节点数量（D-3）：不承保业务，只作为机构树的唯一根与 ADMIN 的归属。
     */
    private static final int HEADQUARTERS = 1;

    /**
     * 机构**总数** = 总部 + 区域机构。
     *
     * <p>该常量驱动 {@code sys_department} 的分配公式与用户机构分配，机构表新增总部节点后
     * 必须同步为 21，否则部门/用户会挂到错误的机构（SYS-P-17 / SYS-P-22）。</p>
     */
    private static final int ORG_COUNT = HEADQUARTERS + 20;

    /** 总部节点机构编码。 */
    private static final String HEADQUARTERS_CODE = "ORGHQ";

    /**
     * admin 归属的部门名（即 {@link #DEPT_SPEC} 里那个根节点，也是总部机构下的第一个部门）。
     *
     * <p>实库里 admin 的 {@code dept_name = '总部'}；用户必须属于一个部门
     * （{@code sys_user.dept_id} 为 NOT NULL），因此初始化时按**名字**给 admin 选部门，
     * 不硬编码部门 id。</p>
     */
    private static final String HEADQUARTERS_DEPT_NAME = "总部";

    /** 总部节点行政区划占位（仅占位，不参与区域统计）。 */
    private static final String HEADQUARTERS_REGION = "110000";

    /**
     * 部门**总数** = 机构数 × 每机构部门数。
     *
     * <p>在字段初始化处**不做跨常量引用**（Java 常量初始化不允许前向引用，
     * 而 {@code DEPT_SPEC} 定义在下方便于就近阅读）。此处按规格表长度写死表达式，
     * 并在 {@link #assertDeptCountConsistent()} 里校验它与规格表一致——
     * 校验失败会在启动时报错，不会静默失配。</p>
     */
    private static final int DEPT_COUNT = ORG_COUNT * 11;

    private static final int USER_COUNT = 300;
    private static final int ENTERPRISE_COUNT = 3000;
    private static final int PROJECT_COUNT = 5000;
    private static final int TENDER_ORDER_COUNT = 100_000;
    private static final int PERFORMANCE_ORDER_COUNT = 50_000;

    private static final int BATCH_SIZE = 2000;

    /** 演示账号密码。 */
    private static final String ADMIN_PASSWORD = "Admin@123";
    private static final String OPERATOR_PASSWORD = "Operator@123";
    private static final String ANALYST_PASSWORD = "Analyst@123";

    /**
     * 演示用户的默认密码。
     *
     * <p>刻意引用 {@link DefaultCredentials#BUILT_IN_DEFAULT_PASSWORD} 而**不是**再写一遍字面量：
     * P-10 起"新建账号 / 管理员重置密码"也写这个值，两处同值定义会漂移，
     * 而"以为改了一处、实际生效的是另一处"是最难排查的一类问题。</p>
     *
     * <p>注意这里用的是**内置常量**而非可配置值 {@code DefaultCredentials.defaultPassword()}：
     * 演示数据种子是开发/测试用途，应当稳定可预期；生产环境若要覆盖初始密码，
     * 覆盖的是业务路径（新建/重置），不该反过来改演示数据。</p>
     */
    private static final String DEFAULT_PASSWORD = DefaultCredentials.BUILT_IN_DEFAULT_PASSWORD;

    /** 区域权重（合计 100）。浙江最高、江苏次之。 */
    private static final String[][] REGIONS = {
            {"330000", "浙江省", "35", "6"},
            {"320000", "江苏省", "24", "4"},
            {"440000", "广东省", "14", "3"},
            {"370000", "山东省", "9", "2"},
            {"510000", "四川省", "6", "2"},
            {"420000", "湖北省", "5", "1"},
            {"110000", "北京市", "4", "1"},
            {"310000", "上海市", "3", "1"},
    };

    /** 月度季节性系数：2 月最低，Q3 最高。 */
    private static final double[] MONTH_FACTOR = {
            0.85, 0.60, 1.05, 1.10, 1.15, 1.20, 1.25, 1.30, 1.35, 1.10, 1.05, 0.95
    };

    /**
     * 机构 2026 Q3 相对基线的系数。
     *
     * <p>索引 == 区域机构序号（0..19，**不含总部**）。前 7 个机构明显增长，中间 7 个基本持平，
     * 最后 6 个明显下降，用于让「哪些机构 Q3 在增长/下降」成为可验证结论。</p>
     */
    private static final double[] ORG_Q3_FACTOR = {
            1.45, 1.38, 1.30, 1.24, 1.18, 1.12, 1.08,
            1.02, 1.00, 0.99, 0.98, 0.97, 0.96, 0.95,
            0.82, 0.78, 0.74, 0.70, 0.66, 0.60
    };

    private static final String[] INDUSTRIES = {
            "建筑工程", "市政工程", "交通运输", "水利水电", "电力能源", "通信信息", "园林绿化", "装饰装修"
    };

    private static final String[] PROJECT_TYPES = {"房建", "市政", "交通", "水利", "其他"};

    private static final String[] ENT_LEVELS = {"AAA", "AA", "A", "BBB"};

    private final JdbcTemplate jdbcTemplate;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 每个机构**实际创建**的部门 id 列表（机构下标 → 部门 id 列表）。
     *
     * <p><b>为什么必须查表而不是算公式</b>：早期实现用
     * {@code deptId = orgIndex + 1 + ORG_COUNT * slot} 推导，一旦部门总数不能被机构数整除
     * （当时的 80 就不能被 21 整除），最后一个机构会算出 {@code 85} 这类**并不存在**的部门 id，
     * 使这批用户的 {@code dept_id} 指向空记录——列表里"所属部门"永远为空，
     * 且按部门统计用户数时会静默少算。改成规格表驱动后部门数会随规格变化，
     * 算术推导只会更容易出错，因此坚持查表。</p>
     */
    private final Map<Integer, List<Long>> orgDeptIds = new LinkedHashMap<>();

    @Value("${guarantee.data-init.seed:20260920}")
    private long seed;

    public DataInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Integer existing = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class);
        if (existing != null && existing > 0) {
            log.info("检测到已有业务数据（sys_user={} 行），跳过演示数据初始化", existing);
            return;
        }
        long start = System.currentTimeMillis();
        Random random = new Random(seed);
        log.info("开始初始化演示数据，随机种子={}", seed);

        assertDeptCountConsistent();
        List<OrgRow> orgs = seedOrgs();
        seedDepartments(orgs);
        seedRolesAndPermissions();
        seedUsers(random, orgs);
        List<InsuranceRow> insuranceTypes = seedInsuranceTypes();
        List<EnterpriseRow> enterprises = seedEnterprises(random);
        List<ProjectRow> projects = seedProjects(random, enterprises);

        // 订单采样只在 20 个区域机构中进行，总部不承保业务（SYS-P-23）。
        // 必须显式排除，而不是依赖"总部恰好没有订单"。
        List<OrgRow> orderOrgs = regionOrgs(orgs);
        seedTenderOrders(random, orderOrgs, insuranceTypes, enterprises, projects);
        seedPerformanceOrders(random, orderOrgs, insuranceTypes, enterprises, projects);

        log.info("演示数据初始化完成，耗时 {} ms", System.currentTimeMillis() - start);
    }

    // ==================================================================
    // 机构 / 部门 / 角色 / 用户
    // ==================================================================

    private record OrgRow(long id, String code, String name, String regionCode, String regionName) {
    }

    /**
     * 生成机构层级（D-3 / SYS-P-15 / SYS-P-16 / SYS-P-21）。
     *
     * <p><b>修复前的缺陷</b>：{@code parent_id} 固定写 0、{@code org_level} 只有 1 与 2，
     * 层级树实际是平铺的，导致 SYS-P-07 的"省级可见全省 / 市级仅见本市"数据范围无法验证。</p>
     *
     * <p><b>修复后的结构</b>：1 个总部（{@code org_level=1}、{@code parent_id=0}）+
     * 每区域按原数量保留机构（合计 20，区域权重不变，SYS-P-16a），其中区域内第 1 个为省级
     * （{@code org_level=2}、父级 = 总部）、其余为市级（{@code org_level=3}、父级 = 该区域省级）。</p>
     *
     * <p>总部不参与订单采样（SYS-P-23），因此 {@link DaySampler} 只在区域机构中采样，
     * 调用方必须传入 {@link #regionOrgs(List)} 的结果。</p>
     */
    private List<OrgRow> seedOrgs() {
        List<OrgRow> orgs = new ArrayList<>(ORG_COUNT);
        List<Object[]> batch = new ArrayList<>(ORG_COUNT);

        // ---- 1. 总部节点：唯一根，ADMIN 归属，不承保业务 ----
        long headquartersId = 1;
        batch.add(new Object[]{
                headquartersId, HEADQUARTERS_CODE, "平台总部", HEADQUARTERS_REGION, "北京市",
                1, 0L, 1, 1});
        orgs.add(new OrgRow(headquartersId, HEADQUARTERS_CODE, "平台总部",
                HEADQUARTERS_REGION, "北京市"));

        // ---- 2. 区域机构：区域内第 1 个为省级，其余为市级 ----
        long id = headquartersId + 1;
        for (String[] region : REGIONS) {
            int count = Integer.parseInt(region[3]);
            long provinceId = id; // 区域内第 1 个机构即为该区域省级机构
            for (int i = 1; i <= count; i++) {
                String code = "ORG" + region[0].substring(0, 2) + String.format("%02d", i);
                String name = region[1] + "第" + i + "保函运营机构";
                boolean province = i == 1;
                int level = province ? 2 : 3;
                long parentId = province ? headquartersId : provinceId;
                batch.add(new Object[]{id, code, name, region[0], region[1], level, parentId, 1, (int) id});
                orgs.add(new OrgRow(id, code, name, region[0], region[1]));
                id++;
            }
        }

        jdbcTemplate.batchUpdate("""
                INSERT INTO sys_org (id, org_code, org_name, region_code, region_name, org_level, parent_id, status, sort_no)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, batch);
        log.info("已生成机构 {} 个（总部 1 + 区域 20：省级 {} / 市级 {}）",
                orgs.size(), REGIONS.length, orgs.size() - HEADQUARTERS - REGIONS.length);
        return orgs;
    }

    /** 区域机构（不含总部），供订单采样使用。 */
    private static List<OrgRow> regionOrgs(List<OrgRow> orgs) {
        return orgs.subList(HEADQUARTERS, orgs.size());
    }

    /**
     * 部门树规格：{@code {部门名, 上级部门名（null = 顶级）}}。
     *
     * <p><b>每个机构都套用这同一棵树</b>（机构是出函机构、部门是公司内部部门，二者是不同的实体；
     * 部门靠 {@code parent_id} 成树，**不再有机构字段**——机构服务于订单，不是人的归属属性）。
     * 本机构 11 个部门：总部 → 5 个一级部门 → 其中业务部/技术部再分 5 个二级部门。</p>
     *
     * <p>为什么用规格表驱动而不是原来的"按 slot 取名字"：原实现的父子关系靠
     * {@code parentId = orgIndex + 1} 这种 id 算术推导，一旦部门数或顺序变化就会指向错误父节点。
     * 改为按名字解析父 id，新增/调整部门只需改这张表。</p>
     */
    private static final String[][] DEPT_SPEC = {
            {"总部", null},
            {"业务部", "总部"},
            {"财务部", "总部"},
            {"人事部", "总部"},
            {"行政部", "总部"},
            {"技术部", "总部"},
            {"杭州部", "业务部"},
            {"台州部", "业务部"},
            {"温州部", "业务部"},
            {"大数据部", "技术部"},
            {"系统部", "技术部"},
    };

    /** 每机构部门数（= 部门树节点数）。 */
    private static final int DEPTS_PER_ORG = DEPT_SPEC.length;

    /**
     * 启动时校验 {@link #DEPT_COUNT} 与规格表一致。
     *
     * <p>常量初始化不能前向引用 {@code DEPT_SPEC}，因此 {@code DEPT_COUNT} 里的乘数只能写字面量；
     * 这个校验把"改了规格忘记改乘数"变成**启动即失败**，而不是悄悄少建/多建部门。</p>
     */
    private static void assertDeptCountConsistent() {
        if (DEPT_COUNT != ORG_COUNT * DEPTS_PER_ORG) {
            throw new IllegalStateException("部门总数常量与规格表不一致: DEPT_COUNT=" + DEPT_COUNT
                    + " 但 ORG_COUNT×规格表长度=" + (ORG_COUNT * DEPTS_PER_ORG));
        }
    }

    /**
     * 生成部门（SYS-P-18 / SYS-P-24a / SYS-P-25）。
     *
     * <p>每个机构套用 {@link #DEPT_SPEC} 这棵树：第 1 个节点（总部）为顶级，
     * 其余按规格表指明父节点，因此层级**不是**一层平铺，而是"总部 → 一级部门 → 二级部门"。</p>
     *
     * <p>编码用"机构编码 + 短代码"（如 {@code ORGHQ-TECH}）保证全局唯一——
     * {@code dept_code} 是全局唯一键，而"技术部"这类名字在 21 个机构里都会出现。</p>
     */
    private void seedDepartments(List<OrgRow> orgs) {
        // 部门名 -> 该机构内的短代码，用于拼 dept_code
        Map<String, String> shortCodes = Map.ofEntries(
                Map.entry("总部", "HQ"),
                Map.entry("业务部", "BIZ"),
                Map.entry("财务部", "FIN"),
                Map.entry("人事部", "HR"),
                Map.entry("行政部", "ADM"),
                Map.entry("技术部", "TECH"),
                Map.entry("杭州部", "BIZ-HZ"),
                Map.entry("台州部", "BIZ-TZ"),
                Map.entry("温州部", "BIZ-WZ"),
                Map.entry("大数据部", "TECH-BD"),
                Map.entry("系统部", "TECH-SYS"));

        List<Object[]> batch = new ArrayList<>(orgs.size() * DEPTS_PER_ORG);
        long id = 1;
        int sortNo = 1;
        for (int orgIndex = 0; orgIndex < orgs.size(); orgIndex++) {
            OrgRow org = orgs.get(orgIndex);
            // 本机构内 部门名 -> 已插入的 id（供规格表解析父 id）
            Map<String, Long> idByName = new LinkedHashMap<>();
            for (String[] node : DEPT_SPEC) {
                String deptName = node[0];
                String parentName = node[1];
                String code = org.code() + "-" + shortCodes.get(deptName);
                Long parentId = parentName == null ? 0L : idByName.get(parentName);
                if (parentId == null) {
                    throw new IllegalStateException("部门规格表引用了未定义或不存在的上级部门: " + parentName);
                }
                batch.add(new Object[]{id, code, deptName, parentId, 1, sortNo});

                // 记录"该机构实际创建了哪些部门"（**按 DEPT_SPEC 顺序追加**），
                // 供 seedUsers 分配用户时查表；顺序是 deptIdByName 按名字定位部门的前提。
                orgDeptIds.computeIfAbsent(orgIndex, k -> new ArrayList<>()).add(id);
                idByName.put(deptName, id);
                id++;
                sortNo++;
            }
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO sys_department (id, dept_code, dept_name, parent_id, status, sort_no)
                VALUES (?, ?, ?, ?, ?, ?)
                """, batch);
        log.info("已生成部门 {} 个（每机构 {} 个：总部 + 5 个一级部门 + 5 个二级部门）",
                batch.size(), DEPTS_PER_ORG);
    }

    /** 权限编码 / 权限名称 / 前端路由。权威定义见 {@link PermissionCatalog}（SYS-P-13）。 */
    private static final String[][] PERMISSIONS = PermissionCatalog.PERMISSIONS;

    private static final String[][] ROLES = PermissionCatalog.ROLES;

    /** ADMIN：全部权限。 */
    private static final Set<String> ADMIN_PERMISSIONS = PermissionCatalog.ADMIN_PERMISSIONS;

    /** OPERATOR：除角色/权限管理与操作审计外的全部权限。 */
    private static final Set<String> OPERATOR_PERMISSIONS = PermissionCatalog.OPERATOR_PERMISSIONS;

    /** ANALYST（D-1）：业务分析 + 系统管理只读。 */
    private static final Set<String> ANALYST_PERMISSIONS = PermissionCatalog.ANALYST_PERMISSIONS;

    /** VIEWER：显式排除 {@code system:audit:view}（D-1a）。 */
    private static final Set<String> VIEWER_PERMISSIONS = PermissionCatalog.VIEWER_PERMISSIONS;

    /**
     * 角色与权限：委托 {@link RolePermissionSeeder} 幂等落库（SYS-P-13 / SYS-P-26）。
     *
     * <p>这里**不**再自己拼 id 批量 INSERT。原实现假设"我就是第一个写 sys_permission 的"，
     * 一旦权限表已有数据（存量库、或上一次启动的补数），就会撞主键并让整个初始化事务回滚，
     * 表现为空库启动失败。按业务键幂等后，任何执行顺序都收敛到同一矩阵。</p>
     */
    private void seedRolesAndPermissions() {
        List<String> added = new RolePermissionSeeder(jdbcTemplate).seed();
        log.info("角色权限矩阵已就绪：权限码 {} 条、角色 {} 个，本次新增绑定 {} 条",
                PERMISSIONS.length, ROLES.length, added.size());
    }

    private static long requireRoleId(Map<String, Long> roleIds, String roleCode) {
        Long id = roleIds.get(roleCode);
        if (id == null) {
            throw new IllegalStateException("角色未初始化: " + roleCode);
        }
        return id;
    }

    private void seedUsers(Random random, List<OrgRow> orgs) {
        String[] surnames = {"张", "王", "李", "赵", "陈", "刘", "杨", "黄", "周", "吴", "徐", "孙", "马", "朱", "胡"};
        String[] givenNames = {"伟", "芳", "娜", "敏", "静", "磊", "强", "军", "洋", "勇", "艳", "杰", "娟", "涛", "明"};

        // 按角色编码解析主键，而不是硬编码 1/2/3/4：
        // 角色表可能已由幂等补数创建，其自增 id 不保证等于目录顺序。
        Map<String, Long> roleIds = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT id, role_code FROM sys_role", rs -> {
            roleIds.put(rs.getString("role_code"), rs.getLong("id"));
        });
        long adminRoleId = requireRoleId(roleIds, "ADMIN");
        long operatorRoleId = requireRoleId(roleIds, "OPERATOR");
        long analystRoleId = requireRoleId(roleIds, "ANALYST");
        long viewerRoleId = requireRoleId(roleIds, "VIEWER");

        List<Object[]> batch = new ArrayList<>(USER_COUNT);
        List<Object[]> userRoles = new ArrayList<>();

        String adminHash = passwordEncoder.encode(ADMIN_PASSWORD);
        String operatorHash = passwordEncoder.encode(OPERATOR_PASSWORD);
        String analystHash = passwordEncoder.encode(ANALYST_PASSWORD);
        String defaultHash = passwordEncoder.encode(DEFAULT_PASSWORD);

        for (int i = 1; i <= USER_COUNT; i++) {
            int orgIndex = i % orgs.size();
            // 从"该机构实际创建的部门"里取一个：用户必须挂在真实存在的部门上。
            // 不能用 id 算术推导——部门树由 DEPT_SPEC 驱动，部门数与顺序都可能变，
            // 推导出来的 id 一旦失配就会指向不存在的部门（列表里"所属部门"永远为空）。
            List<Long> deptIds = orgDeptIds.getOrDefault(orgIndex, List.of());
            Long deptId = deptIds.isEmpty() ? null : deptIds.get(i % deptIds.size());
            String username;
            String hash;
            long roleId;
            if (i == 1) {
                username = "admin";
                hash = adminHash;
                roleId = adminRoleId;
                // SYS-P-24：admin 挂总部机构下的「总部」部门。
                // 用户必须属于一个部门（sys_user.dept_id 为 NOT NULL），因此不再允许 deptId = null；
                // 部门按**名字**定位（见 deptIdByName），不硬编码部门 id。
                orgIndex = headquartersOrgIndex(orgs);
                deptId = deptIdByName(orgIndex, HEADQUARTERS_DEPT_NAME);
            } else if (i == 2) {
                username = "operator";
                hash = operatorHash;
                roleId = operatorRoleId;
                // SYS-P-24：operator 使用「浙江省省级机构」那一组部门（orgs 下标 1）
                orgIndex = 1;
                deptId = pickDeptId(orgIndex, i);
            } else if (i == 3) {
                username = "analyst";
                hash = analystHash;
                roleId = analystRoleId;
                // SYS-P-24：analyst 使用「浙江省第2保函运营机构」那一组部门（orgs 下标 2）
                orgIndex = 2;
                deptId = pickDeptId(orgIndex, i);
            } else if (i == 4) {
                username = "user0004";
                hash = defaultHash;
                roleId = operatorRoleId;
                // SYS-P-24：user0004 使用「江苏省省级机构」那一组部门（orgs 下标 7）
                orgIndex = 7;
                deptId = pickDeptId(orgIndex, i);
            } else {
                username = "user" + String.format("%04d", i);
                hash = defaultHash;
                // 其余用户按稳定分布分配角色：运营 40%、分析 35%、只读 25%
                roleId = switch (i % 20) {
                    case 0, 1, 2, 3, 4, 5, 6, 7 -> operatorRoleId;
                    case 8, 9, 10, 11, 12, 13 -> analystRoleId;
                    default -> viewerRoleId;
                };
            }
            String realName = surnames[random.nextInt(surnames.length)]
                    + givenNames[random.nextInt(givenNames.length)]
                    + (random.nextInt(3) == 0 ? givenNames[random.nextInt(givenNames.length)] : "");
            batch.add(new Object[]{
                    (long) i, username, hash, realName, deptId,
                    "138" + String.format("%08d", random.nextInt(100_000_000)),
                    username + "@guarantee.com", 1
            });
            userRoles.add(new Object[]{(long) i, roleId});
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO sys_user (id, username, password, real_name, dept_id, phone, email, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, batch);
        jdbcTemplate.batchUpdate("""
                INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)
                """, userRoles);
        log.info("已生成用户 {} 个（admin 挂总部部门 / operator、analyst、user0004 挂指定演示部门 "
                + "+ {} 个普通用户）", USER_COUNT, USER_COUNT - 4);
    }

    /**
     * 取总部机构在 {@code orgs} 里的下标。
     *
     * <p>按机构编码（{@link #HEADQUARTERS_CODE}）查找，而不是硬编码 {@code 0}：
     * 总部必须是机构的唯一根，位置一旦变化，admin 的部门就会挂到别的机构去。</p>
     */
    private static int headquartersOrgIndex(List<OrgRow> orgs) {
        for (int i = 0; i < orgs.size(); i++) {
            if (HEADQUARTERS_CODE.equals(orgs.get(i).code())) {
                return i;
            }
        }
        throw new IllegalStateException("机构列表中找不到总部节点: " + HEADQUARTERS_CODE);
    }

    /**
     * 取某机构内指定**名称**的部门 id。
     *
     * <p>{@link #orgDeptIds} 是按 {@link #DEPT_SPEC} 顺序追加的，因此用部门名在规格表中的下标
     * 即可定位同名部门；这样既不硬编码部门 id，也不依赖 id 算术。
     * 名字不在规格表、或该机构没建出这个部门时**直接抛错**，而不是返回 null：
     * {@code sys_user.dept_id} 已收紧为 NOT NULL，静默返回 null 只会在批量插入时才失败。</p>
     */
    private Long deptIdByName(int orgIndex, String deptName) {
        int index = -1;
        for (int i = 0; i < DEPT_SPEC.length; i++) {
            if (DEPT_SPEC[i][0].equals(deptName)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            throw new IllegalStateException("部门规格表中不存在部门: " + deptName);
        }
        List<Long> deptIds = orgDeptIds.getOrDefault(orgIndex, List.of());
        if (index >= deptIds.size()) {
            throw new IllegalStateException("机构下标 " + orgIndex + " 下没有部门 " + deptName
                    + "（该机构实际部门数 " + deptIds.size() + "）");
        }
        return deptIds.get(index);
    }

    /**
     * 取某机构的第 n 个部门 id（按用户序号稳定选择）。
     *
     * <p>演示账号（operator / analyst / user0004）被显式指定到不同机构的部门组，
     * 以便演示数据里"不同归属的用户"分布可控；**指定了哪一组部门，就必须用同一组的下标去取
     * deptId**，否则会拿到别的机构那一组部门——页面上看不出毛病，但按部门统计人数会静默错位
     * （本仓库踩过一次）。</p>
     */
    private Long pickDeptId(int orgIndex, int userSeq) {
        List<Long> deptIds = orgDeptIds.getOrDefault(orgIndex, List.of());
        return deptIds.isEmpty() ? null : deptIds.get(userSeq % deptIds.size());
    }

    // ==================================================================
    // 险种
    // ==================================================================

    private record InsuranceRow(long id, String code, String name, String category, BigDecimal rate) {
    }

    private List<InsuranceRow> seedInsuranceTypes() {
        Object[][] rows = {
                {1L, "TENDER_STD", "投标保函（标准）", "TENDER", "0.008000"},
                {2L, "TENDER_ELEC", "电子投标保函", "TENDER", "0.006000"},
                {3L, "TENDER_SMALL", "投标保函（小额）", "TENDER", "0.010000"},
                {4L, "PERF_STD", "履约保函（标准）", "PERFORMANCE", "0.012000"},
                {5L, "PERF_ADVANCE", "履约保函（预付款）", "PERFORMANCE", "0.015000"},
                {6L, "PERF_QUALITY", "履约保函（质量）", "PERFORMANCE", "0.014000"},
        };
        List<Object[]> batch = new ArrayList<>();
        List<InsuranceRow> result = new ArrayList<>();
        for (Object[] r : rows) {
            batch.add(new Object[]{r[0], r[1], r[2], r[3], new BigDecimal((String) r[4]),
                    new BigDecimal("100000.00"), new BigDecimal("50000000.00"), 1,
                    r[2] + " 演示险种"});
            result.add(new InsuranceRow((Long) r[0], (String) r[1], (String) r[2], (String) r[3],
                    new BigDecimal((String) r[4])));
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO insurance_type (id, type_code, type_name, category, base_rate, min_amount, max_amount, status, description)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, batch);
        log.info("已生成险种 {} 个", result.size());
        return result;
    }

    // ==================================================================
    // 企业 / 项目
    // ==================================================================

    private record EnterpriseRow(long id, String name, String regionCode, String regionName) {
    }

    private record ProjectRow(long id, String name, long enterpriseId, String regionCode,
                              String regionName, BigDecimal amount) {
    }

    private List<EnterpriseRow> seedEnterprises(Random random) {
        String[] prefixes = {"中建", "中铁", "中交", "华建", "宏远", "方正", "鼎盛", "恒基", "嘉华", "瑞泰",
                "天工", "兴业", "联创", "金鼎", "远洋", "博远"};
        String[] suffixes = {"建设工程有限公司", "市政工程有限公司", "路桥工程有限公司", "水利工程有限公司",
                "电力工程有限公司", "园林绿化有限公司", "装饰工程有限公司", "科技有限公司"};

        List<EnterpriseRow> enterprises = new ArrayList<>(ENTERPRISE_COUNT);
        List<Object[]> batch = new ArrayList<>(ENTERPRISE_COUNT);
        for (int i = 1; i <= ENTERPRISE_COUNT; i++) {
            String[] region = pickRegion(random);
            // 企业序号 -> 14 位唯一后缀（乘一个与 10^14 互质的质数，避免出现连续编号，
            // 同时保证 1..3000 范围内不重复；同一区域前缀内唯一，区域前缀之间也互不相同）
            String creditCode = "91" + region[0].substring(0, 4)
                    + String.format("%014d", (i * 7919L) % 100_000_000_000_000L);
            String name = prefixes[random.nextInt(prefixes.length)]
                    + suffixes[random.nextInt(suffixes.length)]
                    + String.format("%04d", i);
            batch.add(new Object[]{
                    (long) i, "ENT" + String.format("%06d", i), name, creditCode,
                    region[0], region[1],
                    INDUSTRIES[random.nextInt(INDUSTRIES.length)],
                    ENT_LEVELS[random.nextInt(ENT_LEVELS.length)],
                    "联系人" + i, "139" + String.format("%08d", random.nextInt(100_000_000)), 1
            });
            enterprises.add(new EnterpriseRow(i, name, region[0], region[1]));
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO enterprise (id, ent_code, ent_name, credit_code, region_code, region_name,
                                        industry, ent_level, contact_name, contact_phone, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, batch);
        log.info("已生成企业 {} 个", enterprises.size());
        return enterprises;
    }

    private List<ProjectRow> seedProjects(Random random, List<EnterpriseRow> enterprises) {
        // 按区域分组企业，保证项目与其业主企业同区域
        Map<String, List<EnterpriseRow>> byRegion = groupByRegion(enterprises, EnterpriseRow::regionCode);

        List<ProjectRow> projects = new ArrayList<>(PROJECT_COUNT);
        List<Object[]> batch = new ArrayList<>(PROJECT_COUNT);
        for (int i = 1; i <= PROJECT_COUNT; i++) {
            String[] region = pickRegion(random);
            List<EnterpriseRow> pool = byRegion.getOrDefault(region[0], enterprises);
            EnterpriseRow owner = pool.get(random.nextInt(pool.size()));

            BigDecimal amount = randomAmount(random, 500_000, 200_000_000);
            LocalDate tenderDate = randomDate(random);
            String type = PROJECT_TYPES[random.nextInt(PROJECT_TYPES.length)];
            String status = pickProjectStatus(random, tenderDate);
            String name = region[1] + type + "工程项目" + String.format("%04d", i);

            batch.add(new Object[]{
                    (long) i, "PRJ" + String.format("%06d", i), name, owner.id(),
                    region[0], region[1], amount, type, status, Date.valueOf(tenderDate)
            });
            projects.add(new ProjectRow(i, name, owner.id(), region[0], region[1], amount));
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO project (id, project_code, project_name, enterprise_id, region_code, region_name,
                                     project_amount, project_type, status, tender_date)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, batch);
        log.info("已生成项目 {} 个", projects.size());
        return projects;
    }

    private static String pickProjectStatus(Random random, LocalDate tenderDate) {
        long monthsAgo = java.time.temporal.ChronoUnit.MONTHS.between(tenderDate, DATA_END);
        if (monthsAgo > 18) {
            return "FINISHED";
        }
        if (monthsAgo > 6) {
            return random.nextInt(100) < 70 ? "BUILDING" : "FINISHED";
        }
        return random.nextInt(100) < 60 ? "AWARDED" : "BIDDING";
    }

    // ==================================================================
    // 订单
    // ==================================================================

    private void seedTenderOrders(Random random, List<OrgRow> orgs, List<InsuranceRow> types,
                                  List<EnterpriseRow> enterprises, List<ProjectRow> projects) {
        List<InsuranceRow> tenderTypes = types.stream()
                .filter(t -> "TENDER".equals(t.category())).toList();
        Map<String, List<EnterpriseRow>> entByRegion = groupByRegion(enterprises, EnterpriseRow::regionCode);
        Map<String, List<ProjectRow>> prjByRegion = groupByRegion(projects, ProjectRow::regionCode);

        DaySampler sampler = new DaySampler(random, orgs);
        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        long id = 0;

        for (int i = 0; i < TENDER_ORDER_COUNT; i++) {
            int orgIdx = sampler.pickOrg();
            OrgRow org = orgs.get(orgIdx);
            LocalDate applyDate = sampler.pickDate(orgIdx);

            List<EnterpriseRow> entPool = entByRegion.getOrDefault(org.regionCode(), enterprises);
            List<ProjectRow> prjPool = prjByRegion.getOrDefault(org.regionCode(), projects);
            EnterpriseRow enterprise = entPool.get(random.nextInt(entPool.size()));
            ProjectRow project = prjPool.get(random.nextInt(prjPool.size()));

            InsuranceRow type = pickInsurance(random, tenderTypes, org.regionCode());
            BigDecimal guaranteeAmount = scaleToRange(project.amount(), random, "0.04", "0.14", 100_000);
            BigDecimal rate = jitterRate(random, type.rate());
            BigDecimal premium = guaranteeAmount.multiply(rate).setScale(2, RoundingMode.HALF_UP);

            LocalDate effective = applyDate.plusDays(1 + random.nextInt(3));
            LocalDate expire = effective.plusMonths(3 + random.nextInt(10));
            String status = pickOrderStatus(random, expire);

            id++;
            batch.add(new Object[]{
                    id, "TB" + applyDate.toString().replace("-", "") + String.format("%07d", i),
                    project.id(), enterprise.id(), type.id(), org.id(),
                    org.regionCode(), org.regionName(), guaranteeAmount, premium, rate, status,
                    Date.valueOf(applyDate), Date.valueOf(effective), Date.valueOf(expire)
            });
            if (batch.size() >= BATCH_SIZE) {
                flushTenderOrders(batch);
                batch = new ArrayList<>(BATCH_SIZE);
            }
        }
        if (!batch.isEmpty()) {
            flushTenderOrders(batch);
        }
        log.info("已生成投标订单 {} 条", TENDER_ORDER_COUNT);
    }

    private void flushTenderOrders(List<Object[]> batch) {
        jdbcTemplate.batchUpdate("""
                INSERT INTO tender_order (id, order_no, project_id, enterprise_id, insurance_type_id, org_id,
                                          region_code, region_name, guarantee_amount, premium_amount, premium_rate,
                                          status, apply_date, effective_date, expire_date)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, batch);
    }

    private void seedPerformanceOrders(Random random, List<OrgRow> orgs, List<InsuranceRow> types,
                                       List<EnterpriseRow> enterprises, List<ProjectRow> projects) {
        List<InsuranceRow> perfTypes = types.stream()
                .filter(t -> "PERFORMANCE".equals(t.category())).toList();
        Map<String, List<EnterpriseRow>> entByRegion = groupByRegion(enterprises, EnterpriseRow::regionCode);
        Map<String, List<ProjectRow>> prjByRegion = groupByRegion(projects, ProjectRow::regionCode);

        DaySampler sampler = new DaySampler(random, orgs);
        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        long id = 0;

        for (int i = 0; i < PERFORMANCE_ORDER_COUNT; i++) {
            int orgIdx = sampler.pickOrg();
            OrgRow org = orgs.get(orgIdx);
            LocalDate applyDate = sampler.pickDate(orgIdx);

            List<EnterpriseRow> entPool = entByRegion.getOrDefault(org.regionCode(), enterprises);
            List<ProjectRow> prjPool = prjByRegion.getOrDefault(org.regionCode(), projects);
            EnterpriseRow enterprise = entPool.get(random.nextInt(entPool.size()));
            ProjectRow project = prjPool.get(random.nextInt(prjPool.size()));

            InsuranceRow type = pickInsurance(random, perfTypes, org.regionCode());
            // 履约保函金额普遍高于投标保函
            BigDecimal guaranteeAmount = scaleToRange(project.amount(), random, "0.08", "0.25", 200_000);
            BigDecimal rate = jitterRate(random, type.rate());
            BigDecimal premium = guaranteeAmount.multiply(rate).setScale(2, RoundingMode.HALF_UP);

            LocalDate effective = applyDate.plusDays(2 + random.nextInt(5));
            LocalDate expire = effective.plusMonths(6 + random.nextInt(18));
            String status = pickOrderStatus(random, expire);

            id++;
            batch.add(new Object[]{
                    id, "PB" + applyDate.toString().replace("-", "") + String.format("%07d", i),
                    "HT" + applyDate.toString().replace("-", "") + String.format("%07d", i),
                    project.id(), enterprise.id(), type.id(), org.id(),
                    org.regionCode(), org.regionName(), guaranteeAmount, premium, rate, status,
                    Date.valueOf(applyDate), Date.valueOf(effective), Date.valueOf(expire)
            });
            if (batch.size() >= BATCH_SIZE) {
                flushPerformanceOrders(batch);
                batch = new ArrayList<>(BATCH_SIZE);
            }
        }
        if (!batch.isEmpty()) {
            flushPerformanceOrders(batch);
        }
        log.info("已生成履约订单 {} 条", PERFORMANCE_ORDER_COUNT);
    }

    private void flushPerformanceOrders(List<Object[]> batch) {
        jdbcTemplate.batchUpdate("""
                INSERT INTO performance_order (id, order_no, contract_no, project_id, enterprise_id,
                                               insurance_type_id, org_id, region_code, region_name,
                                               guarantee_amount, premium_amount, premium_rate,
                                               status, apply_date, effective_date, expire_date)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, batch);
    }

    // ==================================================================
    // 采样工具
    // ==================================================================

    /**
     * 按「机构权重 × 月份季节性 × 机构 Q3 系数」对日期进行加权采样。
     *
     * <p>为每个机构预计算一条按天累积的权重数组，采样时二分查找，
     * 使 15 万条订单也能精确服从设定的业务规律。</p>
     */
    private final class DaySampler {

        private final Random random;
        private final int orgCount;
        private final List<LocalDate> days = new ArrayList<>();
        private final double[][] cumulative;
        private final double[] orgTotals;
        private final double grandTotal;
        private final int[] orgCumulative;

        DaySampler(Random random, List<OrgRow> orgs) {
            this.random = random;
            this.orgCount = orgs.size();

            // 传入的必须是"仅区域机构"列表（不含总部，SYS-P-23）。
            // 这里显式断言，避免后续有人加总部/加机构时采样权重静默错位：
            // orgCumulative 的长度与 REGIONS 的机构数合计强绑定。
            int expected = 0;
            for (String[] region : REGIONS) {
                expected += Integer.parseInt(region[3]);
            }
            if (orgCount != expected) {
                throw new IllegalStateException(
                        "订单采样只允许在区域机构中进行：期望 " + expected + " 个，实际 " + orgCount + " 个");
            }

            LocalDate cursor = DATA_START;
            while (!cursor.isAfter(DATA_END)) {
                days.add(cursor);
                cursor = cursor.plusDays(1);
            }

            this.cumulative = new double[orgCount][days.size()];
            this.orgTotals = new double[orgCount];
            for (int o = 0; o < orgCount; o++) {
                double sum = 0;
                for (int d = 0; d < days.size(); d++) {
                    sum += dayWeight(o, days.get(d));
                    cumulative[o][d] = sum;
                }
                orgTotals[o] = sum;
            }

            // 机构抽样权重：区域权重 / 该区域机构数
            double total = 0;
            double[] regionWeight = new double[REGIONS.length];
            for (int r = 0; r < REGIONS.length; r++) {
                regionWeight[r] = Double.parseDouble(REGIONS[r][2]) / Integer.parseInt(REGIONS[r][3]);
            }
            this.orgCumulative = new int[orgCount];
            int idx = 0;
            for (int r = 0; r < REGIONS.length; r++) {
                int count = Integer.parseInt(REGIONS[r][3]);
                for (int i = 0; i < count; i++) {
                    total += regionWeight[r];
                    orgCumulative[idx++] = (int) Math.round(total * 1000);
                }
            }
            this.grandTotal = total * 1000;
        }

        private double dayWeight(int orgIdx, LocalDate day) {
            double w = MONTH_FACTOR[day.getMonthValue() - 1];
            // 2026 Q3 起用机构专属系数，形成“部分机构增长、部分机构下降”
            if (day.getYear() == 2026 && day.getMonthValue() >= 7) {
                w *= ORG_Q3_FACTOR[orgIdx % ORG_Q3_FACTOR.length];
            } else if (day.getYear() == 2026 && day.getMonthValue() <= 6) {
                w *= 1.0 + (ORG_Q3_FACTOR[orgIdx % ORG_Q3_FACTOR.length] - 1.0) * 0.35;
            }
            return w;
        }

        int pickOrg() {
            double r = random.nextDouble() * grandTotal;
            for (int i = 0; i < orgCumulative.length; i++) {
                if (r < orgCumulative[i]) {
                    return i;
                }
            }
            return orgCount - 1;
        }

        LocalDate pickDate(int orgIdx) {
            double r = random.nextDouble() * orgTotals[orgIdx];
            double[] cum = cumulative[orgIdx];
            int lo = 0;
            int hi = cum.length - 1;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (r < cum[mid]) {
                    hi = mid;
                } else {
                    lo = mid + 1;
                }
            }
            return days.get(lo);
        }
    }

    private static String[] pickRegion(Random random) {
        int roll = random.nextInt(100);
        int acc = 0;
        for (String[] region : REGIONS) {
            acc += Integer.parseInt(region[2]);
            if (roll < acc) {
                return region;
            }
        }
        return REGIONS[0];
    }

    /**
     * 险种结构与区域相关：浙江以投标保函为主，江苏履约保函比例更高。
     */
    private static InsuranceRow pickInsurance(Random random, List<InsuranceRow> candidates, String regionCode) {
        int roll = random.nextInt(100);
        int index;
        if ("320000".equals(regionCode)) {
            // 江苏：更偏向第二/第三类
            index = roll < 45 ? 1 : (roll < 80 ? 0 : 2);
        } else if ("330000".equals(regionCode)) {
            // 浙江：明显偏向标准与电子保函
            index = roll < 55 ? 0 : (roll < 88 ? 1 : 2);
        } else {
            index = roll < 40 ? 0 : (roll < 75 ? 1 : 2);
        }
        return candidates.get(Math.min(index, candidates.size() - 1));
    }

    private static BigDecimal jitterRate(Random random, BigDecimal baseRate) {
        double factor = 0.85 + random.nextDouble() * 0.35;
        return baseRate.multiply(BigDecimal.valueOf(factor)).setScale(6, RoundingMode.HALF_UP);
    }

    private static String pickOrderStatus(Random random, LocalDate expireDate) {
        if (expireDate.isBefore(DATA_END)) {
            int roll = random.nextInt(100);
            if (roll < 70) {
                return "EXPIRED";
            }
            if (roll < 90) {
                return "RELEASED";
            }
            return "EFFECTIVE";
        }
        int roll = random.nextInt(100);
        if (roll < 68) {
            return "EFFECTIVE";
        }
        if (roll < 85) {
            return "UNDER_REVIEW";
        }
        if (roll < 93) {
            return "DRAFT";
        }
        return "RELEASED";
    }

    /** 让金额落在 [min, max] 区间并按 step 取整。 */
    private static BigDecimal randomAmount(Random random, long min, long max) {
        long span = max - min;
        // 平方分布让大额项目更少，更贴近真实
        double factor = Math.pow(random.nextDouble(), 2);
        long value = min + (long) (span * factor);
        return BigDecimal.valueOf(value / 10_000 * 10_000).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal scaleToRange(BigDecimal base, Random random,
                                           String minFactor, String maxFactor, long minAmount) {
        BigDecimal lo = new BigDecimal(minFactor);
        BigDecimal hi = new BigDecimal(maxFactor);
        BigDecimal factor = lo.add(hi.subtract(lo).multiply(BigDecimal.valueOf(random.nextDouble())));
        BigDecimal value = base.multiply(factor);
        if (value.compareTo(BigDecimal.valueOf(minAmount)) < 0) {
            value = BigDecimal.valueOf(minAmount);
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static LocalDate randomDate(Random random) {
        long span = java.time.temporal.ChronoUnit.DAYS.between(DATA_START, DATA_END);
        return DATA_START.plusDays((long) (random.nextDouble() * span));
    }

    private static <T> Map<String, List<T>> groupByRegion(List<T> items,
                                                          java.util.function.Function<T, String> keyFn) {
        Map<String, List<T>> map = new LinkedHashMap<>();
        for (T item : items) {
            map.computeIfAbsent(keyFn.apply(item), k -> new ArrayList<>()).add(item);
        }
        return map;
    }
}
