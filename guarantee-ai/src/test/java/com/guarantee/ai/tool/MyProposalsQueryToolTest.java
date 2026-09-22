package com.guarantee.ai.tool;

import com.guarantee.ai.entity.AiOperationProposal;
import com.guarantee.ai.service.ProposalService;
import com.guarantee.common.security.Permissions;
import com.guarantee.common.security.Roles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code queryMyProposals} 只读工具单元测试（SYS-Q-06b）。
 *
 * <p><b>它守的是什么</b>：真机复现的失败是"助手正文写了一个库里不存在的提案编号"。
 * 修法是给模型一个真实数据源，因此本测试的第一要务是证明工具
 * <b>只返回服务端查询到的内容</b>、范围被强制收敛、0 条就是 0 条
 * （不伪装、不越权、不接受任何用户维度入参）。</p>
 */
class MyProposalsQueryToolTest {

    private ProposalService proposalService;
    private MyProposalsQueryTool tool;

    @BeforeEach
    void setUp() {
        proposalService = mock(ProposalService.class);
        tool = new MyProposalsQueryTool(proposalService);
    }

    private static ToolContext context(List<String> permissions, Long userId, Long conversationId) {
        Map<String, Object> map = new HashMap<>();
        map.put(AiToolContextKeys.PERMISSIONS, permissions);
        map.put(AiToolContextKeys.ROLES, List.of(Roles.ADMIN));
        if (userId != null) {
            map.put(AiToolContextKeys.USER_ID, userId);
        }
        if (conversationId != null) {
            map.put(AiToolContextKeys.CONVERSATION_ID, conversationId);
        }
        return new ToolContext(map);
    }

    private static ToolContext adminContext() {
        return context(List.of(Permissions.AI_CHAT, Permissions.AI_SYSTEM_QUERY), 7L, 99L);
    }

    private static AiOperationProposal proposal(String no, String action, String targetType,
                                                String targetName, LocalDateTime expiresAt) {
        AiOperationProposal entity = new AiOperationProposal();
        entity.setId(123L);
        entity.setProposalNo(no);
        entity.setAction(action);
        entity.setTargetType(targetType);
        entity.setTargetName(targetName);
        entity.setStatus("PENDING");
        entity.setExpiresAt(expiresAt);
        entity.setToolName("proposeInsuranceTypeChange");
        return entity;
    }

    // ==================================================================
    // 正常返回：字段齐备、可直接被正文引用
    // ==================================================================

