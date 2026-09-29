package com.guarantee.web.ai;

import com.guarantee.ai.tool.OrderDistributionTool;
import com.guarantee.ai.tool.OrderDistributionToolResult;
import com.guarantee.ai.tool.AiToolRegistry;
import com.guarantee.analysis.dto.AnalysisCriteria;
import com.guarantee.analysis.service.OrderAnalysisService;
import com.guarantee.analysis.vo.OrderInstitutionVO;
import com.guarantee.analysis.vo.OrderInsuranceVO;
import com.guarantee.analysis.vo.OrderRegionVO;
import com.guarantee.order.dto.OrderSummaryCriteria;
import com.guarantee.order.service.OrderStatisticsService;
import com.guarantee.web.GuaranteeAiAdminApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code queryOrderDistribution} 打到真实数据库的验证（不需要 LLM API Key）。
 *
 * <p><b>为什么必须有它</b>：2026-09-29 现场，用户问「请分析 2026 年第二季度投标订单，
 * 和第一季度比较，并从区域、机构、险种三个维度找出主要变化」，助手只能回答"我给不出"——
 * 因为它当时只有汇总工具。本工具把**数据概览页已有的分布口径**接到助手侧，
 * 因此这里要守的就是"助手看到的分布 = 页面上看到的分布"：</p>
 * <ul>
 *   <li>三个维度逐字段与 {@link OrderAnalysisService} 一致（码/名/量/额/企业数）；</li>
 *   <li>区域维度合计 = 同一时间区间的投标订单总量（跨模块口径一致，不是另一套 SQL）；</li>
 *   <li>按订单量倒序、排名连续；口径行可读。</li>
 * </ul>
 *
 * <p>需要可用的 MySQL（见 application.yml）。运行方式：{@code mvn verify}。</p>
 */
@SpringBootTest(classes = GuaranteeAiAdminApplication.class,
        properties = {"guarantee.data-init.enabled=false"})
class OrderDistributionToolIT {

    private static final LocalDate Q2_START = LocalDate.of(2026, 4, 1);
    private static final LocalDate Q2_END = LocalDate.of(2026, 6, 30);

    @Autowired
    private OrderDistributionTool tool;

    @Autowired
    private OrderAnalysisService orderAnalysisService;

    @Autowired
    private OrderStatisticsService orderStatisticsService;

    @Autowired
    private AiToolRegistry registry;

    @Test
    @DisplayName("区域维度：与数据概览的分布口径逐字段一致，且合计等于同区间的投标订单总量")
    void regionDistributionMatchesAnalysisModule() {
        OrderDistributionToolResult result =
                tool.queryOrderDistribution("REGION", "TENDER", "2026-04-01", "2026-06-30", null, null, null);

        List<OrderRegionVO> expected = orderAnalysisService.regionDistribution(regionCriteria(), 10);

        assertThat(expected).as("演示数据缺失：先跑 scripts/reset-demo-data.ps1 并启动一次后端生成数据")
                .isNotEmpty();
        assertThat(result.items()).as("条数与排名都应与页面上看到的一致")
                .hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            OrderDistributionToolResult.DistributionItem actual = result.items().get(i);
            OrderRegionVO vo = expected.get(i);
            assertThat(actual.rank()).isEqualTo(i + 1);
            assertThat(actual.code()).isEqualTo(vo.getRegionCode());
            assertThat(actual.name()).isEqualTo(vo.getRegionName());
            assertThat(actual.orderCount()).isEqualTo(vo.getOrderCount());
            assertThat(actual.guaranteeAmount()).isEqualByComparingTo(vo.getGuaranteeAmount());
            assertThat(actual.premiumAmount()).isEqualByComparingTo(vo.getPremiumAmount());
            assertThat(actual.enterpriseCount()).isEqualTo(vo.getEnterpriseCount());
        }

        // 跨模块口径：区域分布只是把同一批订单按区域切开，合计必须等于汇总工具的总量。
        // 对不上就说明两处口径已经分叉（那正是"助手说的数和页面对不上"的根源）。
        long distributed = result.items().stream()
                .mapToLong(OrderDistributionToolResult.DistributionItem::orderCount).sum();
        OrderSummaryCriteria summaryCriteria = new OrderSummaryCriteria();
        summaryCriteria.setOrderType("TENDER");
        summaryCriteria.setStartDate(Q2_START);
        summaryCriteria.setEndDate(Q2_END);
        long total = orderStatisticsService.summarize(summaryCriteria).getOrderCount();
        assertThat(distributed)
                .as("按区域切开的订单合计必须等于同区间的投标订单总量")
                .isEqualTo(total);

