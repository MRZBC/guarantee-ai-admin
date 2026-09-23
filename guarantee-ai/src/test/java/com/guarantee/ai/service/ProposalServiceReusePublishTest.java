package com.guarantee.ai.service;

import com.guarantee.ai.entity.AiOperationProposal;
import com.guarantee.ai.mapper.AiOperationProposalMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 复用既有待确认提案时，必须照样推送确认卡事件。
 *
 * <p><b>真机故障</b>：用户第二次说「再把它改回数据分析师」，写工具命中了
 * "同会话同目标同动作已有 PENDING 提案"的复用分支——那个分支原先**只返回载荷、不推事件**，
 * 于是卡片不出现，而模型正文照旧写「请在确认卡上点击「确认执行」」，
 * 用户看到的就是"说生成了提案、却没有卡片"。</p>
 *
 * <p>原先的假设是"前端那张卡还挂着"，但卡不在屏上的情形很常见：刷新过页面、
 * 切过会话、前端刚做过一次待确认列表刷新，或者在另一台设备/标签页发起。</p>
 */
@ExtendWith(MockitoExtension.class)
class ProposalServiceReusePublishTest {

    private static final Long CONVERSATION_ID = 1L;

    @Mock
    private AiOperationProposalMapper proposalMapper;
    @Mock
    private ProposalSecretStore secretStore;
    @Mock
    private OperationAuditService auditService;
    @Mock
    private AiConversationService conversationService;
    @Mock
    private ProposalEventPublisher eventPublisher;
    @Mock
    private ProposalFailureRecorder failedRecorder;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private ObjectProvider<List<ProposalExecutor>> executorsProvider;

    private ProposalService service() {
        return new ProposalService(proposalMapper, secretStore, auditService, conversationService,
                eventPublisher, failedRecorder, objectMapper, executorsProvider);
    }

    private static ProposalService.ProposalDraft draft() {
        // 新建分支会读 request().id() 与 request().extra()，复用分支也要用它算载荷，
        // 因此两种分支都需要一个非空的 request
        ProposalRequest request = ProposalRequest.builder()
                .id(2L)
                .targetName("user0292")
                .userText("再把它改回数据分析师吧")
                .build();
        return new ProposalService.ProposalDraft(CONVERSATION_ID, 7L, "admin", "超级管理员",
                "proposeUserChange", "ASSIGN_ROLES", "USER", 2L, "user0292",
                request, null, Set.of(), "再把它改回数据分析师吧", Map.of(), "trace-1");
    }

    private static AiOperationProposal pending() {
        AiOperationProposal entity = new AiOperationProposal();
        entity.setId(9L);
        entity.setProposalNo("OP202609231138374768");
        entity.setConversationId(CONVERSATION_ID);
        entity.setToolName("proposeUserChange");
        entity.setAction("ASSIGN_ROLES");
        entity.setTargetType("USER");
        entity.setTargetId(2L);
        entity.setTargetName("user0292");
        entity.setStatus("PENDING");
        entity.setExpiresAt(LocalDateTime.now().plusMinutes(15));
        return entity;
    }

    @Test
    @DisplayName("命中复用分支时仍然推送 proposal 事件（否则用户看到「说生成了提案却没有卡片」）")
    void reusingPendingProposalStillPublishesCardEvent() {
        when(proposalMapper.selectPendingSameTarget(CONVERSATION_ID, "USER", 2L, "ASSIGN_ROLES"))
                .thenReturn(List.of(pending()));

        var payload = service().create(draft());

        assertThat(payload.proposalNo()).isEqualTo("OP202609231138374768");
        verify(eventPublisher)
                .publishProposal(eq(CONVERSATION_ID), any());
        verify(proposalMapper, never())
                .insert(any());
    }

    @Test
    @DisplayName("新建分支照旧推送（回归：不能在修复用分支时把这条弄丢）")
    void newProposalStillPublishes() {
        when(proposalMapper.selectPendingSameTarget(CONVERSATION_ID, "USER", 2L, "ASSIGN_ROLES"))
                .thenReturn(List.of());

        service().create(draft());

        verify(proposalMapper).insert(any());
        verify(eventPublisher).publishProposal(eq(CONVERSATION_ID), any());
    }
}
