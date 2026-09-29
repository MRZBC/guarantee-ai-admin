package com.guarantee.analysis.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 趋势粒度归一化的守卫测试。
 *
 * <p><b>防的是什么</b>：数据概览「投标订单趋势」右上角的「按日 / 按月 / 按年」点哪个图都一样。
 * 根因有两层，缺任何一层都不会是这个现象：</p>
 * <ol>
 *   <li>{@link OrderTrendQuery.Granularity#normalize(String)} 早期只认小写 {@code day}，
 *       而前端 {@code Granularity} 类型发的是大写 {@code DAY / MONTH / YEAR}
 *       → {@code DAY}/{@code YEAR} 都被吞成按月；</li>
 *   <li>Mapper 直接比原始字段（{@code c.granularity == 'day'}，OGNL 字符串比较区分大小写），
 *       归一化结果根本没被用上 → 连归一化对了也仍然全部落到月分支。</li>
 * </ol>
 *
 * <p>本测试守第 1 层（第 2 层由 {@code TrendGranularityMapperXmlTest} 守）。</p>
 */
class OrderTrendQueryTest {

    @Test
    @DisplayName("粒度归一化忽略大小写与首尾空白，未知值一律退回 month")
    void normalizeIsCaseInsensitive() {
        assertThat(OrderTrendQuery.Granularity.normalize("DAY")).isEqualTo("day");
        assertThat(OrderTrendQuery.Granularity.normalize("day")).isEqualTo("day");
        assertThat(OrderTrendQuery.Granularity.normalize(" Day ")).isEqualTo("day");

        assertThat(OrderTrendQuery.Granularity.normalize("MONTH")).isEqualTo("month");
        assertThat(OrderTrendQuery.Granularity.normalize("month")).isEqualTo("month");

        assertThat(OrderTrendQuery.Granularity.normalize("YEAR")).isEqualTo("year");
        assertThat(OrderTrendQuery.Granularity.normalize("Year")).isEqualTo("year");

        assertThat(OrderTrendQuery.Granularity.normalize(null)).as("缺省 = 按月").isEqualTo("month");
        assertThat(OrderTrendQuery.Granularity.normalize("   ")).as("空串 = 按月").isEqualTo("month");
        assertThat(OrderTrendQuery.Granularity.normalize("week")).as("非法值不得进 SQL").isEqualTo("month");
    }

    @Test
    @DisplayName("Mapper 侧的粒度判断方法与前端传参一致（前端发 DAY/MONTH/YEAR）")
    void granularityPredicatesMatchFrontendValues() {
        OrderTrendQuery query = new OrderTrendQuery();
        assertThat(query.dayGranularity()).as("默认按月").isFalse();
        assertThat(query.yearGranularity()).as("默认按月").isFalse();

        query.setGranularity("DAY");
        assertThat(query.dayGranularity()).isTrue();
        assertThat(query.yearGranularity()).isFalse();

        query.setGranularity("YEAR");
        assertThat(query.dayGranularity()).isFalse();
        assertThat(query.yearGranularity()).isTrue();

        query.setGranularity("MONTH");
        assertThat(query.dayGranularity()).isFalse();
        assertThat(query.yearGranularity()).isFalse();
    }
}
