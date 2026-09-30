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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 企业维度（REQ-BA-03）与项目维度（REQ-BA-04）打到真实数据库的验证（不需要 LLM API Key）。
 *
 * <p>守四件事：</p>
 * <ol>
 *   <li><b>与页面同口径</b>：工具结果逐字段等于 {@link OrderAnalysisService} 的返回值
 *       （两者共用同一套 SQL，这条断言防的是"工具偷偷另写一套 SQL"）；</li>
 *   <li><b>跨维度合计一致</b>：按企业/项目切开的订单量合计 = 同区间按地区切开的合计
 *       （过滤条件只作用在部分查询上，是"助手与页面对不上"的经典根源）；</li>
 *   <li><b>企业/项目名历史保留</b>：join 不带 is_deleted/status——用"已停用或已软删"的
 *       主数据做探针，验证它的名字仍然出现（没有这类演示数据时如实跳过，不假装通过）；</li>
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

    @Test
    @DisplayName("历史保留探针：已停用/已软删企业的名字仍出现在排行里（无此类数据则如实跳过）")
    void deletedOrDisabledEnterpriseNameStillVisible() {
        List<Map<String, Object>> candidates = jdbcTemplate.queryForList("""
                SELECT e.id, e.ent_name, e.status, e.is_deleted
                FROM enterprise e
                WHERE (e.is_deleted = 1 OR e.status = 0)
                  AND EXISTS (SELECT 1 FROM tender_order o
                              WHERE o.enterprise_id = e.id AND o.is_deleted = 0
                                AND o.apply_date >= '2026-04-01' AND o.apply_date <= '2026-06-30')
                LIMIT 5
                """);
        assumeTrue(!candidates.isEmpty(),
                "演示数据里没有「已停用/已软删且仍有二季度订单」的企业，无法验证历史保留（如实跳过，不假装通过）");

        List<Long> ids = candidates.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
        List<String> names = candidates.stream().map(row -> String.valueOf(row.get("ent_name"))).toList();

        EnterpriseAnalysisToolResult result = enterpriseTool.queryEnterpriseAnalysis(
                "TOP", null, "ORDER_COUNT", "TENDER", "2026-04-01", "2026-06-30", null, 50);
        List<String> returnedNames = result.items().stream()
                .map(EnterpriseAnalysisToolResult.EnterpriseItem::name).toList();

        for (int i = 0; i < ids.size(); i++) {
            if (returnedNames.contains(names.get(i))) {
                // 只要命中一个就足以证明 join 没有过滤主数据
                return;
            }
        }
        throw new AssertionError("已停用/软删企业的名字没有出现在排行里（历史保留口径可能被破坏）："
                + "候选=" + names + "，返回=" + returnedNames);
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