        assertThat(result.meta().dataSource())
                .contains("订单分布").contains("维度：区域").contains("2026-04-01 ~ 2026-06-30")
                .doesNotContain("queryOrderDistribution").doesNotContain("orderType");
    }

    @Test
    @DisplayName("机构维度：与机构分布口径一致，且机构名来自机构表而不是编码")
    void orgDistributionMatchesAnalysisModule() {
        OrderDistributionToolResult result =
                tool.queryOrderDistribution("ORG", "TENDER", "2026-04-01", "2026-06-30", null, null, 5);

        List<OrderInstitutionVO> expected = orderAnalysisService.institutionDistribution(regionCriteria(), 5);

        assertThat(result.items()).hasSameSizeAs(expected).isNotEmpty();
        for (int i = 0; i < expected.size(); i++) {
            OrderDistributionToolResult.DistributionItem actual = result.items().get(i);
            OrderInstitutionVO vo = expected.get(i);
            assertThat(actual.code()).isEqualTo(vo.getOrgCode());
            assertThat(actual.name()).isEqualTo(vo.getOrgName()).isNotBlank();
            assertThat(actual.orderCount()).isEqualTo(vo.getOrderCount());
            assertThat(actual.enterpriseCount()).isEqualTo(vo.getEnterpriseCount());
        }
    }

    @Test
    @DisplayName("险种维度：与险种分布口径一致，且按订单量倒序")
    void insuranceDistributionMatchesAnalysisModule() {
        OrderDistributionToolResult result =
                tool.queryOrderDistribution("INSURANCE", "TENDER", null, null, null, null, null);

        // 必须用**同一个订单类型**取期望值：ALL 会把履约险种也带进来，
        // 拿它去比 TENDER 的结果会得到"排名对不上"的假失败（本用例第一版就踩了这个）
        AnalysisCriteria criteria = new AnalysisCriteria();
        criteria.setOrderType("TENDER");
        List<OrderInsuranceVO> expected = orderAnalysisService.insuranceDistribution(criteria);

        assertThat(result.items()).hasSizeLessThanOrEqualTo(expected.size()).isNotEmpty();
        for (int i = 0; i < result.items().size(); i++) {
            OrderDistributionToolResult.DistributionItem actual = result.items().get(i);
            OrderInsuranceVO vo = expected.get(i);
            assertThat(actual.code()).isEqualTo(vo.getTypeCode());
            assertThat(actual.name()).isEqualTo(vo.getTypeName()).isNotBlank();
            assertThat(actual.orderCount()).isEqualTo(vo.getOrderCount());
            assertThat(actual.enterpriseCount()).as("险种维度没有去重企业数的语义").isNull();
        }
        assertThat(result.items()).extracting(OrderDistributionToolResult.DistributionItem::orderCount)
                .as("分布必须按订单量倒序，否则排名没有意义")
                .isSortedAccordingTo((a, b) -> Long.compare(b, a));
    }

    @Test
    @DisplayName("交叉过滤：带 regionCode 的险种分布 = 同条件 Service 逐字段一致，且与区域维度合计一致")
    void insuranceDistributionWithRegionFilterMatchesAnalysisModule() {
        // REQ-BA-01 的样例：「浙江省 Q2 各险种结构」= 一次调用（dimension=INSURANCE + regionCode）
        OrderDistributionToolResult result = tool.queryOrderDistribution(
                "INSURANCE", "TENDER", "2026-04-01", "2026-06-30", "330000", null, 50);

        AnalysisCriteria criteria = new AnalysisCriteria();
        criteria.setOrderType("TENDER");
        criteria.setStartDate(Q2_START);
        criteria.setEndDate(Q2_END);
        criteria.setRegionCode("330000");
        List<OrderInsuranceVO> expected = orderAnalysisService.insuranceDistribution(criteria);

        assertThat(expected).as("浙江省是演示数据权重最高的地区，为空说明演示数据没初始化")
                .isNotEmpty();
        assertThat(result.items()).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            OrderDistributionToolResult.DistributionItem actual = result.items().get(i);
            OrderInsuranceVO vo = expected.get(i);
            assertThat(actual.rank()).isEqualTo(i + 1);
            assertThat(actual.code()).isEqualTo(vo.getTypeCode());
            assertThat(actual.name()).isEqualTo(vo.getTypeName()).isNotBlank();
            assertThat(actual.orderCount()).isEqualTo(vo.getOrderCount());
            assertThat(actual.guaranteeAmount()).isEqualByComparingTo(vo.getGuaranteeAmount());
            assertThat(actual.premiumAmount()).isEqualByComparingTo(vo.getPremiumAmount());
            assertThat(actual.enterpriseCount()).as("险种维度没有去重企业数的语义").isNull();
        }

        // 跨维度口径：把同一批浙江省订单按险种切开的合计，必须等于按区域切开的那个省的总量。
        // 对不上就说明"区域前缀过滤"只作用在部分查询上（那正是助手与页面对不上的根源）。
        List<OrderRegionVO> zhejiang = orderAnalysisService.regionDistribution(criteria, 10);
        assertThat(zhejiang).as("区域维度带上 regionCode=330000 后应只剩这一行").hasSize(1);
        long byInsurance = result.items().stream()
                .mapToLong(OrderDistributionToolResult.DistributionItem::orderCount).sum();
        assertThat(byInsurance)
                .as("浙江省各险种订单合计必须等于浙江省总量")
                .isEqualTo(zhejiang.get(0).getOrderCount());

        assertThat(result.meta().dataSource())
                .contains("订单分布").contains("维度：险种").contains("区域：330000（浙江省）")
                .contains("2026-04-01 ~ 2026-06-30")
                .doesNotContain("queryOrderDistribution").doesNotContain("regionCode")
                .doesNotContain("orderType");
    }

    @Test
    @DisplayName("只持 ai:chat 的只读用户也能拿到该工具（与 queryOrderSummary 同权限口径，不新增权限码）")
    void registryExposesToolToPlainChatUsers() {
        assertThat(registry.availableToolNames(List.of("ai:chat")))
                .as("现场提问的用户（运营/只读身份）本来就看不到订单分布的替代品，"
                        + "这里必须同样可见，否则又是一次「为什么他不能」")
                .contains("queryOrderDistribution", "queryOrderSummary", "queryOrderTrend");
    }

    private static AnalysisCriteria regionCriteria() {
        AnalysisCriteria criteria = new AnalysisCriteria();
        criteria.setOrderType("TENDER");
        criteria.setStartDate(Q2_START);
        criteria.setEndDate(Q2_END);
        return criteria;
    }
}
