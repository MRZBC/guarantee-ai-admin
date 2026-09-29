package com.guarantee.ai.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本轮工具事实的提取与合并。
 *
 * <p>这是"口径由服务端产出、编号按白名单校验"的数据源，因此必须钉住三件事：
 * ① 真实工具返回值里的 {@code dataSource} / {@code proposalNo} 一个不漏；
 * ② 工具返回值被截断或不是 JSON 时**降级为空**而不是抛异常（绝不能因为采集失败让查询报错）；
 * ③ 去重且保持执行顺序（口径页脚的顺序就是工具执行顺序）。</p>
 */
class TurnFactsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("meta.dataSource（嵌套）被提取——多数查询工具是这种形态")
    void extractsNestedDataSource() {
        String json = """
                {"total":2,"items":[{"id":1,"orgName":"浙江省分公司"}],
                 "meta":{"denied":false,"deniedReason":null,"truncated":false,"truncatedHint":null,
                         "dataSource":"机构配置 · 关键词：浙江 · 状态：启用"}}""";

        TurnFacts.Facts facts = TurnFacts.extract(json, mapper);

        assertThat(facts.dataSources()).containsExactly("机构配置 · 关键词：浙江 · 状态：启用");
        assertThat(facts.proposalNumbers()).isEmpty();
    }

    @Test
    @DisplayName("顶层 dataSource 被提取——queryOrderSummary 是这种形态（没有 meta）")
    void extractsTopLevelDataSource() {
        String json = """
                {"orderType":"TENDER","startDate":"2026-07-01","endDate":"2026-09-30",
                 "orderCount":12,"guaranteeAmount":1.5,"premiumAmount":0.02,
                 "enterpriseCount":3,"projectCount":4,
                 "dataSource":"订单统计 · 险种：投标保函 · 时间区间：2026-07-01 ~ 2026-09-30"}""";

        assertThat(TurnFacts.extract(json, mapper).dataSources())
                .containsExactly("订单统计 · 险种：投标保函 · 时间区间：2026-07-01 ~ 2026-09-30");
    }

    @Test
    @DisplayName("写工具返回值里的 proposalNo 与 meta.dataSource 都被采集")
    void extractsWriteToolFacts() {
        String json = """
                {"denied":false,"deniedReason":null,"proposalId":42,
                 "proposalNo":"OP202609292345001234",
                 "summary":"停用险种「投标保函（标准）」",
                 "expiresAt":"2026-09-30T00:00:00",
                 "ambiguousTargets":[],"changes":[{"label":"状态","before":"启用","after":"停用"}],
                 "hint":"提案已生成，但尚未生效",
                 "meta":{"denied":false,"truncated":false,"dataSource":"变更提案 · 停用险种「投标保函（标准）」"}}""";

        TurnFacts.Facts facts = TurnFacts.extract(json, mapper);

        assertThat(facts.proposalNumbers()).containsExactly("OP202609292345001234");
        assertThat(facts.dataSources()).containsExactly("变更提案 · 停用险种「投标保函（标准）」");
    }

    @Test
    @DisplayName("编号出现在自由文本里也要采集（例如 items[].proposalNo 之外的 summary / message）")
    void extractsProposalNoFromFreeText() {
        String json = """
                {"total":1,"items":[],"hint":"刚才那个提案 OP202609292345001234 仍在等待确认"}""";

        assertThat(TurnFacts.extract(json, mapper).proposalNumbers())
                .containsExactly("OP202609292345001234");
    }

    @Test
    @DisplayName("编号带中文标点也能识别（\\b 边界不吃 CJK）")
    void extractsProposalNoNextToChinesePunctuation() {
        assertThat(ProposalNoFormat.findAll("待确认提案：提案编号 OP202609292345001234，请点击。"))
                .containsExactly("OP202609292345001234");
        assertThat(ProposalNoFormat.findAll("OP202609292345001234")).hasSize(1);
        assertThat(ProposalNoFormat.findAll("OP123")).as("少于 8 位数字不算编号").isEmpty();
        assertThat(ProposalNoFormat.findAll("OP2026…258712")).as("省略号形态不算编号").isEmpty();
    }

    @Test
    @DisplayName("同一返回值里的重复 dataSource 只记一次；多次 merge 保持执行顺序")
    void deduplicatesAndKeepsOrder() {
        TurnFacts facts = new TurnFacts();
        facts.merge(TurnFacts.extract("""
                {"dataSource":"订单统计 · 全量"}""", mapper));
        facts.merge(TurnFacts.extract("""
                {"meta":{"dataSource":"机构配置 · 关键词：浙江"}}""", mapper));
        facts.merge(TurnFacts.extract("""
                {"dataSource":"订单统计 · 全量"}""", mapper));

        assertThat(facts.dataSources())
                .as("去重 + 顺序 = 工具执行顺序")
                .containsExactly("订单统计 · 全量", "机构配置 · 关键词：浙江");
    }

    @Test
    @DisplayName("多个工具调用的编号合并成一个集合")
    void mergesProposalNumbers() {
        TurnFacts facts = new TurnFacts();
        facts.merge(TurnFacts.extract("""
                {"proposalNo":"OP202609292345001111"}""", mapper));
        facts.merge(TurnFacts.extract("""
                {"items":[{"proposalNo":"OP202609292345002222"}]}""", mapper));

        assertThat(facts.proposalNumbers())
                .containsExactlyInAnyOrder("OP202609292345001111", "OP202609292345002222");
    }

    @Test
    @DisplayName("被 16KB 上限截断成非法 JSON / 非 JSON 文本：降级为空，不抛异常")
    void degradesOnBrokenJson() {
        assertThat(TurnFacts.extract("{\"dataSource\":\"订单统计 · 全量", mapper).dataSources())
                .as("截断的 JSON 解析失败 → 本轮没有可采信口径").isEmpty();
        assertThat(TurnFacts.extract("不是 JSON 的一段话", mapper).dataSources()).isEmpty();
        assertThat(TurnFacts.extract(null, mapper).dataSources()).isEmpty();
        assertThat(TurnFacts.extract("   ", mapper).dataSources()).isEmpty();
        assertThat(TurnFacts.extract("{}", null).dataSources()).isEmpty();
    }

    @Test
    @DisplayName("空 dataSource 不入账（denied / 无口径的工具不该产生空口径行）")
    void ignoresBlankDataSource() {
        String json = """
                {"meta":{"denied":true,"deniedReason":"你当前没有 system:org:view 权限","dataSource":""}}""";

        TurnFacts.Facts facts = TurnFacts.extract(json, mapper);

        assertThat(facts.dataSources()).isEmpty();
    }

    @Test
    @DisplayName("未执行过任何工具：事实为空（口径页脚与编号白名单都应为空）")
    void emptyWhenNothingRan() {
        TurnFacts facts = new TurnFacts();

        assertThat(facts.dataSources()).isEmpty();
        assertThat(facts.proposalNumbers()).isEqualTo(Set.of());
        assertThat(facts.metricBlocks()).isEmpty();
    }

    @Test
    @DisplayName("指标块随事实一起收集（数值溯源的服务端出口）")
    void collectsMetricBlocks() {
        TurnFacts facts = new TurnFacts();

        facts.merge(TurnFacts.extract("""
                {"orderCount":12,"guaranteeAmount":100.5,"dataSource":"订单统计 · 全量"}""", mapper));
        facts.merge(TurnFacts.extract("""
                {"orderCount":12,"guaranteeAmount":100.5,"dataSource":"订单统计 · 全量"}""", mapper));

        assertThat(facts.metricBlocks())
                .as("同一份数据只渲染一次")
                .hasSize(1);
        assertThat(facts.metricBlocks().get(0)).contains("- 订单量：12 笔");
    }

    @Test
    @DisplayName("没有已登记指标的返回值不产生指标块")
    void noMetricBlockWhenNothingRegistered() {
        assertThat(TurnFacts.extract("""
                {"meta":{"dataSource":"我的待确认提案"}}""", mapper).metricBlocks()).isEmpty();
    }
}
