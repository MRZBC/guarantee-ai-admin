package com.guarantee.ai.tool;

import com.guarantee.analysis.dto.OrderTrendQuery;
import com.guarantee.analysis.service.OrderAnalysisService;
import com.guarantee.analysis.vo.OrderTrendVO;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code queryOrderTrend} 的单元测试（不需要数据库）。
 *
 * <p><b>防的是什么</b>：趋势问题的头号伪答案是"两个区间相减"——它看起来像趋势，其实只有
 * 两个点，中间的涨跌与拐点全丢了。本工具把数据概览页的趋势口径接到助手侧，因此这里要守住：</p>
 * <ol>
 *   <li>周期字段逐项映射（period/订单量/保额/保费），顺序保持升序；</li>
 *   <li>粒度大小写不敏感、非法值回落按月（与数据概览页同一行为，页面点得出来助手就得答得出）；</li>
 *   <li>超限时从**最近端**截断并显式标记：截错方向会把"两年前的走势"当成"现在的走势"；</li>
 *   <li>口径讲人话（粒度中文、险种业务名、区域带省名），且不泄漏工具名与英文参数名。</li>
 * </ol>
 */
class OrderTrendToolTest {

    private OrderAnalysisService orderAnalysisService;
    private OrderTrendTool tool;

    @BeforeEach
    void setUp() {
        orderAnalysisService = mock(OrderAnalysisService.class);
        tool = new OrderTrendTool(orderAnalysisService);
    }

    @Test
    @DisplayName("周期逐字段映射：period/订单量/保额/保费，顺序与 Service 升序一致")
    void pointsMapEveryFieldInServiceOrder() {
        when(orderAnalysisService.trend(any())).thenReturn(List.of(
                trend("2026-04", 120, "1200000.50", "6000.25"),
                trend("2026-05", 150, "1800000.00", "9000.00"),
                trend("2026-06", 90, "900000.10", "4500.05")));

        OrderTrendToolResult result = tool.queryOrderTrend(
                "TENDER", "2026-04-01", "2026-06-30", "MONTH", "330000", 2L, 24);

        assertThat(result.orderType()).isEqualTo("TENDER");
        assertThat(result.granularity()).isEqualTo("month");
        assertThat(result.startDate()).isEqualTo("2026-04-01");
        assertThat(result.endDate()).isEqualTo("2026-06-30");
        assertThat(result.regionCode()).isEqualTo("330000");
        assertThat(result.orgId()).isEqualTo(2L);
        assertThat(result.meta().denied()).isFalse();
        assertThat(result.meta().truncated()).isFalse();

        assertThat(result.points()).hasSize(3);
        assertThat(result.points()).extracting(OrderTrendToolResult.TrendPoint::period)
                .containsExactly("2026-04", "2026-05", "2026-06");
        OrderTrendToolResult.TrendPoint first = result.points().get(0);
        assertThat(first.orderCount()).isEqualTo(120);
        assertThat(first.guaranteeAmount()).isEqualByComparingTo("1200000.50");
        assertThat(first.premiumAmount()).isEqualByComparingTo("6000.25");
    }

    @Test
    @DisplayName("粒度大小写不敏感、带空白可容错；非法值回落按月（与数据概览页同口径）")
    void granularityIsNormalizedCaseInsensitively() {
        when(orderAnalysisService.trend(any())).thenReturn(List.of());

        assertThat(tool.queryOrderTrend(null, null, null, "DAY", null, null, null).granularity())
                .isEqualTo("day");
        assertThat(tool.queryOrderTrend(null, null, null, "day", null, null, null).granularity())
                .isEqualTo("day");
        assertThat(tool.queryOrderTrend(null, null, null, " Year ", null, null, null).granularity())
                .isEqualTo("year");
        assertThat(tool.queryOrderTrend(null, null, null, "MONTH", null, null, null).granularity())
                .isEqualTo("month");
        assertThat(tool.queryOrderTrend(null, null, null, null, null, null, null).granularity())
                .as("缺省按月")
                .isEqualTo("month");
        assertThat(tool.queryOrderTrend(null, null, null, "WEEKLY", null, null, null).granularity())
                .as("无法识别的粒度回落按月：数据概览页也是这个行为，助手不应比页面更严")
                .isEqualTo("month");

        // 归一化必须发生在**传参之前**：Mapper 用 c.dayGranularity() 判断分支，
        // 传原始大小写进去虽然也能归一（DTO 内部又归一一次），但回显给模型的粒度必须已归一。
        ArgumentCaptor<OrderTrendQuery> captor = ArgumentCaptor.forClass(OrderTrendQuery.class);
        verify(orderAnalysisService, org.mockito.Mockito.atLeastOnce()).trend(captor.capture());
        assertThat(captor.getValue().normalizedGranularity()).isEqualTo("month");
    }

