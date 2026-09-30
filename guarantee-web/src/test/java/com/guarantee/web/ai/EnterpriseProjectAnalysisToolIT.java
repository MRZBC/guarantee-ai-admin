package com.guarantee.web.ai;

import com.guarantee.ai.tool.AiToolRegistry;
import com.guarantee.ai.tool.EnterpriseAnalysisTool;
import com.guarantee.ai.tool.EnterpriseAnalysisToolResult;
import com.guarantee.ai.tool.ProjectAnalysisTool;
import com.guarantee.ai.tool.ProjectAnalysisToolResult;
import com.guarantee.analysis.dto.AnalysisCriteria;
import com.guarantee.analysis.service.OrderAnalysisService;
import com.guarantee.analysis.vo.EnterpriseGroupVO;
import com.guarantee.analysis.vo.EnterpriseRankVO;
import com.guarantee.analysis.vo.OrderRegionVO;
import com.guarantee.analysis.vo.ProjectGroupVO;
import com.guarantee.analysis.vo.ProjectRankVO;
import com.guarantee.web.GuaranteeAiAdminApplication;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 企业维度（REQ-BA-03）与项目维度（REQ-BA-04）打到真实数据库的验证（不需要 LLM API Key）。
 *
 * <p>守四件事：</p>
 * <ol>
 *   <li><b>与页面同口径</b>：工具结果逐字段等于 {@link OrderAnalysisService} 的返回值
 *       （两者共用同一套 SQL，这条断言防的是"工具偷偷另写一套 SQL"）；</li>
 *   <li><b>跨维度合计一致</b>：按企业/项目切开的订单量合计 = 同区间按地区切开的合计
 *       （过滤条件只作用在部分查询上，是"助手与页面对不上"的经典根源）；</li>
 *   <li><b>企业/项目名历史保留</b>：join 不带 is_deleted/status——先用 SQL 改写器证明机制，
 *       再用**事务内造样本 + 回滚**真跑数据路径（把一家有二季度订单的企业临时改成停用+软删，
 *       断言它仍出现在榜单里，并给出"加了过滤就会消失"的反证），跑完强制回滚、复核零污染；</li>
 *   <li><b>项目类型原样中文</b>：返回值必须落在中文枚举集合里。</li>
 * </ol>
 */
@SpringBootTest(classes = GuaranteeAiAdminApplication.class,
        properties = {"guarantee.data-init.enabled=false"})
class EnterpriseProjectAnalysisToolIT {

    private static final LocalDate Q2_START = LocalDate.of(2026, 4, 1);
    private static final LocalDate Q2_END = LocalDate.of(2026, 6, 30);

    /** 项目管理页的项目类型枚举（中文）。 */
    private static final Set<String> PROJECT_TYPES = Set.of("房建", "市政", "交通", "水利", "其他");

    @Autowired
    private EnterpriseAnalysisTool enterpriseTool;

    @Autowired
    private ProjectAnalysisTool projectTool;

    @Autowired
    private OrderAnalysisService orderAnalysisService;

    @Autowired
    private AiToolRegistry registry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    @Test
    @DisplayName("企业分布（行业）：与同 Service 的查询逐字段一致，且订单量合计等于同区间地区合计")
    void enterpriseDistributionMatchesService() {
        EnterpriseAnalysisToolResult result = enterpriseTool.queryEnterpriseAnalysis(
                "DISTRIBUTION", "INDUSTRY", null, "TENDER", "2026-04-01", "2026-06-30", null, 20);

        List<EnterpriseGroupVO> expected =
                orderAnalysisService.enterpriseDistribution(criteria(), "INDUSTRY", 20);

        assertThat(expected).as("演示数据缺失：先跑 scripts/reset-demo-data.ps1")
                .isNotEmpty();
        assertThat(result.items()).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            EnterpriseAnalysisToolResult.EnterpriseItem actual = result.items().get(i);
            EnterpriseGroupVO vo = expected.get(i);
            assertThat(actual.code()).isEqualTo(vo.getGroupCode()).isNotBlank();
            assertThat(actual.name()).isEqualTo(vo.getGroupName());
            assertThat(actual.enterpriseCount()).isEqualTo(vo.getEnterpriseCount()).isPositive();
            assertThat(actual.orderCount()).isEqualTo(vo.getOrderCount());
            assertThat(actual.guaranteeAmount()).isEqualByComparingTo(vo.getGuaranteeAmount());
            assertThat(actual.premiumAmount()).isEqualByComparingTo(vo.getPremiumAmount());
        }

