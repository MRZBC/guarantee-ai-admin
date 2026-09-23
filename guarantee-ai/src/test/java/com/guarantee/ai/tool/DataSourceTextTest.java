package com.guarantee.ai.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 业务口径文本的渲染规则（{@code ToolResultMeta.dataSource}）。
 *
 * <p>为什么值得单独测：这个串会被模型**逐字抄进回答正文**给业务用户看（提示词第 39 条），
 * 所以它的排版规则直接等于用户看到的东西。改动它等于改动用户可见文案，
 * 必须有断言钉住。</p>
 */
class DataSourceTextTest {

    @Test
    @DisplayName("只展示实际生效的条件：'不限'/null/false 一律不出现")
    void unappliedConditionsAreOmitted() {
        Map<String, Object> parts = new LinkedHashMap<>();
        parts.put("keyword", "不限");
        parts.put("status", 1);
        parts.put("regionCode", null);
        parts.put("includeDeleted", false);
        parts.put("数据范围", "全量（不受机构限制）");

        String text = DataSourceText.of("机构配置", parts);

        assertThat(text)
                .as("把「参数转储」变成「口径说明」的关键：列一堆『不限』只是噪音")
                .isEqualTo("机构配置 · 状态：启用 · 数据范围：全量（不受机构限制）");
        assertThat(text).doesNotContain("不限", "keyword", "regionCode", "includeDeleted");
    }

    @Test
    @DisplayName("全部条件都未生效时显式写「未加过滤（全量）」")
    void allUnappliedIsStatedExplicitly() {
        Map<String, Object> parts = new LinkedHashMap<>();
        parts.put("keyword", "不限");
        parts.put("status", null);
        parts.put("includeDeleted", false);

        assertThat(DataSourceText.of("用户配置", parts))
                .as("「什么都没过滤」本身就是重要口径，不能显示成空白——"
                        + "否则用户会误以为已经按他的范围过滤过了")
                .isEqualTo("用户配置 · 未加过滤（全量）");
    }

    @Test
    @DisplayName("布尔条件为 true 时只输出标签本身，不写 key=true")
    void trueBooleanRendersAsLabelOnly() {
        Map<String, Object> parts = new LinkedHashMap<>();
        parts.put("includeDeleted", true);
        parts.put("neverLoggedIn", true);

        assertThat(DataSourceText.of("用户配置", parts))
                .isEqualTo("用户配置 · 含已删除 · 从未登录");
    }

    @Test
    @DisplayName("status 的 1/0 译成启用/停用；其它取值原样保留")
    void statusIsTranslated() {
        assertThat(DataSourceText.of("机构配置", DataSourceText.parts("status", 1)))
                .isEqualTo("机构配置 · 状态：启用");
        assertThat(DataSourceText.of("机构配置", DataSourceText.parts("status", 0)))
                .isEqualTo("机构配置 · 状态：停用");
        assertThat(DataSourceText.of("我的待确认提案", DataSourceText.parts("status", "PENDING")))
                .as("不是 1/0 的取值（如提案状态 PENDING）按原样显示，不做猜测性翻译")
                .isEqualTo("我的待确认提案 · 状态：PENDING");
    }

    @Test
    @DisplayName("传输细节（limit）不进入口径")
    void transportKeysAreHidden() {
        Map<String, Object> parts = new LinkedHashMap<>();
        parts.put("limit", 200);
        parts.put("keyword", "江苏");

        assertThat(DataSourceText.of("机构配置", parts))
                .as("条数上限是分页细节；结果被截断另由 truncated 标志表达，不该混进口径")
                .isEqualTo("机构配置 · 关键字：江苏");
    }

    @Test
    @DisplayName("未登记的键按原样输出，绝不静默丢弃条件")
    void unknownKeysFallBackToRawName() {
        Map<String, Object> parts = new LinkedHashMap<>();
        parts.put("someNewParam", "x");

        assertThat(DataSourceText.of("机构配置", parts))
                .as("宁可偶尔露出一个英文键，也不能吞掉一个查询条件——"
                        + "那会让回显的口径与实际执行的查询不一致，比不好看严重得多")
                .isEqualTo("机构配置 · someNewParam：x");
    }

    @Test
    @DisplayName("真实场景：截图里那句 queryOrderSummary(orderType=TENDER, ...) 的替代形态")
    void orderSummaryShape() {
        Map<String, Object> parts = new LinkedHashMap<>();
        parts.put("orderType", "投标保函");
        parts.put("时间区间", "2026-01-01 ~ 2026-12-31");
        parts.put("region", "不限");
        parts.put("orgId", null);

        assertThat(DataSourceText.of("订单统计", parts))
                .isEqualTo("订单统计 · 险种：投标保函 · 时间区间：2026-01-01 ~ 2026-12-31")
                .doesNotContain("queryOrderSummary", "orderType=", "startDate");
    }

    @Test
    @DisplayName("中文键原样保留（工具里既有英文参数名，也有中文标签）")
    void chineseKeysPassThrough() {
        assertThat(DataSourceText.of("我的工具调用记录",
                DataSourceText.parts("数据范围", "仅本人", "字段集", "仅账号/姓名")))
                .isEqualTo("我的工具调用记录 · 数据范围：仅本人 · 字段集：仅账号/姓名");
    }

    @Test
    @DisplayName("parts 辅助构造：键值必须成对")
    void partsHelperRejectsOddArguments() {
        assertThatThrownBy(() -> DataSourceText.parts("a", 1, "b"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("空 parts 不抛异常")
    void handlesNullAndEmptyParts() {
        assertThat(DataSourceText.of("机构配置", null)).isEqualTo("机构配置 · 未加过滤（全量）");
        assertThat(DataSourceText.of("机构配置", Map.of())).isEqualTo("机构配置 · 未加过滤（全量）");
    }
}
