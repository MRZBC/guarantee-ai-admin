package com.guarantee.ai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「零工具调用却出现口径行」的兜底校验。
 *
 * <p>真实事故（2026-09-24，会话 481）：用户回了一个字母 "B"，模型一次写工具都没调用，
 * 却写了一段"已按你的选择发起提案…"，末尾还带一句自造的
 * {@code 口径：用户配置 · 目标: user0005 张涛 · 变更类型：角色分配 · 数据范围：全量…}
 * ——把上一轮见过的字符串重新拼出来的。本类钉住该场景必须被识别。</p>
 */
class DataSourceClaimGuardTest {

    private final DataSourceClaimGuard guard = new DataSourceClaimGuard();

    @Test
    @DisplayName("零工具调用 + 出现口径行 → 追加纠正")
    void correctsFabricatedDataSourceWhenNoToolRan() {
        String answer = """
                已按你的选择发起提案：把 user0005 张涛的角色调整为「只读用户」。

                口径：用户配置 · 目标: user0005 张涛 · 变更类型：角色分配 · 数据范围：全量（阶段一 O3：机构维度已移除）""";

        assertThat(guard.correctionFor(answer, false)).contains(DataSourceClaimGuard.CORRECTION);
    }

    @Test
    @DisplayName("执行过工具就不再介入（抄错属于引用错误，不在本兜底职责内）")
    void staysSilentWhenAnyToolExecuted() {
        String answer = "本季度共 12 笔订单。\n\n口径：订单统计 · 险种：投标保函";

        assertThat(guard.correctionFor(answer, true)).isEmpty();
    }

    @Test
    @DisplayName("零工具调用但没有口径行 → 不介入（那是 ProposalClaimGuard 的职责）")
    void staysSilentWhenNoDataSourceLine() {
        assertThat(guard.correctionFor("你好，请问需要查询什么？", false)).isEmpty();
    }

    @Test
    @DisplayName("行中出现「口径」不算：避免误伤「我们按同一口径统计」这类正当表述")
    void ignoresDataSourceMentionedMidSentence() {
        assertThat(guard.correctionFor("以上数据我们按同一口径统计，不含已删除记录。", false)).isEmpty();
        assertThat(guard.correctionFor("如果你指的是另一个口径：请说明时间范围。", false)).isEmpty();
    }

    @Test
    @DisplayName("行首判定：允许缩进与半角冒号")
    void detectsIndentedLineAndHalfWidthColon() {
        assertThat(guard.correctionFor("结论如下。\n   口径：订单统计", false)).isPresent();
        assertThat(guard.correctionFor("结论如下。\n口径: 订单统计", false)).isPresent();
    }

    @Test
    @DisplayName("空/空白正文不介入")
    void ignoresBlankAnswer() {
        assertThat(guard.correctionFor(null, false)).isEmpty();
        assertThat(guard.correctionFor("   \n  ", false)).isEmpty();
    }

    // ------------------------------------------------------------------
    // Markdown 装饰变体：与 KnowledgeClaimGuard 对齐（T6-01 / D-A）
    // ------------------------------------------------------------------

    /**
     * 绕过反例：前 6 个来自独立验证（窄式正则对它们既不剥离也不纠正），后 3 个是同类扩展。
     *
     * <p>与知识来源守卫（12 个变体）同源：同一件事的两个面必须用同一套装饰容忍规则。</p>
     */
    private static final List<String> DECORATED_VARIANTS = List.of(
            "**口径：订单统计 · 粗体**",
            "- 口径：订单统计 · 列表",
            "> 口径：订单统计 · 引用",
            "口径 ：订单统计 · 冒号前有空格",
            "**口径**: 订单统计 · 标签后置粗体",
            "`口径：订单统计 · 行内代码`",
            "## 口径：订单统计 · 标题",
            "| 口径：订单统计 · 表格 |",
            "  * 口径: 订单统计 · 嵌套列表半角");

    @Test
    @DisplayName("装饰变体一律识别为「声明口径」（判定同步放宽）")
    void detectsDecoratedDataSourceLines() {
        for (String variant : DECORATED_VARIANTS) {
            String answer = "结论如下。\n" + variant + "\n以上。";

            assertThat(DataSourceClaimGuard.claimsDataSource(answer))
                    .as("必须识别为「声明口径」：%s", variant).isTrue();
            assertThat(guard.correctionFor(answer, false))
                    .as("零工具调用时必须触发纠正：%s", variant).isPresent();
            // 执行过工具不纠正（引用错误 ≠ 编造），但剥离照旧——两条判定用的是同一套规则
            assertThat(guard.correctionFor(answer, true))
                    .as("执行过工具不纠正：%s", variant).isEmpty();
        }
    }