    @Test
    @DisplayName("条数：缺省 24、非法值退 24、超过上限压到 120")
    void limitIsNormalizedAndCapped() {
        assertThat(OrderTrendTool.normalizeLimit(null)).isEqualTo(24);
        assertThat(OrderTrendTool.normalizeLimit(0)).isEqualTo(24);
        assertThat(OrderTrendTool.normalizeLimit(-5)).isEqualTo(24);
        assertThat(OrderTrendTool.normalizeLimit(12)).isEqualTo(12);
        assertThat(OrderTrendTool.normalizeLimit(500)).isEqualTo(120);
    }

    @Test
    @DisplayName("超限时从最近端截断：保留最后 N 个周期，并在 truncated 里说清只给了最近 N 个")
    void truncatesFromTheMostRecentEnd() {
        List<OrderTrendVO> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            rows.add(trend(String.format("%d-%02d", 2024 + i / 12, (i % 12) + 1), 100 + i, "1000.00", "10.00"));
        }
        when(orderAnalysisService.trend(any())).thenReturn(rows);

        OrderTrendToolResult result = tool.queryOrderTrend(null, null, null, "DAY", null, null, null);

        assertThat(result.points()).as("默认上限 24 个点").hasSize(24);
        assertThat(result.points().get(0))
                .as("截断方向：必须保留**最近**的周期，否则模型会把两年前的走势当成现在")
                .isEqualTo(new OrderTrendToolResult.TrendPoint("2024-07", 106, new BigDecimal("1000.00"),
                        new BigDecimal("10.00")));
        assertThat(result.points().get(23).period()).isEqualTo("2026-06");
        assertThat(result.meta().truncated()).isTrue();
        assertThat(result.meta().truncatedHint())
                .contains("只给了最近 24 个周期")
                .contains("30");
    }

    @Test
    @DisplayName("未超限时不标记 truncated（不能把完整序列说成被截断）")
    void noTruncationWhenWithinLimit() {
        when(orderAnalysisService.trend(any())).thenReturn(List.of(trend("2026-01", 1, "1.00", "0.10")));

        OrderTrendToolResult result = tool.queryOrderTrend(null, null, null, "MONTH", null, null, 24);

        assertThat(result.meta().truncated()).isFalse();
        assertThat(result.meta().truncatedHint()).isNull();
        assertThat(result.points()).hasSize(1);
    }

    @Test
    @DisplayName("口径行讲人话：粒度中文、险种业务名、区域带省名、机构带 ID，且无英文参数名")
    void dataSourceIsHumanReadable() {
        when(orderAnalysisService.trend(any())).thenReturn(List.of());

        OrderTrendToolResult result = tool.queryOrderTrend(
                "TENDER", "2026-04-01", "2026-06-30", "MONTH", "330000", 2L, 12);

        assertThat(result.meta().dataSource())
                .contains("订单趋势")
                .contains("粒度：按月")
                .contains("投标保函")
                .contains("2026-04-01 ~ 2026-06-30")
                .contains("区域：330000（浙江省）")
                .contains("机构：2")
                .contains("最近 12 个周期")
                .doesNotContain("queryOrderTrend")
                .doesNotContain("orderType")
                .doesNotContain("TENDER")
                .doesNotContain("granularity")
                .doesNotContain("regionCode")
                .doesNotContain("orgId");
    }

    @Test
    @DisplayName("日期必须是 yyyy-MM-dd；起始晚于结束直接拒绝")
    void invalidDatesAreRejectedWithReadableMessage() {
        assertThatThrownBy(() -> tool.queryOrderTrend(null, "2026/04/01", "2026-06-30", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yyyy-MM-dd");
        assertThatThrownBy(() -> tool.queryOrderTrend(null, "2026-06-30", "2026-04-01", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能晚于");
    }

    @Test
    @DisplayName("工具定义可被 Spring AI 正确生成：名字与全部入参都要在 schema 里")
    void toolDefinitionIsGeneratedForTheModel() {
        var callbacks = org.springframework.ai.support.ToolCallbacks.from(
                new OrderTrendTool(mock(OrderAnalysisService.class)));

        assertThat(callbacks).singleElement().satisfies(callback -> {
            assertThat(callback.getToolDefinition().name()).isEqualTo("queryOrderTrend");
            assertThat(callback.getToolDefinition().inputSchema())
                    .contains("orderType").contains("startDate").contains("endDate")
                    .contains("granularity").contains("regionCode").contains("orgId").contains("limit");
            assertThat(callback.getToolDefinition().description())
                    .as("描述必须点名趋势场景、区分'两区间比大小'，并说明只给最近 N 个周期")
                    .contains("趋势")
                    .contains("queryOrderSummary")
                    .contains("最近");
        });
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private static OrderTrendVO trend(String period, long count, String guarantee, String premium) {
        OrderTrendVO vo = new OrderTrendVO();
        vo.setPeriod(period);
        vo.setOrderCount(count);
        vo.setGuaranteeAmount(new BigDecimal(guarantee));
        vo.setPremiumAmount(new BigDecimal(premium));
        return vo;
    }
}
