package com.guarantee.ai.tool;

import com.guarantee.analysis.dto.AnalysisCriteria;
import com.guarantee.analysis.service.OrderAnalysisService;
import com.guarantee.analysis.vo.OrderInstitutionVO;
import com.guarantee.analysis.vo.OrderInsuranceVO;
import com.guarantee.analysis.vo.OrderRegionVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code queryOrderDistribution} 的单元测试（不需要数据库）。
 *
 * <p>防的是什么：2026-09-29 现场「请分析 2026 年第二季度投标订单，和第一季度比较，
 * 并从区域、机构、险种三个维度找出主要变化」——助手当时**没有**任何按维度分组的工具，
 * 只能逐个区域去调汇总工具（36 次调用耗尽轮次）或如实回答"我给不出"。
 * 本工具把数据概览页已有的分布口径接到助手侧，因此这里要守住三件事：</p>
 * <ol>
 *   <li>三个维度各自映射到正确的 Service 方法与字段（码/名取错就会出现"区域名是机构码"）；</li>
 *   <li>维度写法容错（大小写、中文别名），非法值必须报可读错误而不是静默查成别的维度；</li>
 *   <li>条数与截断如实告知（{@code truncated} 不为空时模型才知道"只看过前 N 名"）。</li>
 * </ol>
 *
 * <p>阶段二收尾（REQ-BA-01）追加第四件事：交叉过滤必须**先过滤再分组**——
 * 「浙江省的险种结构」是一次调用，而不是让模型逐个对象去试。</p>
 */
class OrderDistributionToolTest {

    private OrderAnalysisService orderAnalysisService;
    private OrderDistributionTool tool;

    @BeforeEach
    void setUp() {
        orderAnalysisService = mock(OrderAnalysisService.class);
        tool = new OrderDistributionTool(orderAnalysisService);
    }

    @Test
    @DisplayName("区域维度：码/名/量/额/企业数逐字段映射，排名从 1 开始")
    void regionDimensionMapsEveryField() {
        when(orderAnalysisService.regionDistribution(any(), anyInt()))
                .thenReturn(List.of(region("330000", "浙江省", 5427, "33317606376.87", "257976203.08", 1052),
                        region("320000", "江苏省", 3647, "21321126282.02", "165734038.23", 758)));

        OrderDistributionToolResult result = tool.queryOrderDistribution(
                "REGION", "TENDER", "2026-04-01", "2026-06-30", null, null, null);

        assertThat(result.dimension()).isEqualTo("REGION");
        assertThat(result.orderType()).isEqualTo("TENDER");
        assertThat(result.items()).hasSize(2);
        OrderDistributionToolResult.DistributionItem first = result.items().get(0);
        assertThat(first.rank()).isEqualTo(1);
        assertThat(first.code()).isEqualTo("330000");
        assertThat(first.name()).isEqualTo("浙江省");
        assertThat(first.orderCount()).isEqualTo(5427);
        assertThat(first.guaranteeAmount()).isEqualByComparingTo("33317606376.87");
        assertThat(first.premiumAmount()).isEqualByComparingTo("257976203.08");
        assertThat(first.enterpriseCount()).isEqualTo(1052);
        assertThat(result.items().get(1).rank()).isEqualTo(2);
        assertThat(result.meta().denied()).isFalse();
    }

