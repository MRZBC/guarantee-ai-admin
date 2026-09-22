package com.guarantee.ai.service;

import com.guarantee.ai.entity.AiOperationProposal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「声称有提案但没有提案」兜底校验的单元测试（SYS-Q-06b）。
 *
 * <p><b>两条必须同时成立的断言</b>：</p>
 * <ol>
 *   <li>真的编造（声称已生成/待确认，而会话内没有 PENDING）→ 必须追加纠正；</li>
 *   <li>真有 PENDING（写工具确实生成了卡片）→ **绝不能**误伤，
 *       否则用户会看到"系统说没有提案"而卡片就在眼前，比不纠还糟。</li>
 * </ol>
 *
 * <p>另外用真机出现过的原文做回归，确保匹配规则认得出实际形态。</p>
 */
class ProposalClaimGuardTest {

    /** 真机复现的原文（编号是照抄上一轮真实提案改了尾数，库里不存在）。 */
    private static final String FABRICATED = """
            待确认提案：提案编号 OP202609231200258712，目标为险种「投标保函（标准）」。
            请在确认卡上点击「确认执行」后生效。""";

    private ProposalService proposalService;
    private ProposalClaimGuard guard;

    @BeforeEach
    void setUp() {
        proposalService = mock(ProposalService.class);
        guard = new ProposalClaimGuard(proposalService);
    }

    private void pendingExists(boolean exists) {
        when(proposalService.listPendingInConversation(any(), any(), anyInt()))
                .thenReturn(exists ? List.of(new AiOperationProposal()) : List.of());
    }

    // ==================================================================
    // 一：声称有提案 + 没有 PENDING → 追加纠正
    // ==================================================================

    @Test
    @DisplayName("真机形态的编造：正文写「待确认提案…请在确认卡上点击」而会话内无 PENDING → 追加纠正")
    void fabricatedClaimShouldBeCorrected() {
        pendingExists(false);

        Optional<String> correction = guard.correctionFor(7L, 99L, FABRICATED);

        assertThat(correction).isPresent();
        assertThat(correction.get()).isEqualTo(ProposalClaimGuard.CORRECTION);
        assertThat(correction.get()).as("纠正必须如实且可操作").contains("并未生成").contains("重新说明");
        verify(proposalService).listPendingInConversation(7L, 99L, 1);
    }

    @Test
    @DisplayName("「我已生成变更提案」「请到确认卡上点击」等话术在没有 PENDING 时同样被纠正")
    void otherClaimPhrasingsShouldBeCorrected() {
        pendingExists(false);

        assertThat(guard.correctionFor(1L, 2L, "我已生成变更提案，需要在确认卡上点击『确认执行』后才会生效。"))
                .isPresent();
        assertThat(guard.correctionFor(1L, 2L, "变更提案已生成，请到确认卡上点击确认。"))
                .isPresent();
        assertThat(guard.correctionFor(1L, 2L, "请在确认卡上点击确认执行。"))
                .as("指向一张不存在的卡片本身就是需要纠正的失败形态")
                .isPresent();
        assertThat(guard.correctionFor(1L, 2L, "我生成了了一张新的待确认提案，编号 OP202609231200000001。"))
                .isPresent();
    }

    @Test
    @DisplayName("多句正文按句判定：一句如实、一句编造，仍然要纠正")
    void perSentenceEvaluationShouldStillCatchTheFabricatedSentence() {
        pendingExists(false);

        Optional<String> correction = guard.correctionFor(7L, 99L,
                "我没有查询到相关数据。\n我已生成变更提案，请在确认卡上点击确认执行。");

        assertThat(correction).isPresent();
    }

    // ==================================================================
    // 二：确实有 PENDING → 不误伤
    // ==================================================================

    @Test
    @DisplayName("写工具确实生成了卡片（会话内有 PENDING）→ 不追加任何纠正")
    void realPendingProposalShouldNotBeCorrected() {
        pendingExists(true);

        Optional<String> correction = guard.correctionFor(7L, 99L, FABRICATED);

        assertThat(correction).as("确有 PENDING 时误伤会让用户以为卡片是假的").isEmpty();
    }

