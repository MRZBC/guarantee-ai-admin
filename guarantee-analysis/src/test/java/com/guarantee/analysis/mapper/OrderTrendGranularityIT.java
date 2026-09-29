package com.guarantee.analysis.mapper;

import com.guarantee.analysis.AnalysisItConfig;
import com.guarantee.analysis.dto.OrderTrendQuery;
import com.guarantee.analysis.vo.OrderTrendVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 趋势粒度落到真实 SQL 上的验证（需要 MySQL + 演示数据，见 test/resources/application.yml 的约定）。
 *
 * <p><b>防的是什么</b>：现场反馈「数据概览的按日 / 按月 / 按年按钮无效，展示的都是相同内容」。
 * 单纯断言 DTO 归一化（{@code OrderTrendQueryTest}）或 XML 文本（{@code TrendGranularityMapperXmlTest}）
 * 都只是间接证据——这里直接查库，断言三种粒度**真的**产出三种不同的 period：</p>
 * <ul>
 *   <li>按日 {@code yyyy-MM-dd}、按月 {@code yyyy-MM}、按年 {@code yyyy}；</li>
 *   <li>周期数按 日 &gt; 月 &gt; 年 递减（同一个时间范围，粒度越细点越多）；</li>
 *   <li>三者订单数合计必须相等——粒度只改分组方式，不能改口径。</li>
 * </ul>
 */
@SpringBootTest(classes = AnalysisItConfig.class)
class OrderTrendGranularityIT {

    private static final Pattern DAY_PERIOD = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern MONTH_PERIOD = Pattern.compile("\\d{4}-\\d{2}");
    private static final Pattern YEAR_PERIOD = Pattern.compile("\\d{4}");

    @Autowired
    private OrderAnalysisMapper orderAnalysisMapper;

    @Test
    @DisplayName("按日 / 按月 / 按年产出三种不同的 period，且口径一致")
    void granularityProducesDistinctPeriods() {
        List<OrderTrendVO> day = trend("DAY");
        List<OrderTrendVO> month = trend("MONTH");
        List<OrderTrendVO> year = trend("YEAR");

        assertThat(day).as("演示数据缺失：先跑 scripts/reset-demo-data.ps1 并启动一次后端生成数据").isNotEmpty();
        assertThat(month).isNotEmpty();
        assertThat(year).isNotEmpty();

        assertThat(day).allSatisfy(row -> assertThat(row.getPeriod()).matches(DAY_PERIOD));
        assertThat(month).allSatisfy(row -> assertThat(row.getPeriod()).matches(MONTH_PERIOD));
        assertThat(year).allSatisfy(row -> assertThat(row.getPeriod()).matches(YEAR_PERIOD));

        assertThat(day.size())
                .as("按日的点数必须多于按月——这正是「三个粒度长得一样」的反面")
                .isGreaterThan(month.size());
        assertThat(month.size())
                .as("按月的点数必须多于按年（数据区间跨 2 个自然年）")
                .isGreaterThan(year.size());

        long total = totalOrders(month);
        assertThat(totalOrders(day)).as("粒度只改分组，订单总数必须一致").isEqualTo(total);
        assertThat(totalOrders(year)).as("粒度只改分组，订单总数必须一致").isEqualTo(total);
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private List<OrderTrendVO> trend(String granularity) {
        OrderTrendQuery query = new OrderTrendQuery();
        query.setGranularity(granularity);
        return orderAnalysisMapper.selectTrend(query);
    }

    private static long totalOrders(List<OrderTrendVO> trend) {
        return trend.stream().mapToLong(OrderTrendVO::getOrderCount).sum();
    }
}