    @Test
    @DisplayName("机构维度：走机构分布，带机构编码与机构名")
    void orgDimensionUsesInstitutionDistribution() {
        when(orderAnalysisService.institutionDistribution(any(), anyInt()))
                .thenReturn(List.of(institution("ORG3301", "浙江省第1保函运营机构", 922, 621)));

        OrderDistributionToolResult result = tool.queryOrderDistribution(
                "机构", "TENDER", "2026-04-01", "2026-06-30", null, null, 5);

        assertThat(result.dimension()).isEqualTo("ORG");
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.code()).isEqualTo("ORG3301");
            assertThat(item.name()).isEqualTo("浙江省第1保函运营机构");
            assertThat(item.enterpriseCount()).isEqualTo(621);
        });
        verify(orderAnalysisService).institutionDistribution(any(), org.mockito.ArgumentMatchers.eq(5));
    }

    @Test
    @DisplayName("险种维度：企业数无意义填 null，并按 limit 截断且如实标记 truncated")
    void insuranceDimensionTruncatesAndFlagsIt() {
        List<OrderInsuranceVO> rows = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            OrderInsuranceVO vo = new OrderInsuranceVO();
            vo.setTypeCode("TYPE" + i);
            vo.setTypeName("险种" + i);
            vo.setOrderCount(100 - i);
            vo.setGuaranteeAmount(BigDecimal.valueOf(1000L * i));
            vo.setPremiumAmount(BigDecimal.valueOf(10L * i));
            rows.add(vo);
        }
        when(orderAnalysisService.insuranceDistribution(any())).thenReturn(rows);

        OrderDistributionToolResult result = tool.queryOrderDistribution(
                "INSURANCE", "ALL", null, null, null, null, 3);

        assertThat(result.items()).hasSize(3);
        assertThat(result.items()).extracting(OrderDistributionToolResult.DistributionItem::rank)
                .containsExactly(1, 2, 3);
        assertThat(result.items()).allSatisfy(item -> assertThat(item.enterpriseCount()).isNull());
        assertThat(result.meta().truncated())
                .as("只返回前 3 条时必须告诉模型可能还有更多，否则它会当成全部")
                .isTrue();
        assertThat(result.meta().truncatedHint()).contains("前 3 条");
    }

    @Test
    @DisplayName("交叉过滤：regionCode/orgId 透传到 criteria（先过滤再分组），口径行带区域名与机构 ID")
    void crossFilterGoesToCriteriaAndDataSource() {
        when(orderAnalysisService.insuranceDistribution(any())).thenReturn(List.of());

        OrderDistributionToolResult result = tool.queryOrderDistribution(
                "INSURANCE", "TENDER", "2026-04-01", "2026-06-30", "330000", 2L, 10);

        // 一次调用就把"浙江省 + 某机构 + 投标险种结构"查齐——这才是 REQ-BA-01 要的形态，
        // 而不是让模型先查区域、再逐个险种去试。
        ArgumentCaptor<AnalysisCriteria> captor = ArgumentCaptor.forClass(AnalysisCriteria.class);
        verify(orderAnalysisService).insuranceDistribution(captor.capture());
        AnalysisCriteria criteria = captor.getValue();
        assertThat(criteria.getRegionCode())
                .as("区域过滤必须进 criteria，否则查出来的是全国口径")
                .isEqualTo("330000");
        assertThat(criteria.getOrgId()).isEqualTo(2L);
        assertThat(criteria.getStartDate()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(criteria.getEndDate()).isEqualTo(LocalDate.of(2026, 6, 30));

        assertThat(result.meta().dataSource())
                .as("口径行要能让用户看出'这是浙江省、这家机构的数字'")
                .contains("区域：330000（浙江省）")
                .contains("机构：2")
                .doesNotContain("regionCode")
                .doesNotContain("orgId");
    }

    @Test
    @DisplayName("维度写法容错：大小写与中文别名都认，非法值给可读错误")
    void dimensionAcceptsAliasesAndRejectsUnknown() {
        when(orderAnalysisService.regionDistribution(any(), anyInt())).thenReturn(List.of());
        when(orderAnalysisService.institutionDistribution(any(), anyInt())).thenReturn(List.of());

        assertThat(tool.queryOrderDistribution("region", null, null, null, null, null, null).dimension())
                .isEqualTo("REGION");
        assertThat(tool.queryOrderDistribution(" 区域 ", null, null, null, null, null, null).dimension())
                .isEqualTo("REGION");
        assertThat(tool.queryOrderDistribution("Institution", null, null, null, null, null, null).dimension())
                .isEqualTo("ORG");

        assertThatThrownBy(() -> tool.queryOrderDistribution("季度", null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REGION")
                .hasMessageContaining("险种");
        assertThatThrownBy(() -> tool.queryOrderDistribution(null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("条数：缺省 10、非法值退 10、超过上限压到 50（不把上下文塞满）")
    void limitIsNormalizedAndCapped() {
        assertThat(OrderDistributionTool.normalizeLimit(null)).isEqualTo(10);
        assertThat(OrderDistributionTool.normalizeLimit(0)).isEqualTo(10);
        assertThat(OrderDistributionTool.normalizeLimit(-3)).isEqualTo(10);
        assertThat(OrderDistributionTool.normalizeLimit(5)).isEqualTo(5);
        assertThat(OrderDistributionTool.normalizeLimit(500)).isEqualTo(50);
    }

    @Test
    @DisplayName("时间与口径：日期透传到 criteria，口径行讲人话（含维度/险种名/区间）")
    void dateRangeGoesToCriteriaAndDataSourceIsHumanReadable() {
        when(orderAnalysisService.regionDistribution(any(), anyInt())).thenReturn(List.of());

        OrderDistributionToolResult result = tool.queryOrderDistribution(
                "REGION", "TENDER", "2026-04-01", "2026-06-30", null, null, 10);

        ArgumentCaptor<AnalysisCriteria> captor = ArgumentCaptor.forClass(AnalysisCriteria.class);
        verify(orderAnalysisService).regionDistribution(captor.capture(), anyInt());
        AnalysisCriteria criteria = captor.getValue();
        assertThat(criteria.getStartDate()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(criteria.getEndDate()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(criteria.normalizedOrderType()).isEqualTo("TENDER");

        assertThat(result.meta().dataSource())
                .as("口径行是给业务用户看的：维度用中文、险种用业务名，且不得出现工具名与英文参数")
                .contains("订单分布").contains("维度：区域").contains("投标保函")
                .contains("2026-04-01 ~ 2026-06-30")
                .doesNotContain("queryOrderDistribution").doesNotContain("orderType")
                .doesNotContain("TENDER");
    }

    @Test
    @DisplayName("起始日期晚于结束日期：直接拒绝，不发出无意义的查询")
    void invertedDateRangeIsRejected() {
        assertThatThrownBy(() -> tool.queryOrderDistribution(
                "REGION", "TENDER", "2026-06-30", "2026-04-01", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能晚于");
    }

    @Test
    @DisplayName("工具定义可被 Spring AI 正确生成：名字与入参 schema 模型能看懂")
    void toolDefinitionIsGeneratedForTheModel() {
        var callbacks = org.springframework.ai.support.ToolCallbacks.from(
                new OrderDistributionTool(mock(OrderAnalysisService.class)));

        assertThat(callbacks).singleElement().satisfies(callback -> {
            assertThat(callback.getToolDefinition().name()).isEqualTo("queryOrderDistribution");
            assertThat(callback.getToolDefinition().inputSchema())
                    .as("模型只能按 schema 传参：维度/订单类型/日期/区域/机构/条数都要出现")
                    .contains("dimension").contains("orderType")
                    .contains("startDate").contains("endDate")
                    .contains("regionCode").contains("orgId").contains("limit");
            assertThat(callback.getToolDefinition().description())
                    .as("描述里必须点名三个维度、「对比就各调一次」以及区域前缀匹配，"
                            + "否则模型会退回去逐个查汇总或把「某区域的险种结构」拆成多次")
                    .contains("REGION").contains("ORG").contains("INSURANCE")
                    .contains("queryOrderSummary")
                    .contains("层级前缀");
        });
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private static OrderRegionVO region(String code, String name, long count,
                                        String guarantee, String premium, long enterprises) {
        OrderRegionVO vo = new OrderRegionVO();
        vo.setRegionCode(code);
        vo.setRegionName(name);
        vo.setOrderCount(count);
        vo.setGuaranteeAmount(new BigDecimal(guarantee));
        vo.setPremiumAmount(new BigDecimal(premium));
        vo.setEnterpriseCount(enterprises);
        return vo;
    }

    private static OrderInstitutionVO institution(String code, String name, long count, long enterprises) {
        OrderInstitutionVO vo = new OrderInstitutionVO();
        vo.setOrgCode(code);
        vo.setOrgName(name);
        vo.setOrderCount(count);
        vo.setGuaranteeAmount(BigDecimal.ONE);
        vo.setPremiumAmount(BigDecimal.ONE);
        vo.setEnterpriseCount(enterprises);
        return vo;
    }
}
