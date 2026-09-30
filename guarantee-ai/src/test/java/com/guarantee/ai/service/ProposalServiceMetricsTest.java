package com.guarantee.ai.service;

import com.guarantee.ai.entity.AiOperationProposal;
import com.guarantee.ai.mapper.AiOperationProposalMapper;
import com.guarantee.ai.metrics.AiChatMetrics;
import com.guarantee.common.exception.BizException;
import com.guarantee.common.security.AuditSourceContext;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@code ai.proposals} 指标在**真实状态流转处**打点（AC-MCP-07 / SYS-NF-08）。
 *
 * <p><b>为什么必须有这个测试</b>：修 T6-06 之前，{@code AiChatMetrics.proposal(...)} 定义了
 * 却**没有任何生产调用点**——单测只直接调 metrics 本身，所以 4 个断言全绿，
 * 而真机 {@code /actuator/prometheus} 上连 {@code ai_proposals} 的 HELP/TYPE 都没有。
 * 因此这里刻意**不**直接调 metrics，而是驱动 {@link ProposalService} 的真实方法，
 * 再从 {@code MeterRegistry} 反查计数：测的是"线接上没有"，不是"meter 能不能计数"。</p>
 *
 * <p>每个状态一个用例，并额外断言：<b>被拒也计数</b>、<b>过期计数不重复</b>、
 * <b>标签键只有 status/source 且取值是枚举</b>。</p>
 */
@ExtendWith(MockitoExtension.class)
class ProposalServiceMetricsTest {

    private static final Long PROPOSAL_ID = 11L;
    private static final Long USER_ID = 7L;
    private static final Long CONVERSATION_ID = 3L;
    private static final String TARGET_TYPE = "USER";
    private static final String PROPOSAL_NO = "OP202609302255000001";

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
    @Mock
    private ProposalExecutor executor;

