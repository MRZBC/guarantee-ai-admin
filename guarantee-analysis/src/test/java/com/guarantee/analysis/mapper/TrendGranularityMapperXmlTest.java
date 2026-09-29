package com.guarantee.analysis.mapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 趋势 SQL 粒度分支的守卫测试（纯 XML，不需要数据库）。
 *
 * <p><b>防的是什么</b>：数据概览「按日 / 按月 / 按年」点哪个都一样 —— 真因是
 * {@code selectTrend} 直接比**原始**字段 {@code c.granularity == 'day'}，
 * 而前端发的是大写 {@code DAY}。OGNL 比较字符串区分大小写，于是三个粒度全部落到
 * {@code <otherwise>} 的月分支，归一化（{@code normalizedGranularity()}）等于白做。
 * 另外 {@code year} 这一档当年根本不存在。</p>
 *
 * <p>口径与 {@code orderType} 一致：归一化集中在 DTO，Mapper 只调用归一化后的判断方法
 * （{@code c.includeTender()} → {@code normalizedOrderType()}、
 * {@code c.dayGranularity()} → {@code normalizedGranularity()}）。</p>
 */
class TrendGranularityMapperXmlTest {

    private static final String MAPPER = "/mapper/analysis/OrderAnalysisMapper.xml";

    @Test
    @DisplayName("粒度分支走归一化判断，不再直接比较原始字段")
    void trendUsesNormalizedGranularity() throws Exception {
        String trend = selectBlock(read(), "selectTrend");

        assertThat(trend)
                .as("直接比 c.granularity 会区分大小写：前端发 'DAY' 时永远匹配不上，三个粒度都会变成按月")
                .doesNotContain("c.granularity");
        assertThat(trend)
                .as("必须调用 DTO 里先归一化再判断的方法")
                .contains("c.dayGranularity()")
                .contains("c.yearGranularity()");
    }

    @Test
    @DisplayName("日 / 月 / 年三种粒度各有自己的 period 格式")
    void trendHasOneFormatPerGranularity() throws Exception {
        String trend = selectBlock(read(), "selectTrend");

        assertThat(trend).as("日粒度 yyyy-MM-dd").contains("'%Y-%m-%d'");
        assertThat(trend).as("月粒度 yyyy-MM").contains("'%Y-%m'");
        assertThat(trend).as("年粒度 yyyy").contains("'%Y'");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private static String read() throws IOException {
        try (InputStream in = TrendGranularityMapperXmlTest.class.getResourceAsStream(MAPPER)) {
            assertThat(in).as("mapper XML 必须在 classpath 上：%s", MAPPER).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 截出某个 {@code <select id="...">} 的整段 XML。 */
    private static String selectBlock(String xml, String id) {
        String open = "<select id=\"" + id + "\"";
        int start = xml.indexOf(open);
        assertThat(start).as("<select id=\"%s\"> 必须存在", id).isGreaterThanOrEqualTo(0);
        int end = xml.indexOf("</select>", start);
        assertThat(end).as("<select id=\"%s\"> 必须闭合", id).isGreaterThan(start);
        return xml.substring(start, end);
    }
}
