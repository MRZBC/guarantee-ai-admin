package com.guarantee.analysis.mapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据概览「分布图」维度名称保留口径的守卫测试。
 *
 * <p><b>防的是什么</b>：险种分布 / 机构分布是 {@code LEFT JOIN} 维度表补齐名称，
 * {@code COUNT(*)} 统计的是订单子查询——维度表的条件只影响"能不能取到名字"。
 * 原先 join 上写着 {@code AND t.status = 1 AND t.is_deleted = 0}，于是被停用/删除的主数据
 * 在榜单里变成**一行没有名字的数据**（现场实测：{@code insuranceTypeId=1}「投标保函（标准）」
 * 名下有 44064 条投标订单、占投标单量 44%，{@code typeName} 却是空字符串），
 * 前端 {@code Overview.vue} 直接把它当 ECharts 的 {@code name}，图表上就是一块无标签的扇区。</p>
 *
 * <p>与订单列表同口径：历史订单的名称不因主数据停用/删除而消失，
 * 见 {@code docs/DEC-订单筛选下拉的选项口径.md}。</p>
 */
class DistributionDimensionNameTest {

    private static final String MAPPER = "/mapper/analysis/OrderAnalysisMapper.xml";

    @Test
    @DisplayName("险种 / 机构分布 join 不带 status、is_deleted：停用或已删除的主数据仍能取到名称")
    void distributionJoinsDoNotFilterDimension() throws Exception {
        String xml = read();

        assertThat(selectBlock(xml, "selectInsuranceDistribution"))
                .as("险种分布的 join 不得带 status / is_deleted，否则最大占比的那一条会没有名字")
                .contains("LEFT JOIN insurance_type t ON t.id = o.insurance_type_id")
                .doesNotContain("t.status = 1")
                .doesNotContain("t.is_deleted");
        assertThat(selectBlock(xml, "selectInstitutionDistribution"))
                .as("机构分布的 join 同理")
                .contains("LEFT JOIN sys_org g ON g.id = o.org_id")
                .doesNotContain("g.status = 1")
                .doesNotContain("g.is_deleted");
    }

    @Test
    @DisplayName("订单子查询仍显式过滤已删除订单（也是拦截器整句跳过的前提）")
    void orderSourceStillFiltersDeletedOrders() throws Exception {
        String xml = read();

        assertThat(xml)
                .as("订单源必须自己过滤 is_deleted：join 去掉条件后不能靠拦截器兜底")
                .contains("AND is_deleted = 0");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private static String read() throws IOException {
        try (InputStream in = DistributionDimensionNameTest.class.getResourceAsStream(MAPPER)) {
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