    @Test
    @DisplayName("返回待确认提案的编号/动作/目标/有效期，且 dataSource 可被正文回显")
    void shouldReturnPendingProposalFields() {
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(10);
        when(proposalService.listPendingInConversation(eq(7L), eq(99L), anyInt()))
                .thenReturn(List.of(proposal("OP2026092312001234", "DISABLE", "INSURANCE_TYPE",
                        "投标保函（标准）", expiresAt)));

        MyProposalsToolResult result = tool.queryMyProposals(adminContext());

        assertThat(result.meta().denied()).isFalse();
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.conversationScoped()).isTrue();
        MyProposalsToolResult.MyProposalItem item = result.items().get(0);
        assertThat(item.proposalId()).isEqualTo(123L);
        assertThat(item.proposalNo()).as("编号必须原样返回，模型只许照抄，不许改写尾数")
                .isEqualTo("OP2026092312001234");
        assertThat(item.action()).isEqualTo("DISABLE");
        assertThat(item.actionName()).isEqualTo("停用");
        assertThat(item.targetType()).isEqualTo("INSURANCE_TYPE");
        assertThat(item.targetTypeName()).isEqualTo("险种");
        assertThat(item.targetName()).isEqualTo("投标保函（标准）");
        assertThat(item.status()).isEqualTo("PENDING");
        assertThat(item.expiresAt()).isEqualTo(expiresAt.toString());
        assertThat(item.expired()).isFalse();
        assertThat(item.toolName()).isEqualTo("proposeInsuranceTypeChange");
        assertThat(result.meta().dataSource()).contains("queryMyProposals").contains("PENDING");
    }

    @Test
    @DisplayName("0 条就是 0 条：denied=false，total=0（SYS-Q-11，不得伪装成有数据）")
    void emptyResultIsNotDenied() {
        when(proposalService.listPendingInConversation(any(), any(), anyInt())).thenReturn(List.of());

        MyProposalsToolResult result = tool.queryMyProposals(adminContext());

        assertThat(result.total()).isZero();
        assertThat(result.items()).isEmpty();
        assertThat(result.meta().denied()).as("无数据与无权限必须可区分").isFalse();
        assertThat(result.meta().dataSource()).isNotNull();
    }

    @Test
    @DisplayName("已过期但仍为 PENDING 的提案照实返回，并标记 expired=true")
    void expiredPendingProposalShouldBeFlagged() {
        when(proposalService.listPendingInConversation(any(), any(), anyInt()))
                .thenReturn(List.of(proposal("OP2026092312009999", "DISABLE", "ORG",
                        "广东省第2保函运营机构", LocalDateTime.now().minusMinutes(1))));

        MyProposalsToolResult result = tool.queryMyProposals(adminContext());

        assertThat(result.items()).singleElement()
                .satisfies(item -> assertThat(item.expired())
                        .as("过期后确认会被拒绝，必须让模型能如实说明而不是引导用户去点卡片")
                        .isTrue());
    }

    // ==================================================================
    // 范围强制收敛：不接受任何用户维度入参
    // ==================================================================

    @Test
    @DisplayName("范围来自 ToolContext（用户 + 会话），工具没有任何入参可以被模型放大范围")
    void scopeComesFromToolContextOnly() {
        when(proposalService.listPendingInConversation(any(), any(), anyInt())).thenReturn(List.of());

        tool.queryMyProposals(adminContext());

        verify(proposalService).listPendingInConversation(eq(7L), eq(99L), anyInt());
        // 工具方法只有 ToolContext 一个参数：不存在"查哪个用户/哪个会话"的入参
        assertThat(java.util.Arrays.stream(MyProposalsQueryTool.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("queryMyProposals"))
                .findFirst().orElseThrow().getParameterTypes())
                .as("只读工具不得暴露可被模型利用的用户/会话入参")
                .containsExactly(ToolContext.class);
    }

    @Test
    @DisplayName("会话号缺失时退化为该用户全部待确认提案，并如实标注未按会话收敛")
    void missingConversationDegradesToUserScope() {
        when(proposalService.listPendingInConversation(any(), any(), anyInt())).thenReturn(List.of());

        MyProposalsToolResult result = tool.queryMyProposals(
                context(List.of(Permissions.AI_CHAT, Permissions.AI_SYSTEM_QUERY), 7L, null));

        assertThat(result.conversationScoped()).isFalse();
        verify(proposalService).listPendingInConversation(eq(7L), isNull(), anyInt());
    }

    // ==================================================================
    // 权限：复用 ai:system:query，不新增权限码
    // ==================================================================

    @Test
    @DisplayName("无 ai:system:query 时 denied=true 且不查库（SYS-Q-11：不得把无权限伪装成 0 条）")
    void withoutPermissionShouldBeDeniedAndNotQuery() {
        MyProposalsToolResult result = tool.queryMyProposals(
                context(List.of(Permissions.AI_CHAT, Permissions.ORG_VIEW), 7L, 99L));

        assertThat(result.meta().denied()).isTrue();
        assertThat(result.meta().deniedReason()).contains(Permissions.AI_SYSTEM_QUERY);
        assertThat(result.items()).isEmpty();
        assertThat(result.total()).isZero();
        verify(proposalService, never()).listPendingInConversation(any(), any(), anyInt());
    }

    @Test
    @DisplayName("权限快照为空（fail-closed）时同样拒绝")
    void emptyPermissionSnapshotShouldBeDenied() {
        MyProposalsToolResult result = tool.queryMyProposals(context(List.of(), 7L, 99L));
        assertThat(result.meta().denied()).isTrue();
    }

    @Test
    @DisplayName("拿不到当前用户时返回可读的拒绝原因，而不是抛异常")
    void missingUserIdShouldBeDeniedWithReason() {
        MyProposalsToolResult result = tool.queryMyProposals(
                context(List.of(Permissions.AI_CHAT, Permissions.AI_SYSTEM_QUERY), null, 99L));

        assertThat(result.meta().denied()).isTrue();
        assertThat(result.meta().deniedReason()).contains("重新登录");
        verify(proposalService, never()).listPendingInConversation(any(), any(), anyInt());
    }
}