    private SimpleMeterRegistry registry;
    private ProposalService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = new ProposalService(proposalMapper, secretStore, auditService, conversationService,
                eventPublisher, failedRecorder, objectMapper, executorsProvider,
                new AiChatMetrics(registry, true));
        AuditSourceContext.clear();
        // 结果推不出去时会落一条会话消息；本测试只关心指标与状态，消息落库不做断言
        lenient().when(eventPublisher.publishResult(any(), any(), any(), any(), any(), any())).thenReturn(true);
        lenient().when(conversationService.appendMessage(anyLong(), anyString(), anyString())).thenReturn(null);
        lenient().when(auditService.record(any(), any(), any(), any(), any())).thenReturn(99L);
    }

    @AfterEach
    void tearDown() {
        AuditSourceContext.clear();
    }

    // ==================================================================
    // 各状态 +1
    // ==================================================================

    @Test
    @DisplayName("创建：新提案落库 → ai_proposals{status=created,source=ai} +1")
    void createCountsCreated() {
        when(proposalMapper.selectPendingSameTarget(any(), any(), any(), any())).thenReturn(List.of());
        doAnswer(invocation -> {
            ((AiOperationProposal) invocation.getArgument(0)).setId(PROPOSAL_ID);
            return 1;
        }).when(proposalMapper).insert(any());

        service.create(draft());

        assertThat(count(AiChatMetrics.PROPOSAL_CREATED, AiChatMetrics.PROPOSAL_SOURCE_AI)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("创建：命中「已有同目标待确认提案」的复用分支**不**计数（那不是一次新提案）")
    void reuseBranchDoesNotCountCreated() {
        when(proposalMapper.selectPendingSameTarget(any(), any(), any(), any()))
                .thenReturn(List.of(pendingProposal()));

        service.create(draft());

        assertThat(count(AiChatMetrics.PROPOSAL_CREATED, AiChatMetrics.PROPOSAL_SOURCE_AI)).isZero();
    }

    @Test
    @DisplayName("确认执行成功 → ai_proposals{status=confirmed,source=web} +1")
    void confirmSuccessCountsConfirmed() {
        stubConfirmHappyPath();

        service.confirm(PROPOSAL_ID, context(List.of()));

        assertThat(count(AiChatMetrics.PROPOSAL_CONFIRMED, AiChatMetrics.PROPOSAL_SOURCE_WEB)).isEqualTo(1.0);
        assertThat(count(AiChatMetrics.PROPOSAL_FAILED, AiChatMetrics.PROPOSAL_SOURCE_WEB)).isZero();
    }

    @Test
    @DisplayName("助手驱动的确认 → source=ai（与审计来源同词表；页面确认才是 web）")
    void aiDrivenConfirmCountsSourceAi() {
        stubConfirmHappyPath();

        AuditSourceContext.markAiDriven();
        try {
            service.confirm(PROPOSAL_ID, context(List.of()));
        } finally {
            AuditSourceContext.clear();
        }

        assertThat(count(AiChatMetrics.PROPOSAL_CONFIRMED, AiChatMetrics.PROPOSAL_SOURCE_AI)).isEqualTo(1.0);
        assertThat(count(AiChatMetrics.PROPOSAL_CONFIRMED, AiChatMetrics.PROPOSAL_SOURCE_WEB)).isZero();
    }

    @Test
    @DisplayName("确认时执行失败（返回 failed 结果）→ status=failed +1（不会同时记 confirmed）")
    void confirmExecutionFailureCountsFailed() {
        stubConfirmHappyPath();
        when(executor.execute(any(), any(), any()))
                .thenReturn(ProposalExecutionResult.failed("数据库约束冲突"));

        service.confirm(PROPOSAL_ID, context(List.of()));

        assertThat(count(AiChatMetrics.PROPOSAL_FAILED, AiChatMetrics.PROPOSAL_SOURCE_WEB)).isEqualTo(1.0);
        assertThat(count(AiChatMetrics.PROPOSAL_CONFIRMED, AiChatMetrics.PROPOSAL_SOURCE_WEB)).isZero();
    }

    @Test
    @DisplayName("确认时权限已变更 → status=invalidated +1（可读拒绝，且不记 confirmed）")
    void confirmWithoutPermissionCountsInvalidated() {
        AiOperationProposal proposal = pendingProposal();
        proposal.setRequiredPerms("system:user:update");
        when(proposalMapper.selectById(PROPOSAL_ID)).thenReturn(proposal);

        assertThatThrownBy(() -> service.confirm(PROPOSAL_ID, context(List.of())))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("权限已变更");

        assertThat(count(AiChatMetrics.PROPOSAL_INVALIDATED, AiChatMetrics.PROPOSAL_SOURCE_WEB)).isEqualTo(1.0);
        assertThat(count(AiChatMetrics.PROPOSAL_CONFIRMED, AiChatMetrics.PROPOSAL_SOURCE_WEB)).isZero();
    }

    @Test
    @DisplayName("被拒也计数 → ai_proposals{status=rejected,source=web} +1")
    void rejectCountsRejected() {
        when(proposalMapper.selectById(PROPOSAL_ID)).thenReturn(pendingProposal());
        when(proposalMapper.updateResult(eq(PROPOSAL_ID), eq("REJECTED"), any(), any(), any(), any()))
                .thenReturn(1);

        service.reject(PROPOSAL_ID, USER_ID, "先不改");

        assertThat(count(AiChatMetrics.PROPOSAL_REJECTED, AiChatMetrics.PROPOSAL_SOURCE_WEB)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("过期计数 → status=expired,source=system +1；重复清理同一条**不**重复计数")
    void expireCountsOnceAndIsIdempotent() {
        when(proposalMapper.selectOverdue(any(), anyInt())).thenReturn(List.of(pendingProposal()));
        when(proposalMapper.updateResult(eq(PROPOSAL_ID), eq("EXPIRED"), any(), any(), any(), any()))
                .thenReturn(1);

        assertThat(service.expireOverdue()).isEqualTo(1);
        assertThat(count(AiChatMetrics.PROPOSAL_EXPIRED, AiChatMetrics.PROPOSAL_SOURCE_SYSTEM)).isEqualTo(1.0);

        // 第二次清扫：条件更新影响 0 行（已被别人处理）→ 指标不得再 +1
        when(proposalMapper.updateResult(eq(PROPOSAL_ID), eq("EXPIRED"), any(), any(), any(), any()))
                .thenReturn(0);
        service.expireOverdue();
        assertThat(count(AiChatMetrics.PROPOSAL_EXPIRED, AiChatMetrics.PROPOSAL_SOURCE_SYSTEM)).isEqualTo(1.0);
    }

    // ==================================================================
    // 标签纪律（AC-MCP-08）
    // ==================================================================

    @Test
    @DisplayName("标签键只有 status/source，且取值全在枚举内（不得出现 id/会话/正文）")
    void proposalTagKeysAndValuesAreEnumerated() {
        // 制造一个序列即可检查标签（走真实 create 分支，而不是直接调 metrics）
        when(proposalMapper.selectPendingSameTarget(any(), any(), any(), any())).thenReturn(List.of());
        doAnswer(invocation -> {
            ((AiOperationProposal) invocation.getArgument(0)).setId(PROPOSAL_ID);
            return 1;
        }).when(proposalMapper).insert(any());
        service.create(draft());

        Set<String> keys = new HashSet<>();
        Set<String> statusValues = new HashSet<>();
        Set<String> sourceValues = new HashSet<>();
        Set<String> allowedStatus = Set.of(
                AiChatMetrics.PROPOSAL_CREATED, AiChatMetrics.PROPOSAL_CONFIRMED,
                AiChatMetrics.PROPOSAL_REJECTED, AiChatMetrics.PROPOSAL_INVALIDATED,
                AiChatMetrics.PROPOSAL_EXPIRED, AiChatMetrics.PROPOSAL_FAILED);
        Set<String> allowedSource = Set.of(
                AiChatMetrics.PROPOSAL_SOURCE_AI, AiChatMetrics.PROPOSAL_SOURCE_WEB,
                AiChatMetrics.PROPOSAL_SOURCE_SYSTEM);

        for (Meter meter : registry.getMeters()) {
            if (!meter.getId().getName().equals(AiChatMetrics.PROPOSALS)) {
                continue;
            }
            for (var tag : meter.getId().getTags()) {
                keys.add(tag.getKey());
                if ("status".equals(tag.getKey())) {
                    assertThat(allowedStatus).contains(tag.getValue().toUpperCase(java.util.Locale.ROOT));
                    statusValues.add(tag.getValue());
                }
                if ("source".equals(tag.getKey())) {
                    assertThat(allowedSource).contains(tag.getValue().toUpperCase(java.util.Locale.ROOT));
                    sourceValues.add(tag.getValue());
                }
            }
        }

        assertThat(keys).as("ai.proposals 只允许 status / source 两个标签键").containsExactlyInAnyOrder("status", "source");
        assertThat(statusValues).as("标签值已被 sanitize 归一为小写").isNotEmpty();
        assertThat(sourceValues).isNotEmpty();
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    /** 读取某个 status/source 组合的计数；未注册（0 次）时返回 0。 */
    private double count(String status, String source) {
        var counter = registry.find(AiChatMetrics.PROPOSALS)
                .tag("status", status.toLowerCase(java.util.Locale.ROOT))
                .tag("source", source.toLowerCase(java.util.Locale.ROOT))
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static AiOperationProposal pendingProposal() {
        AiOperationProposal proposal = new AiOperationProposal();
        proposal.setId(PROPOSAL_ID);
        proposal.setProposalNo(PROPOSAL_NO);
        proposal.setConversationId(CONVERSATION_ID);
        proposal.setUserId(USER_ID);
        proposal.setToolName("proposeUserChange");
        proposal.setAction("ASSIGN_ROLES");
        proposal.setTargetType(TARGET_TYPE);
        proposal.setTargetId(2L);
        proposal.setTargetName("user0292");
        proposal.setStatus("PENDING");
        proposal.setExpiresAt(LocalDateTime.now().plusMinutes(15));
        return proposal;
    }

    private static ProposalExecutionContext context(List<String> permissions) {
        return new ProposalExecutionContext(USER_ID, "admin", "超级管理员",
                List.of("ADMIN"), permissions, null, "trace-1");
    }

    private static ProposalService.ProposalDraft draft() {
        return new ProposalService.ProposalDraft(CONVERSATION_ID, USER_ID, "admin", "超级管理员",
                "proposeUserChange", "ASSIGN_ROLES", TARGET_TYPE, 2L, "user0292",
                ProposalRequest.empty(), null, Set.of(), "把他改成数据分析师", Map.of(), "trace-1");
    }

    /** 确认成功路径所需的全部替身（指纹为空 → 跳过比对；无敏感参数）。 */
    private void stubConfirmHappyPath() {
        when(proposalMapper.selectById(PROPOSAL_ID)).thenReturn(pendingProposal());
        when(proposalMapper.claimForExecution(eq(PROPOSAL_ID), any())).thenReturn(1);
        when(executorsProvider.getIfAvailable(any())).thenReturn(List.of(executor));
        when(executor.targetType()).thenReturn(TARGET_TYPE);
        when(executor.execute(any(), any(), any()))
                .thenReturn(ProposalExecutionResult.ok("已执行", Map.of(), Map.of("status", 0)));
    }
}