    // ==================================================================
    // 三：如实回答 / 无关正文不得被误伤
    // ==================================================================

    @Test
    @DisplayName("如实回答「当前没有待确认提案」不触发纠正，且根本不必查库")
    void truthfulNegativeShouldNotBeCorrected() {
        Optional<String> correction = guard.correctionFor(7L, 99L,
                "当前没有待确认的变更提案，也没有可点击的确认卡。");

        assertThat(correction).isEmpty();
        verify(proposalService, never()).listPendingInConversation(any(), any(), anyInt());
    }

    @Test
    @DisplayName("权限不足 / 工具失败时的如实说明不触发纠正")
    void honestFailureExplanationShouldNotBeCorrected() {
        assertThat(guard.correctionFor(1L, 2L, "你当前没有 ai:system:write 权限，无法生成提案，请联系管理员。"))
                .isEmpty();
        assertThat(guard.correctionFor(1L, 2L, "写工具返回失败，未生成任何提案：目标名不明确。"))
                .isEmpty();
        assertThat(guard.correctionFor(1L, 2L, "该编号对应的提案不存在，请重新发起。"))
                .isEmpty();
        assertThat(guard.correctionFor(1L, 2L, "提案生成失败：ambiguous，请从以下候选中选择。"))
                .isEmpty();
    }

    @Test
    @DisplayName("普通业务回答（无提案话术）不触发纠正，也不查库")
    void unrelatedAnswerShouldNotBeCorrected() {
        Optional<String> correction = guard.correctionFor(7L, 99L,
                "2026 年第三季度投标订单共 1,234 笔，保函金额合计 5,678.90 万元。"
                        + "数据来源：queryOrderSummary(orderType=TENDER, 2026-07-01 ~ 2026-09-30)。");

        assertThat(correction).isEmpty();
        verify(proposalService, never()).listPendingInConversation(any(), any(), anyInt());
    }

    @Test
    @DisplayName("空正文 / null 正文不触发纠正")
    void blankAnswerShouldNotBeCorrected() {
        assertThat(guard.correctionFor(1L, 2L, null)).isEmpty();
        assertThat(guard.correctionFor(1L, 2L, "   \n  ")).isEmpty();
        assertThat(guard.correctionFor(1L, 2L, "")).isEmpty();
    }

    // ==================================================================
    // 四：兜底校验自身失败不得影响正常回复
    // ==================================================================

    @Test
    @DisplayName("查库异常时跳过纠正而不是把异常抛给用户（补救措施不能变成新故障）")
    void queryFailureShouldSkipCorrection() {
        when(proposalService.listPendingInConversation(any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));

        assertThat(guard.correctionFor(7L, 99L, FABRICATED)).isEmpty();
    }

    // ==================================================================
    // 五：纯文本判定规则
    // ==================================================================

    @Test
    @DisplayName("claimsProposal 认得出编造、放得过如实回答")
    void claimsProposalRule() {
        assertThat(ProposalClaimGuard.claimsProposal(FABRICATED)).isTrue();
        assertThat(ProposalClaimGuard.claimsProposal("已生成变更提案，请在确认卡上点击确认执行。")).isTrue();
        assertThat(ProposalClaimGuard.claimsProposal("当前没有待确认的变更提案。")).isFalse();
        assertThat(ProposalClaimGuard.claimsProposal("未生成提案。")).isFalse();
        assertThat(ProposalClaimGuard.claimsProposal("报告已生成，请查收。"))
                .as("没有「提案」二字的话术不得命中（例如「报告已生成」）")
                .isFalse();
        assertThat(ProposalClaimGuard.claimsProposal("如果你确认，我就会生成变更提案。"))
                .as("尚未发生的将来时表述不得命中")
                .isFalse();
    }
}
