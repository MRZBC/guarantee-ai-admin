package com.guarantee.ai.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 指标块的提取与渲染（数值溯源的服务端出口）。
 *
 * <p>两条底线：① 已登记的指标一个不落、单位与小数位写清；② **没有登记的字段绝不猜**
 * （猜错比不显示更糟——例如把费率当成金额）。</p>
 */
class DataMetricsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("汇总类结果：顶层标量全部渲染，金额两位小数、计数带单位")
    void rendersTopLevelScalars() {
        String json = """
                {"orderType":"TENDER","orderCount":12,"guaranteeAmount":1500000.5,
                 "premiumAmount":19500,"enterpriseCount":3,"projectCount":4,
                 "dataSource":"订单统计 · 险种：投标保函 · 时间区间：2026-07-01 ~ 2026-09-30"}""";

        String block = DataMetrics.blockOf(parse(json)).orElseThrow();

        assertThat(block)
                .startsWith("订单统计 · 险种：投标保函 · 时间区间：2026-07-01 ~ 2026-09-30")
                .contains("- 订单量：12 笔")
                .contains("- 担保金额：1500000.50 元")
                .contains("- 保费：19500.00 元")
                .contains("- 企业数：3 家")
                .contains("- 项目数：4 个");
    }

    @Test
    @DisplayName("查询类结果：口径在 meta 里也能取到，记录数按条计")
    void fallsBackToMetaDataSource() {
        String json = """
                {"total":3,"items":[{"id":1}],
                 "meta":{"denied":false,"truncated":false,"dataSource":"机构配置 · 关键词：浙江"}}""";

        String block = DataMetrics.blockOf(parse(json)).orElseThrow();

        assertThat(block).startsWith("机构配置 · 关键词：浙江").contains("- 记录数：3 条");
    }

    @Test
    @DisplayName("维度明细：渲染成 markdown 表，列名随维度变化")
    void rendersDimensionTable() {
        String json = """
                {"dimension":"REGION","orderType":"TENDER","startDate":"2026-07-01","endDate":"2026-09-30",
                 "items":[
                   {"rank":1,"code":"330000","name":"浙江省","orderCount":7,
                    "guaranteeAmount":900000.5,"premiumAmount":11700,"enterpriseCount":3},
                   {"rank":2,"code":"440000","name":"广东省","orderCount":5,
                    "guaranteeAmount":600000,"premiumAmount":7800,"enterpriseCount":null}],
                 "meta":{"dataSource":"订单分布 · 维度：区域 · 险种：投标保函"}}""";

        String block = DataMetrics.blockOf(parse(json)).orElseThrow();

        assertThat(block).startsWith("订单分布 · 维度：区域 · 险种：投标保函");
        assertThat(block).contains("| 排名 | 区域 | 订单量 | 担保金额（元） | 保费（元） | 企业数 |");
        assertThat(block).contains("| 1 | 浙江省 | 7 | 900000.50 | 11700.00 | 3 |");
        assertThat(block).as("空值用「—」占位，不显示 null").contains("| 2 | 广东省 | 5 | 600000.00 | 7800.00 | — |");
    }

    @Test
    @DisplayName("明细超过 20 行：只渲染 20 行并如实说明总数")
    void capsTableRows() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            items.add(Map.of("rank", i, "name", "区域" + i, "orderCount", i));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dimension", "REGION");
        body.put("items", items);
        body.put("meta", Map.of("dataSource", "订单分布 · 维度：区域"));

        String block = DataMetrics.blockOf(mapper.readValue(mapper.writeValueAsString(body), Object.class))
                .orElseThrow();

        assertThat(block).contains("| 20 | 区域20 | 20 |").doesNotContain("| 21 |");
        assertThat(block).contains("（仅显示前 20 条，共 25 条）");
    }

    @Test
    @DisplayName("结果被截断时明确标注：摘要不能让人以为拿到的是全量")
    void marksTruncatedResult() {
        String json = """
                {"total":5,"items":[],
                 "meta":{"truncated":true,"truncatedHint":"超限","dataSource":"用户配置 · 关键词：张"}}""";

        assertThat(DataMetrics.blockOf(parse(json)).orElseThrow())
                .contains("（该结果超出单次返回上限已被截断，以上仅为部分数据）");
    }

    @Test
    @DisplayName("未登记的字段不猜：费率/编码/日期不进摘要；无名数组不渲染表")
    void ignoresUnregisteredFields() {
        String json = """
                {"insuranceTypeCode":"PERF_STD","baseRate":0.013,"status":1,
                 "effectiveDate":"2026-01-01",
                 "items":[{"id":1,"orgCode":"ORG3301","status":1}],
                 "meta":{"dataSource":"险种配置 · 关键词：履约"}}""";

        assertThat(DataMetrics.blockOf(parse(json)))
                .as("没有任何已登记指标时不产出摘要块（宁可不显示，也不给出可能被误读的数字）")
                .isEmpty();
    }

    @Test
    @DisplayName("明细里的数字不会被当成本轮标量（避免与汇总值同名冲突）")
    void nestedNumbersAreNotPromoted() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", List.of(Map.of("rank", 1, "name", "浙江省", "orderCount", 7)));
        body.put("meta", Map.of("dataSource", "订单分布 · 维度：区域"));

        String block = DataMetrics.blockOf(mapper.readValue(mapper.writeValueAsString(body), Object.class))
                .orElseThrow();

        assertThat(block).as("表格里有 7，但不应出现顶层标量行").doesNotContain("- 订单量：7 笔");
    }

    @Test
    @DisplayName("无可渲染内容 / 非对象：返回空")
    void emptyWhenNothingToRender() {
        assertThat(DataMetrics.blockOf(parse("{}"))).isEmpty();
        assertThat(DataMetrics.blockOf(parse("[]"))).isEmpty();
        assertThat(DataMetrics.blockOf(null)).isEmpty();
        assertThat(DataMetrics.blockOf("字符串")).isEmpty();
    }

    @Test
    @DisplayName("renderAll：拼接多块并带固定标题；无内容时返回空串")
    void rendersAll() {
        assertThat(DataMetrics.renderAll(List.of())).isEmpty();
        assertThat(DataMetrics.renderAll(null)).isEmpty();

        String all = DataMetrics.renderAll(List.of("甲口径\n- 订单量：1 笔", "乙口径\n- 记录数：2 条"));

        assertThat(all).startsWith("\n\n" + DataMetrics.HEADER)
                .contains("甲口径").contains("乙口径");
    }

    private Object parse(String json) {
        return mapper.readValue(json, Object.class);
    }
}
