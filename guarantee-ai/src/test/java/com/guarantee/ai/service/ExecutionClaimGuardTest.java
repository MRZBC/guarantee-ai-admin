package com.guarantee.ai.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ExecutionClaimGuardTest {
    @Test
    void replaysActualAcceptanceAnswers() throws Exception {
        for (int id : new int[]{4898, 4902, 4905}) {
            try (var in = getClass().getResourceAsStream("/acceptance/edge-" + id + ".txt")) {
                String answer = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertThat(ExecutionClaimGuard.correctionFor(answer, true, false,
                        java.util.Set.of("Edge验收已改名", "Edge验收临时角色", "Edge验收提案目标")))
                        .as("真实回复 %s", id).isEmpty();
            }
        }
    }
    @Test
    void preservesRulesAndKnownNamesWithoutHidingCompletion() {
        assertThat(ExecutionClaimGuard.correctionFor("一个对象可以既已删除、又已停用。已删除的记录不会出现在统计里。", true, false)).isEmpty();
        assertThat(ExecutionClaimGuard.correctionFor("已删除该角色，可以继续了。", false, false)).isPresent();
        var names = java.util.Set.of("Edge验收已改名");
        assertThat(ExecutionClaimGuard.correctionFor("目标角色：Edge验收已改名（ID 1305）", true, false, names)).isEmpty();
        assertThat(ExecutionClaimGuard.correctionFor("新值：‘Edge验收已改名’，请确认后执行。", true, false, names)).isEmpty();
        assertThat(ExecutionClaimGuard.correctionFor("角色‘Edge验收已改名’已修改成功。", true, false, names)).isPresent();
        assertThat(ExecutionClaimGuard.correctionFor("尚未修改 A，但已删除 B。", false, true)).isPresent();
        assertThat(ExecutionClaimGuard.correctionFor("尚未修改 A，已删除 B。", false, true)).isPresent();
        assertThat(ExecutionClaimGuard.correctionFor("已删除该角色。", false, false,
                java.util.Set.of("已删除"))).isPresent();
        assertThat(ExecutionClaimGuard.correctionFor("他说：‘已成功删除该角色’。", false, false)).isPresent();
    }
    @Test
    void queryAndProposalAreNotExecutionEvidence() {
        assertThat(ExecutionClaimGuard.correctionFor("查询显示该用户当前已停用。", true, false)).isEmpty();
        assertThat(ExecutionClaimGuard.correctionFor("已成功停用该用户。", true, false)).isPresent();
        assertThat(ExecutionClaimGuard.correctionFor("变更已生效。", false, true)).isPresent();
        assertThat(ExecutionClaimGuard.correctionFor("已生成提案，请确认后执行。", false, false)).isEmpty();
        assertThat(ExecutionClaimGuard.correctionFor("变更已生效。", false, false)).isPresent();
    }
    @Test
    void correctsUnsupportedCompletionWithoutProposalNumber() {
        for (String text : new String[]{"已成功停用该险种，变更已生效。", "操作已完成。", "已修改名称。"}) {
            assertThat(ExecutionClaimGuard.correctionFor(text)).as(text).isPresent();
        }
    }

    @Test
    void preservesFailureAndFutureInstructions() {
        for (String text : new String[]{"尚未执行，请确认。", "执行失败，未成功停用。", "确认后才会生效。", "例如修改成功时会显示结果。"}) {
            assertThat(ExecutionClaimGuard.correctionFor(text)).as(text).isEmpty();
        }
    }

    @Test
    void codeSetOrderDoesNotChangeFingerprint() {
        assertThat(ProposalExecutor.canonicalCodes(java.util.List.of("b", "a", "a")))
                .containsExactly("a", "b");
    }
}