        // R1：占比由服务端下发且按维度归一 —— 逐项等于 Service 的值，合计正好 100.00
        assertThat(result.items()).extracting(EnterpriseAnalysisToolResult.EnterpriseItem::share)
                .allSatisfy((share) -> assertThat(share).isNotNull());
        assertThat(result.items().stream()
                .mapToLong((item) -> item.share().movePointRight(2).longValueExact()).sum())
                .as("share 之和必须正好 100.00（最大余数法归一，无浮点尾差）")
                .isEqualTo(10000L);
        for (int i = 0; i < expected.size(); i++) {
            assertThat(result.items().get(i).share())
                    .as("工具透传的占比必须等于 Service 算好的值").isEqualByComparingTo(expected.get(i).getShare());
        }
        // 跨维度合计：按行业切开的订单量之和 = 同区间按地区切开的合计
        List<OrderRegionVO> regions = orderAnalysisService.regionDistribution(criteria(), 200);
        long byIndustry = result.items().stream()
                .mapToLong(EnterpriseAnalysisToolResult.EnterpriseItem::orderCount).sum();
        long byRegion = regions.stream().mapToLong(OrderRegionVO::getOrderCount).sum();
        assertThat(byIndustry).as("企业行业维度与订单地区维度必须来自同一批订单")
                .isEqualTo(byRegion);

