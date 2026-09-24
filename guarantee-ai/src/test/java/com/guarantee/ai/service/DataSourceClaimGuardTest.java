package com.guarantee.ai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
