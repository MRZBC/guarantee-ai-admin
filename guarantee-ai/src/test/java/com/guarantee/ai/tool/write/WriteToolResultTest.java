package com.guarantee.ai.tool.write;

import com.guarantee.ai.service.ProposalPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 写工具必须把确认卡上的「原值 → 新值」一并返回给模型。
 *
 * <p>真机事故（2026-09-25 00:16）：模型被告知"角色分配"，返回里只有
 * {@code summary="角色分配：user0005"}——**没有目标角色的中文名**；于是它在正文里写了自己记忆里的
 * 「行政」（上一轮编造的改名），而同屏确认卡写的是「业务运营（无系统配置）」，用户当场发现
 * 正文与卡片不一致并拒绝了提案。把 {@code changes} 返回给模型后，正文才有可逐字抄写的真值。</p>
 */
class WriteToolResultTest {

    @Test
    @DisplayName("返回里带上卡片同源的 changes（模型正文照抄的唯一真值）")
    void carriesPreviewChanges() {
        ProposalPayload payload = new ProposalPayload(
                726L, "OP202609250016423869", 484L,
                "proposeUserChange", "ASSIGN_ROLES", "角色分配",
                "USER", "用户", 5L, "user0005",
                "角色分配：user0005",
                List.of(new ProposalPayload.ChangeItem(
                        "roleCodes", "角色", "只读用户", "业务运营（无系统配置）")),
                List.of("影响面：当前角色 只读用户"),
                List.of("角色分配会立即改变该用户的权限"),
                true, "把张涛的角色改成无系统配置的权限",
                LocalDateTime.now().plusMinutes(15), "PENDING");

        BaseProposalTool.WriteToolResult result = BaseProposalTool.WriteToolResult.ok(payload);

        assertThat(result.changes())
                .as("没有 changes，模型就只能凭记忆写实体名——那正是「正文与卡片不一致」的成因")
                .hasSize(1);
        assertThat(result.changes().get(0).after()).isEqualTo("业务运营（无系统配置）");
        assertThat(result.changes().get(0).before()).isEqualTo("只读用户");
    }

    @Test
    @DisplayName("失败/歧义/拒绝分支的 changes 为空，不得为 null（模型侧要能安全解析）")
    void nonOkBranchesHaveEmptyChanges() {
        assertThat(BaseProposalTool.WriteToolResult.failed("目标不明确").changes()).isEmpty();
        assertThat(BaseProposalTool.WriteToolResult.denied("无权限").changes()).isEmpty();
        assertThat(BaseProposalTool.WriteToolResult.ambiguous(List.of(), "多个候选").changes()).isEmpty();
    }
}