        assertThat(result.meta().dataSource())
                .contains("企业分析").contains("维度：行业").contains("险种：投标保函")
                .doesNotContain("queryEnterpriseAnalysis").doesNotContain("dimension");
    }

    @Test
    @DisplayName("企业排行：按订单量倒序、企业名非空；按保额排序时保额单调不增")
    void enterpriseTopOrderingAndHistoricalNames() {
        EnterpriseAnalysisToolResult byCount = enterpriseTool.queryEnterpriseAnalysis(
                "TOP", null, "ORDER_COUNT", "TENDER", "2026-04-01", "2026-06-30", null, 20);
        List<EnterpriseRankVO> expected = orderAnalysisService.enterpriseTop(criteria(), "ORDER_COUNT", 20);

        assertThat(expected).isNotEmpty();
        assertThat(byCount.items()).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            assertThat(byCount.items().get(i).name()).isEqualTo(expected.get(i).getEntName())
                    .as("企业名必须历史保留：停用/删除的企业也不能变成空名字").isNotBlank();
            assertThat(byCount.items().get(i).orderCount()).isEqualTo(expected.get(i).getOrderCount());
        }
        assertThat(byCount.items()).extracting(EnterpriseAnalysisToolResult.EnterpriseItem::orderCount)
                .isSortedAccordingTo((a, b) -> Long.compare(b, a));

        EnterpriseAnalysisToolResult byAmount = enterpriseTool.queryEnterpriseAnalysis(
                "TOP", null, "GUARANTEE_AMOUNT", "TENDER", "2026-04-01", "2026-06-30", null, 20);
        assertThat(byAmount.items()).extracting(EnterpriseAnalysisToolResult.EnterpriseItem::guaranteeAmount)
                .isSortedAccordingTo((a, b) -> b.compareTo(a));
        assertThat(byAmount.meta().dataSource()).contains("排序依据：保额");
    }

    @Test
    @DisplayName("项目分布（类型）：类型是中文枚举、字段逐项一致、合计等于同区间地区合计")
    void projectDistributionKeepsChineseTypes() {
        ProjectAnalysisToolResult result = projectTool.queryProjectAnalysis(
                "DISTRIBUTION", "PROJECT_TYPE", "TENDER", "2026-04-01", "2026-06-30", null, null);
        List<ProjectGroupVO> expected = orderAnalysisService.projectDistribution(criteria(), "PROJECT_TYPE", 10);

        assertThat(expected).isNotEmpty();
        assertThat(result.items()).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            ProjectAnalysisToolResult.ProjectItem actual = result.items().get(i);
            ProjectGroupVO vo = expected.get(i);
            assertThat(actual.name()).isEqualTo(vo.getGroupName())
                    .as("项目类型必须是中文枚举（与项目管理页一致）").isIn(PROJECT_TYPES);
            assertThat(actual.projectCount()).isEqualTo(vo.getProjectCount()).isPositive();
            assertThat(actual.orderCount()).isEqualTo(vo.getOrderCount());
            assertThat(actual.guaranteeAmount()).isEqualByComparingTo(vo.getGuaranteeAmount());
        }

        assertThat(result.items().stream()
                .mapToLong((item) -> item.share().movePointRight(2).longValueExact()).sum())
                .as("项目维度 share 之和同样必须正好 100.00").isEqualTo(10000L);
        List<OrderRegionVO> regions = orderAnalysisService.regionDistribution(criteria(), 200);
        long byType = result.items().stream()
                .mapToLong(ProjectAnalysisToolResult.ProjectItem::orderCount).sum();
        assertThat(byType).isEqualTo(regions.stream().mapToLong(OrderRegionVO::getOrderCount).sum());

        assertThat(result.meta().dataSource())
                .contains("项目分析").contains("维度：项目类型")
                .doesNotContain("queryProjectAnalysis");
    }

    @Test
    @DisplayName("项目排行：按担保金额倒序、项目名与类型非空且类型为中文")
    void projectTopRanking() {
        ProjectAnalysisToolResult result = projectTool.queryProjectAnalysis(
                "TOP", null, "TENDER", "2026-04-01", "2026-06-30", null, 20);
        List<ProjectRankVO> expected = orderAnalysisService.projectTop(criteria(), 20);

        assertThat(expected).isNotEmpty();
        assertThat(result.items()).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            ProjectAnalysisToolResult.ProjectItem item = result.items().get(i);
            assertThat(item.code()).isEqualTo(expected.get(i).getProjectCode()).isNotBlank();
            assertThat(item.name()).isEqualTo(expected.get(i).getProjectName()).isNotBlank();
            assertThat(item.projectType()).isIn(PROJECT_TYPES);
        }
        assertThat(result.items()).extracting(ProjectAnalysisToolResult.ProjectItem::guaranteeAmount)
                .isSortedAccordingTo((a, b) -> b.compareTo(a));
    }

    @Test
    @DisplayName("历史保留的机制证明：语句含显式 is_deleted → 改写器整句跳过 → 企业/项目主数据不被注入过滤")
    void logicalDeleteRewriterSkipsWholeStatement() throws Exception {
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("c", criteria());
        params.put("dimension", "INDUSTRY");
        params.put("limit", 10);

        String sql = boundSql("com.guarantee.analysis.mapper.OrderAnalysisMapper.selectEnterpriseDistribution", params);

        assertThat(sql).as("订单侧必须有显式逻辑删除过滤（否则已删订单会被算进来）")
                .contains("is_deleted = 0");
        assertThat(sql).as("企业主数据侧刻意不带过滤，才能历史保留")
                .contains("LEFT JOIN enterprise")
                .doesNotContain("e.is_deleted")
                .doesNotContain("e.status");
        assertThat(rewrite(sql))
                .as("语句已显式出现 is_deleted 条件 → 改写器整句跳过；一旦这条不成立，"
                        + "企业名会在主数据被停用/删除后消失（历史保留口径被破坏）")
                .isEqualTo(sql);

        Map<String, Object> projectParams = new java.util.HashMap<>();
        projectParams.put("c", criteria());
        projectParams.put("dimension", "PROJECT_TYPE");
        projectParams.put("limit", 10);
        String projectSql = boundSql(
                "com.guarantee.analysis.mapper.OrderAnalysisMapper.selectProjectDistribution", projectParams);
        assertThat(projectSql).contains("LEFT JOIN project").doesNotContain("p.is_deleted").doesNotContain("p.status");
        assertThat(rewrite(projectSql)).isEqualTo(projectSql);
    }

    /**
     * 复用生产侧的 SQL 改写器做机制证明。
     *
     * <p>它是 {@code guarantee-system} 的**包可见**工具类，本任务 scope 不含该模块，
     * 因此不为了测试去放宽它的可见性——这里用反射调用，属于"只读地用一次"。</p>
     */
    private static String rewrite(String sql) throws Exception {
        Class<?> type = Class.forName("com.guarantee.system.mybatis.LogicalDeleteSqlRewriter");
        java.lang.reflect.Method method = type.getDeclaredMethod("rewrite", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, sql);
    }

    private String boundSql(String statementId, Map<String, Object> params) {
        MappedStatement statement = sqlSessionFactory.getConfiguration().getMappedStatement(statementId);
        return statement.getBoundSql(params).getSql();
    }

    /**
     * 历史保留的**数据路径**证明（真跑，不再 Skipped）。
     *
     * <p>原先这条探针查"演示数据里已停用/已软删的企业"，而演示数据里根本没有这类样本
     * （实测：`is_deleted=1` 0 家、`status=0` 0 家），于是它**永久 Skipped**——
     * 机制被证明了（下一条测试看 SQL），但"数据真的走通了"没有被证明。</p>
     *
     * <p>现在改成**事务内造样本**：本方法跑在 Spring 测试事务里，JdbcTemplate 与
     * MyBatis（Service/Tool 用的同一 DataSource）**加入同一事务**，因此
     * "改主数据 → 调 Service" 能互相看见；断言完立即 <b>强制回滚</b>，
     * 并在**新连接**上复核零污染。这样既真跑了数据路径，又不需要演示数据里有这类脏样本。</p>
     */
    @Test
    @Transactional
    @DisplayName("历史保留（事务内造样本 + 回滚）：软删/停用一家有二季度订单的企业后，它仍在榜单里且名字/行业正常")
    void deletedOrDisabledEnterpriseNameStillVisible() {
        // 1) 选样本：二季度订单最多的一家**正常**企业（它一定在 TOP 50 里，改坏后也仍应出现）
        Map<String, Object> target = jdbcTemplate.queryForMap("""
                SELECT e.id, e.ent_name, e.industry, e.status, e.is_deleted, COUNT(o.id) AS orders
                FROM enterprise e
                JOIN tender_order o ON o.enterprise_id = e.id AND o.is_deleted = 0
                WHERE e.is_deleted = 0
                  AND o.apply_date >= '2026-04-01' AND o.apply_date <= '2026-06-30'
                GROUP BY e.id, e.ent_name, e.industry, e.status, e.is_deleted
                ORDER BY orders DESC
                LIMIT 1
                """);
        long id = ((Number) target.get("id")).longValue();
        String name = String.valueOf(target.get("ent_name"));
        String industry = String.valueOf(target.get("industry"));
        long orders = ((Number) target.get("orders")).longValue();
        assertThat(orders).as("演示数据缺失：先跑 scripts/reset-demo-data.ps1").isPositive();

        // 2) 在本事务里把它变成"已停用 + 已软删"：主数据退场，历史订单仍在
        int updated = jdbcTemplate.update("""
                UPDATE enterprise SET is_deleted = 1, status = 0,
                       deleted_at = NOW(6), deleted_by = 'it-history-retention'
                WHERE id = ?
                """, id);
        assertThat(updated).as("样本企业必须被成功改为停用+软删").isEqualTo(1);

        // 3) 同一事务内调 Service/Tool：这家企业**仍然出现**，名字与行业都正常
        EnterpriseAnalysisToolResult result = enterpriseTool.queryEnterpriseAnalysis(
                "TOP", null, "ORDER_COUNT", "TENDER", "2026-04-01", "2026-06-30", null, 50);
        EnterpriseAnalysisToolResult.EnterpriseItem hit = result.items().stream()
                .filter(item -> name.equals(item.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "已停用/软删企业的名字没有出现在榜单里（历史保留口径被破坏）："
                                + "样本=" + name + "，返回=" + result.items().stream()
                                .map(EnterpriseAnalysisToolResult.EnterpriseItem::name).toList()));
        assertThat(hit.industry()).as("行业也必须历史保留（join 不带 e.is_deleted）").isEqualTo(industry);
        assertThat(hit.orderCount()).as("历史订单照常统计").isEqualTo(orders);

        // 4) 反证：若 join 真加了 `AND e.is_deleted = 0`，这家企业就查不到了 —— 差异非空
        Integer filtered = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM enterprise e
                JOIN tender_order o ON o.enterprise_id = e.id AND o.is_deleted = 0
                WHERE e.id = ? AND e.is_deleted = 0
                  AND o.apply_date >= '2026-04-01' AND o.apply_date <= '2026-06-30'
                """, Integer.class, id);
        Integer unfiltered = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM enterprise e
                JOIN tender_order o ON o.enterprise_id = e.id AND o.is_deleted = 0
                WHERE e.id = ?
                  AND o.apply_date >= '2026-04-01' AND o.apply_date <= '2026-06-30'
                """, Integer.class, id);
        assertThat(filtered).as("反证：加了 is_deleted 过滤后样本企业会消失").isZero();
        assertThat(unfiltered).as("不加过滤时它的历史订单仍在（正是历史保留要保住的东西）")
                .isEqualTo((int) orders);

        // 5) 立即回滚，并在**新连接**（无事务）上复核：演示数据零污染
        TestTransaction.flagForRollback();
        TestTransaction.end();
        Map<String, Object> after = jdbcTemplate.queryForMap(
                "SELECT is_deleted, status FROM enterprise WHERE id = ?", id);
        assertThat(((Number) after.get("is_deleted")).intValue())
                .as("探针必须回滚：演示数据不能被污染").isZero();
        assertThat(((Number) after.get("status")).intValue())
                .as("status 也要还原为启用").isEqualTo(1);
    }

    /** 兜底：任何提前失败的路径都不许把测试事务留在打开状态。 */
    @AfterEach
    void rollbackProbeTransactionIfStillActive() {
        if (TestTransaction.isActive()) {
            TestTransaction.flagForRollback();
            TestTransaction.end();
        }
    }

    @Test
    @DisplayName("只持 ai:chat 的用户也能看到两个新工具（与订单分析同权限口径，不新增权限码）")
    void registryExposesNewTools() {
        assertThat(registry.availableToolNames(List.of("ai:chat")))
                .contains("queryEnterpriseAnalysis", "queryProjectAnalysis");
    }

    private static AnalysisCriteria criteria() {
        AnalysisCriteria criteria = new AnalysisCriteria();
        criteria.setOrderType("TENDER");
        criteria.setStartDate(Q2_START);
        criteria.setEndDate(Q2_END);
        return criteria;
    }
}