    @Test
    @DisplayName("装饰变体一律被剥离：整行移除且不动正文其余部分")
    void stripsDecoratedDataSourceLines() {
        for (String variant : DECORATED_VARIANTS) {
            String answer = "结论如下。\n" + variant + "\n以上。";

            assertThat(DataSourceClaimGuard.stripDataSourceLines(answer))
                    .as("必须剥离：%s", variant)
                    .doesNotContain("订单统计")
                    .contains("结论如下。")
                    .contains("以上。");
        }
    }

    @Test
    @DisplayName("行首锚点仍在：行中出现的「口径」不判定、不剥离（哪怕带装饰）")
    void decoratedMentionsMidLineAreStillIgnored() {
        List<String> prose = List.of(
                "以上数据我们按同一口径统计，不含已删除记录。",
                "如果你指的是另一个口径：请说明时间范围。",
                "以上数据我们按**口径**统计，不含已删除记录。",
                "- 本节说明的是统计口径，不是数据来源。");
        for (String text : prose) {
            assertThat(DataSourceClaimGuard.claimsDataSource(text))
                    .as("行中提及不得判定为声明：%s", text).isFalse();
            assertThat(DataSourceClaimGuard.stripDataSourceLines(text))
                    .as("行中提及不得剥离：%s", text).isSameAs(text);
            assertThat(guard.correctionFor(text, false))
                    .as("行中提及不得触发纠正：%s", text).isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // 口径的唯一出口：剥离模型自写行 + 服务端生成页脚
    // ------------------------------------------------------------------

    @Test
    @DisplayName("剥离模型自写的口径行：整行移除，不留尾部空行")
    void stripsModelWrittenDataSourceLine() {
        String answer = "本季度共 12 笔订单。\n\n口径：订单统计 · 险种：投标保函";

        assertThat(DataSourceClaimGuard.stripDataSourceLines(answer))
                .isEqualTo("本季度共 12 笔订单。");
    }

    @Test
    @DisplayName("半角冒号、缩进、行中位置：前两者剥离，行中的「口径」不动")
    void stripsIndentedAndHalfWidthLinesOnly() {
        String multiple = "结论：\n  口径：订单统计\n口径: 机构配置\n\n补充说明。";
        assertThat(DataSourceClaimGuard.stripDataSourceLines(multiple))
                .isEqualTo("结论：\n\n补充说明。");

        String prose = "以上数据我们按同一口径统计，不含已删除记录。";
        assertThat(DataSourceClaimGuard.stripDataSourceLines(prose)).isSameAs(prose);
    }

    @Test
    @DisplayName("没有口径行时返回同一个实例（调用方据此判定无需重发正文）")
    void returnsSameInstanceWhenNothingStripped() {
        String answer = "本季度共 12 笔订单，金额 1.50 元。";

        assertThat(DataSourceClaimGuard.stripDataSourceLines(answer)).isSameAs(answer);
        assertThat(DataSourceClaimGuard.stripDataSourceLines(null)).isEmpty();
    }

    @Test
    @DisplayName("服务端页脚：每个真实工具一行、保持执行顺序、跳过空白")
    void buildsServerSideFooter() {
        assertThat(DataSourceClaimGuard.footer(null)).isEmpty();
        assertThat(DataSourceClaimGuard.footer(List.of())).isEmpty();
        assertThat(DataSourceClaimGuard.footer(List.of("  "))).isEmpty();
        assertThat(DataSourceClaimGuard.footer(List.of("订单统计 · 全量")))
                .isEqualTo("\n\n口径：订单统计 · 全量");
        assertThat(DataSourceClaimGuard.footer(
                List.of("订单统计 · 全量", "  ", "机构配置 · 关键词：浙江")))
                .as("顺序即工具执行顺序，空口径不产生空行")
                .isEqualTo("\n\n口径：订单统计 · 全量\n口径：机构配置 · 关键词：浙江");
    }

    @Test
    @DisplayName("端到端形态：模型写的口径行被丢弃，用户看到的是服务端口径")
    void stripThenFooterYieldsServerTruth() {
        String produced = "本季度共 12 笔订单。\n\n口径：订单统计 · 编造的条件";

        String finalText = DataSourceClaimGuard.stripDataSourceLines(produced)
                + DataSourceClaimGuard.footer(List.of("订单统计 · 时间区间：2026-07-01 ~ 2026-09-30"));

        assertThat(finalText)
                .isEqualTo("本季度共 12 笔订单。\n\n口径：订单统计 · 时间区间：2026-07-01 ~ 2026-09-30");
    }
}
