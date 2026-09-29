package com.guarantee.ai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 提案编号白名单校验。
 *
 * <p>真机事故：正文写「待确认提案：提案编号 OP202609242359135602」，而库里该编号不存在、
 * 当轮也没有写工具调用。原有的 {@link ProposalClaimGuard} 只能发现"会话内一条 PENDING 都没有"
 * 这一种情况——本类补的是**确定性**校验：编号必须来自本轮工具真实返回或用户自己打出的串。</p>
 */
class ProposalNumberGuardTest {

    private static final String REAL_NO = "OP202609292345001234";
    private static final String FAKE_NO = "OP202609242359135602";

    @Test
    @DisplayName("白名单为空 + 正文出现编号 → 编号被移除、置位改写、给出纠正")
    void removesNumberWhenNothingWasReturned() {
        ProposalNumberGuard.Result result = ProposalNumberGuard.sanitize(
                "我已生成变更提案，提案编号 " + FAKE_NO + "，请在确认卡上点击「确认执行」。",
                Set.of());

        assertThat(result.changed()).isTrue();
        assertThat(result.removed()).containsExactly(FAKE_NO);
        assertThat(result.text()).doesNotContain(FAKE_NO);
        assertThat(result.text()).contains("我已生成变更提案");
        assertThat(result.text()).contains("请在确认卡上点击");
    }

    @Test
    @DisplayName("编号来自本轮工具真实返回 → 原样保留（不得误伤真编号）")
    void keepsTrustedNumber() {
        ProposalNumberGuard.Result result = ProposalNumberGuard.sanitize(
                "待确认提案：" + REAL_NO + "，15 分钟内有效。",
                Set.of(REAL_NO));

        assertThat(result.changed()).isFalse();
        assertThat(result.removed()).isEmpty();
        assertThat(result.text()).isEqualTo("待确认提案：" + REAL_NO + "，15 分钟内有效。");
    }

    @Test
    @DisplayName("真编号与假编号同时出现：只移除假的")
    void removesOnlyUntrustedNumbers() {
        ProposalNumberGuard.Result result = ProposalNumberGuard.sanitize(
                "待确认提案：" + REAL_NO + "；另外上一轮的 " + FAKE_NO + " 也存在。",
                Set.of(REAL_NO));

        assertThat(result.changed()).isTrue();
        assertThat(result.removed()).containsExactly(FAKE_NO);
        assertThat(result.text()).contains(REAL_NO).doesNotContain(FAKE_NO);
    }

    @Test
    @DisplayName("多个编造编号：全部移除并计数")
    void removesAllFabricatedNumbers() {
        ProposalNumberGuard.Result result = ProposalNumberGuard.sanitize(
                "提案 " + FAKE_NO + " 与 " + "OP202601010000009999" + " 都已生成。",
                Set.of());

        assertThat(result.removed()).hasSize(2);
        assertThat(result.text()).doesNotContain("OP2026");
    }

    @Test
    @DisplayName("可采信集合 = 工具真实返回 ∪ 用户原话里自己打出的编号")
    void trustedUnionIncludesUserSuppliedNumber() {
        Set<String> trusted = ProposalNumberGuard.trusted(
                "帮我看看 " + FAKE_NO + " 这个提案还在吗", Set.of(REAL_NO));

        assertThat(trusted).containsExactlyInAnyOrder(REAL_NO, FAKE_NO);

        // 用户自己贴的编号，模型如实回显不算编造
        ProposalNumberGuard.Result result = ProposalNumberGuard.sanitize(
                "编号 " + FAKE_NO + " 在系统里不存在。", trusted);
        assertThat(result.changed()).isFalse();
        assertThat(result.text()).contains(FAKE_NO);
    }

    @Test
    @DisplayName("可采信集合对 null 宽容（工具没返回、用户也没提编号）")
    void trustedHandlesNulls() {
        assertThat(ProposalNumberGuard.trusted(null, null)).isEmpty();
        assertThat(ProposalNumberGuard.trusted("你好", null)).isEmpty();
        assertThat(ProposalNumberGuard.trusted(null, Set.of(REAL_NO))).containsExactly(REAL_NO);
    }

    @Test
    @DisplayName("正文没有编号 / 空正文：一律不改写（避免无谓的 reset 重发）")
    void leavesTextWithoutNumbersAlone() {
        String answer = "本季度共 12 笔订单，金额 1.50 元。";

        ProposalNumberGuard.Result result = ProposalNumberGuard.sanitize(answer, Set.of());

        assertThat(result.changed()).isFalse();
        assertThat(result.text()).isSameAs(answer);
        assertThat(ProposalNumberGuard.sanitize(null, Set.of()).changed()).isFalse();
        assertThat(ProposalNumberGuard.sanitize("   ", Set.of()).changed()).isFalse();
    }

    @Test
    @DisplayName("纠正文案不回显假编号（避免用户记住错的那个），并给出下一步")
    void correctionDoesNotEchoFakeNumber() {
        assertThat(ProposalNumberGuard.CORRECTION)
                .doesNotContain("OP")
                .contains("不是本轮工具返回的真实编号")
                .contains("已由系统移除")
                .contains("以确认卡上的编号为准");
    }
}
