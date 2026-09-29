package com.guarantee.web.ai;

import com.guarantee.ai.tool.AiToolRegistry;
import com.guarantee.ai.tool.OrderTrendTool;
import com.guarantee.ai.tool.OrderTrendToolResult;
import com.guarantee.analysis.dto.OrderTrendQuery;
import com.guarantee.analysis.service.OrderAnalysisService;
import com.guarantee.analysis.vo.OrderTrendVO;
import com.guarantee.web.GuaranteeAiAdminApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code queryOrderTrend} 打到真实数据库的验证（不需要 LLM API Key）。
 *
 * <p><b>为什么必须有它</b>：趋势是"看起来最容易糊弄"的一类问题——模型完全可以拿两个区间的
 * 总量相减，编出一条只有两个点的"趋势"。本工具把**数据概览页的趋势口径**
 * （{@link OrderAnalysisService#trend(OrderTrendQuery)}）接到助手侧，因此这里要守的是
 * "助手看到的序列 = 页面上看到的序列"：</p>
 * <ul>
 *   <li>每个周期逐字段与 Service 一致（period / 订单量 / 保函金额 / 保费）；</li>
 *   <li>三种粒度真的产出三种 period 格式，且粒度只改分组、不改口径（订单总数一致）；</li>
 *   <li>超限时从**最近端**截断（返回的正是 Service 序列的末尾 N 个），并带 truncated 提示。</li>
 * </ul>
 *
 * <p>需要可用的 MySQL（见 application.yml）。运行方式：{@code mvn verify}。</p>
 */
@SpringBootTest(classes = GuaranteeAiAdminApplication.class,
        properties = {"guarantee.data-init.enabled=false"})
class OrderTrendToolIT {

    private static final LocalDate Q2_START = LocalDate.of(2026, 4, 1);
    private static final LocalDate Q2_END = LocalDate.of(2026, 6, 30);

    private static final Pattern DAY_PERIOD = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern MONTH_PERIOD = Pattern.compile("\\d{4}-\\d{2}");
    private static final Pattern YEAR_PERIOD = Pattern.compile("\\d{4}");

    @Autowired
    private OrderTrendTool tool;

    @Autowired
    private OrderAnalysisService orderAnalysisService;

    @Autowired
    private AiToolRegistry registry;

    @Test
    @DisplayName("按月趋势：与数据概览的 trend 口径逐字段一致（周期/订单量/保额/保费），口径行可读")
    void monthlyTrendMatchesAnalysisService() {
        OrderTrendToolResult result = tool.queryOrderTrend(
                "TENDER", "2026-04-01", "2026-06-30", "MONTH", null, null, 120);

        List<OrderTrendVO> expected = orderAnalysisService.trend(query("MONTH"));
        assertThat(expected).as("演示数据缺失：先跑 scripts/reset-demo-data.ps1 并启动一次后端生成数据")
                .isNotEmpty();

        assertThat(result.granularity()).isEqualTo("month");
        assertThat(result.points()).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            OrderTrendToolResult.TrendPoint actual = result.points().get(i);
            OrderTrendVO vo = expected.get(i);
            assertThat(actual.period()).isEqualTo(vo.getPeriod());
            assertThat(actual.orderCount()).isEqualTo(vo.getOrderCount());
            assertThat(actual.guaranteeAmount()).isEqualByComparingTo(vo.getGuaranteeAmount());
            assertThat(actual.premiumAmount()).isEqualByComparingTo(vo.getPremiumAmount());
        }
        assertThat(result.meta().truncated()).as("3 个月的点数远低于上限").isFalse();

        assertThat(result.meta().dataSource())
                .contains("订单趋势").contains("粒度：按月").contains("投标保函")
                .contains("2026-04-01 ~ 2026-06-30")
                .doesNotContain("queryOrderTrend").doesNotContain("orderType");
    }

    @Test
    @DisplayName("三种粒度：period 格式各不相同（日/月/年），点数 日>月>年，且订单总数一致")
    void granularityProducesDistinctPeriodFormats() {
        OrderTrendToolResult day = tool.queryOrderTrend(
                "TENDER", "2026-04-01", "2026-06-30", "DAY", null, null, 120);
        OrderTrendToolResult month = tool.queryOrderTrend(
                "TENDER", "2026-04-01", "2026-06-30", "month", null, null, 120);
        OrderTrendToolResult year = tool.queryOrderTrend(
                "TENDER", "2026-04-01", "2026-06-30", "Year", null, null, 120);

        assertThat(day.granularity()).isEqualTo("day");
        assertThat(month.granularity()).isEqualTo("month");
        assertThat(year.granularity()).isEqualTo("year");

        assertThat(day.points()).as("Q2 的日粒度点数为 0 说明演示数据没初始化").isNotEmpty();
        assertThat(month.points()).isNotEmpty();
        assertThat(year.points()).isNotEmpty();

        assertThat(day.points()).allSatisfy(p -> assertThat(p.period()).matches(DAY_PERIOD));
        assertThat(month.points()).allSatisfy(p -> assertThat(p.period()).matches(MONTH_PERIOD));
        assertThat(year.points()).allSatisfy(p -> assertThat(p.period()).matches(YEAR_PERIOD));

        assertThat(day.points().size())
                .as("粒度越细点越多——这正是「三个粒度长得一样」的反面")
                .isGreaterThan(month.points().size());
        assertThat(month.points().size()).isGreaterThan(year.points().size());

        long monthlyTotal = total(month);
        assertThat(total(day)).as("粒度只改分组，订单总数必须一致").isEqualTo(monthlyTotal);
        assertThat(total(year)).as("粒度只改分组，订单总数必须一致").isEqualTo(monthlyTotal);
    }

    @Test
    @DisplayName("超限从最近端截断：返回的正是 Service 序列的末尾 N 个周期，并如实标记 truncated")
    void truncationKeepsTheMostRecentPeriods() {
        OrderTrendToolResult result = tool.queryOrderTrend(null, null, null, "DAY", null, null, 5);

        // 期望值必须与工具调用**同条件**：不传订单类型与日期 = 全量（这不是笔误）
        OrderTrendQuery allQuery = new OrderTrendQuery();
        allQuery.setGranularity("DAY");
        List<OrderTrendVO> all = orderAnalysisService.trend(allQuery);
        assertThat(all.size())
                .as("全部演示数据跨 2 个自然年，按日的周期数必然多于 5")
                .isGreaterThan(5);

        assertThat(result.points()).hasSize(5);
        List<OrderTrendVO> tail = all.subList(all.size() - 5, all.size());
        for (int i = 0; i < tail.size(); i++) {
            OrderTrendToolResult.TrendPoint actual = result.points().get(i);
            OrderTrendVO vo = tail.get(i);
            assertThat(actual.period())
                    .as("截断必须从最近端切：返回的应是序列尾部，而不是开头的 5 天")
                    .isEqualTo(vo.getPeriod());
            assertThat(actual.orderCount()).isEqualTo(vo.getOrderCount());
        }

        assertThat(result.meta().truncated()).isTrue();
        assertThat(result.meta().truncatedHint()).contains("只给了最近 5 个周期");
    }

    @Test
    @DisplayName("只持 ai:chat 的只读用户也能拿到趋势工具（与 queryOrderSummary 同权限口径，不新增权限码）")
    void registryExposesTrendToolToPlainChatUsers() {
        assertThat(registry.availableToolNames(List.of("ai:chat")))
                .contains("queryOrderTrend", "queryOrderSummary", "queryOrderDistribution");
    }

    private static OrderTrendQuery query(String granularity) {
        OrderTrendQuery query = new OrderTrendQuery();
        query.setOrderType("TENDER");
        query.setStartDate(Q2_START);
        query.setEndDate(Q2_END);
        query.setGranularity(granularity);
        return query;
    }

    private static long total(OrderTrendToolResult result) {
        return result.points().stream().mapToLong(OrderTrendToolResult.TrendPoint::orderCount).sum();
    }
}
