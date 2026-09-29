package com.guarantee.ai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「零工具却给出业务数字」的兜底校验。
 *
 * <p>它补的是 {@link DataSourceClaimGuard} 抓不到的形态：**不写口径行、直接给数字**。
 * 判定同样确定（零工具 → 数字不可能有来源），但必须**窄**——年份、系统上限、能力说明、
 * 有效期都不是业务数字，误伤会把正常回答改坏。</p>
 */
class NumberClaimGuardTest {

    private final NumberClaimGuard guard = new NumberClaimGuard();

    @Test
    @DisplayName("零工具 + 数字带业务量词 → 追加纠正")
    void correctsBusinessNumberWithoutTools() {
        assertThat(guard.correctionFor("本季度共 12 笔订单，担保金额 1.5 亿元。", false))
                .contains(NumberClaimGuard.CORRECTION);
        assertThat(guard.correctionFor("保函金额合计 320 万元。", false))
                .contains(NumberClaimGuard.CORRECTION);
    }

    @Test
    @DisplayName("零工具 + 指标词邻近数字 → 追加纠正（即使没有量词）")
    void correctsLabelWithNumber() {
        assertThat(guard.correctionFor("订单量为 12，环比上升。", false))
                .contains(NumberClaimGuard.CORRECTION);
        assertThat(guard.correctionFor("基准费率是 1.3%，属于最低档。", false))
                .as("费率也是业务数字：零工具时同样没有来源")
                .contains(NumberClaimGuard.CORRECTION);
    }

    @Test
    @DisplayName("执行过工具就不介入（数值对不对属于引用问题，另有服务端摘要兜底）")
    void staysSilentWhenAnyToolExecuted() {
        assertThat(guard.correctionFor("本季度共 12 笔订单。", true)).isEmpty();
        assertThat(guard.correctionFor("订单量为 12。", true)).isEmpty();
    }

    @Test
    @DisplayName("非业务数字不得误伤：年份、系统上限、能力说明、有效期")
    void ignoresNonBusinessNumbers() {
        assertThat(guard.correctionFor("本轮时间范围是 2026 年第三季度（2026-07-01 ~ 2026-09-30）。", false))
                .isEmpty();
        assertThat(guard.correctionFor("单次最多返回 50 条记录，超出会被截断。", false)).isEmpty();
        assertThat(guard.correctionFor("我可以按 3 个维度（区域 / 机构 / 险种）做拆解。", false)).isEmpty();
        assertThat(guard.correctionFor("变更提案的有效期是 15 分钟。", false)).isEmpty();
        assertThat(guard.correctionFor("该角色当前有 4 项权限。", false))
                .as("「项」不在量词白名单里，宁可漏报也不误伤")
                .isEmpty();
    }

    @Test
    @DisplayName("跨句不误伤：指标词与数字分属两句话")
    void doesNotCrossSentenceBoundary() {
        assertThat(guard.correctionFor("订单量是重点。\n共 12 个报表模板。", false)).isEmpty();
    }

    @Test
    @DisplayName("空/空白正文不介入")
    void ignoresBlankAnswer() {
        assertThat(guard.correctionFor(null, false)).isEmpty();
        assertThat(guard.correctionFor("   \n  ", false)).isEmpty();
    }
}
