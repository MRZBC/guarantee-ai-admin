package com.guarantee.ai.tool;

import com.guarantee.ai.mapper.AiAuditLogMapper;
import com.guarantee.ai.mapper.AiToolCallMapper;
import com.guarantee.ai.metrics.AiChatMetrics;
import com.guarantee.ai.service.ExecutionClaimGuard;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ToolOutcomeFactsTest {
    @Test
    void collectsActualNamesAndChangesForExecutionGuard() {
        var facts = new TurnFacts();
        recorder.record("proposeRoleChange", ToolKind.WRITE, "{}",
                "{\"changes\":[{\"field\":\"roleName\",\"before\":\"原角色\",\"after\":\"Edge验收已改名\"}]}",
                true, 1, null, new ToolContext(Map.of(AiToolContextKeys.TURN_FACTS, facts)));
        assertThat(facts.literalValues()).contains("原角色", "Edge验收已改名");
        assertThat(ExecutionClaimGuard.correctionFor("新值：Edge验收已改名", false, false, facts.literalValues())).isEmpty();
        assertThat(ExecutionClaimGuard.correctionFor("角色‘Edge验收已改名’已修改成功", false, false, facts.literalValues())).isPresent();
    }
    private final AiToolCallRecorder recorder = new AiToolCallRecorder(mock(AiToolCallMapper.class),
            mock(AiAuditLogMapper.class), new ObjectMapper(), mock(AiChatMetrics.class));

    @Test
    void failedToolAndPendingProposalCannotProveExecution() {
        var failed = new TurnFacts();
        recorder.record("slowQuery", ToolKind.READ, "{}", null, false, 10, "超时",
                new ToolContext(Map.of(AiToolContextKeys.TURN_FACTS, failed)));
        assertThat(failed.toolFailed()).isTrue();
        assertThat(failed.querySucceeded()).isFalse();
        assertThat(ExecutionClaimGuard.correctionFor("变更已生效。", failed.querySucceeded(), failed.toolFailed())).isPresent();

        var pending = new TurnFacts();
        recorder.record("proposeRoleChange", ToolKind.WRITE, "{}", "{\"status\":\"PENDING\"}", true, 1, null,
                new ToolContext(Map.of(AiToolContextKeys.TURN_FACTS, pending)));
        assertThat(pending.querySucceeded()).isFalse();
        assertThat(ExecutionClaimGuard.correctionFor("已成功授权。", pending.querySucceeded(), pending.toolFailed())).isPresent();
    }

    @Test
    void successfulQueryCanDescribeExistingStateButNotNewExecution() {
        var facts = new TurnFacts();
        recorder.record("queryUser", ToolKind.READ, "{}", "{\"items\":[{\"statusName\":\"停用\"}],\"meta\":{\"dataSource\":\"用户查询\"}}", true, 1, null,
                new ToolContext(Map.of(AiToolContextKeys.TURN_FACTS, facts)));
        assertThat(facts.querySucceeded()).isTrue();
        assertThat(ExecutionClaimGuard.correctionFor("查询显示该用户当前已停用。", true, false)).isEmpty();
        assertThat(ExecutionClaimGuard.correctionFor("本次已成功停用。", true, false)).isPresent();
    }
}
