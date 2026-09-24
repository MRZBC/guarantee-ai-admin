package com.guarantee.order.mapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订单列表「维度名称保留」口径的守卫测试。
 *
 * <p><b>防的是什么</b>：订单列表的维度 JOIN 曾经对四张表统一写 {@code AND x.is_deleted = 0}，
 * 于是主数据被停用/被直连删除后，"筛选下拉里能选到它、筛出来的行那一列却是空的"。
 * 现在险种（{@code it}）与机构（{@code so}）不再按 {@code is_deleted} 过滤，让历史订单
 * 永远显示下单时的名称；项目（{@code p}）与企业（{@code e}）暂无筛选入口，维持原状。</p>
 *
 * <p><b>为什么用文本守卫而不是发 SQL</b>：这条口径的"执行者"是 MyBatis 的 SQL 片段本身，
 * 而且 {@code fromJoin} 同时被分页、计数、详情三条语句引用；用反射/文本断言把
 * "险种与机构不带 is_deleted 条件"钉住，比启动一整套数据源上下文更直接。
 * 真实的端到端行为（已删机构仍显示名称）见 {@code docs/DEC-订单筛选下拉的选项口径.md} 的走查记录。</p>
 *
 * <p><b>为什么 {@code p}/{@code e} 的条件必须留着</b>：逻辑删除拦截器判定"整句已含
 * {@code is_deleted} 条件即跳过注入"（{@code LogicalDeleteSqlRewriter}）。片段里若一个条件都不剩，
 * 拦截器会给 {@code it}/{@code so} 自动注入 {@code is_deleted = 0}，把本口径悄悄抵消掉——
 * 所以这条断言同时也是"拦截器不会来插手"的前提。</p>
 */
class OrderDimensionNamePreservationTest {

    private static final String[] MAPPERS = {
            "TenderOrderMapper.xml", "PerformanceOrderMapper.xml"
    };

    @Test
    @DisplayName("险种与机构维度不带 is_deleted 条件：主数据停用/删除后历史订单仍显示名称")
    void insuranceAndOrgDimensionsKeepHistoricalNames() throws Exception {
        for (String mapper : MAPPERS) {
            String join = fromJoin(read(mapper));

            assertThat(join)
                    .as("%s：险种维度不得按 is_deleted 过滤（已删/停用险种的历史订单仍要显示险种名）", mapper)
                    .doesNotContain("it.is_deleted");
            assertThat(join)
                    .as("%s：机构维度不得按 is_deleted 过滤（已删/停用机构的历史订单仍要显示机构名）", mapper)
                    .doesNotContain("so.is_deleted");
        }
    }

    @Test
    @DisplayName("项目与企业维度维持原状，且片段仍含 is_deleted 条件（拦截器据此整句跳过）")
    void projectAndEnterpriseStayFilteredSoInterceptorSkipsTheStatement() throws Exception {
        for (String mapper : MAPPERS) {
            String xml = read(mapper);
            String join = fromJoin(xml);

            assertThat(join).as("%s：项目维度维持原状", mapper).contains("p.is_deleted = 0");
            assertThat(join).as("%s：企业维度维持原状", mapper).contains("e.is_deleted = 0");
            assertThat(xml)
                    .as("%s：订单主表仍显式过滤已删除订单；该条件也是拦截器整句跳过的前提", mapper)
                    .contains("o.is_deleted = 0");
        }
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private static String read(String mapperFile) throws IOException {
        String resource = "/mapper/order/" + mapperFile;
        try (InputStream in = OrderDimensionNamePreservationTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("mapper XML 必须在 classpath 上：%s", resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 取出 {@code <sql id="fromJoin">} 片段（分页 / 计数 / 详情三条语句共用它）。 */
    private static String fromJoin(String xml) {
        int start = xml.indexOf("<sql id=\"fromJoin\">");
        assertThat(start).as("fromJoin 片段必须存在").isGreaterThanOrEqualTo(0);
        int end = xml.indexOf("</sql>", start);
        assertThat(end).as("fromJoin 片段必须闭合").isGreaterThan(start);
        return xml.substring(start, end);
    }
}
