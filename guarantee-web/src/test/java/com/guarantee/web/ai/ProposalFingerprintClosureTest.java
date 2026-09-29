package com.guarantee.web.ai;

import com.guarantee.ai.entity.AiOperationProposal;
import com.guarantee.ai.mapper.AiOperationProposalMapper;
import com.guarantee.ai.service.AiConversationService;
import com.guarantee.ai.service.OperationAuditService;
import com.guarantee.ai.service.ProposalEventPublisher;
import com.guarantee.ai.service.ProposalExecutionContext;
import com.guarantee.ai.service.ProposalExecutionResult;
import com.guarantee.ai.service.ProposalExecutor;
import com.guarantee.ai.service.ProposalFailureRecorder;
import com.guarantee.ai.service.ProposalSecretStore;
import com.guarantee.ai.service.ProposalService;
import com.guarantee.ai.service.executor.OrgProposalExecutor;
import com.guarantee.common.exception.BizException;
import com.guarantee.system.entity.SysOrg;
import com.guarantee.system.scope.DataScope;
import com.guarantee.system.service.OrgService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 确认链路的目标指纹闭环（TEST-CFG-07 / REQ-CFG-09 / AC-CFG-11）。
 *
 * <p>这一组测试存在的理由：{@code target_fingerprint} 此前**写入了但从不比对**
 * （T-09 未闭环）——预览与确认之间目标被他人改动，确认仍会执行，
 * 于是"人工确认的是当时看到的东西"这个保证并不成立，且失败时**没有任何痕迹**。</p>
 *
 * <p>覆盖四件事：指纹稳定（同一状态两次计算相同）、会被改动捕捉（业务字段或
 * {@code updated_at} 变化）、CREATE 无目标返回 null、确认时不一致则置
 * {@code INVALIDATED} + 写审计 + 抛可读错误；一致时正常继续执行。</p>
 */
class ProposalFingerprintClosureTest {

    private static final Long USER_ID = 7L;
    private static final Long TARGET_ID = 100L;

    private OrgService orgService;
    private OrgProposalExecutor orgExecutor;

    @BeforeEach
    void setUp() {
        orgService = mock(OrgService.class);
        orgExecutor = new OrgProposalExecutor(orgService);
    }

    private static SysOrg org() {
        SysOrg org = new SysOrg();
        org.setId(TARGET_ID);
        org.setOrgCode("ORG-100");
        org.setOrgName("广东省第1保函运营机构");
        org.setRegionCode("440000");
        org.setRegionName("广东省");
        org.setOrgLevel(2);
        org.setParentId(1L);
        org.setStatus(1);
        org.setSortNo(10);
        org.setIsDeleted(0);
        org.setUpdatedAt(LocalDateTime.of(2026, 9, 30, 10, 0, 0));
        return org;
    }

    private static AiOperationProposal proposal(String fingerprint) {
        AiOperationProposal proposal = new AiOperationProposal();
        proposal.setId(9L);
        proposal.setProposalNo("OP20260930120000ABCD");
        proposal.setConversationId(null);
        proposal.setUserId(USER_ID);
        proposal.setToolName("proposeOrgChange");
        proposal.setAction("UPDATE");
        proposal.setTargetType("ORG");
        proposal.setTargetId(TARGET_ID);
        proposal.setTargetName("广东省第1保函运营机构");
        proposal.setRequestPayload("{}");
        proposal.setRequiredPerms("ai:system:write,system:org:update");
        proposal.setTargetFingerprint(fingerprint);
        proposal.setStatus("PENDING");
        proposal.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        proposal.setTraceId("trace-fingerprint");
        return proposal;
    }

    // ==================================================================
    // 指纹本身：稳定 + 能捕捉改动
    // ==================================================================

    @Test
    @DisplayName("同一目标同一状态：两次计算结果完全相同（稳定，不依赖当前时间）")
    void fingerprintIsStableForUnchangedTarget() {
        when(orgService.getEntityById(TARGET_ID)).thenReturn(org(), org());

        String first = orgExecutor.fingerprint(proposal("x"), null);
        String second = orgExecutor.fingerprint(proposal("x"), null);

        assertThat(first).isNotBlank().isEqualTo(second);
    }

    @Test
    @DisplayName("业务字段变化 → 指纹变化；只有 updated_at 变化 → 指纹同样变化")
    void fingerprintChangesWhenTargetRowChanges() {
        SysOrg original = org();
        when(orgService.getEntityById(TARGET_ID)).thenReturn(original);
        String baseline = orgExecutor.fingerprint(proposal("x"), null);

        SysOrg renamed = org();
        renamed.setOrgName("广东省第1保函运营机构（已改名）");
        when(orgService.getEntityById(TARGET_ID)).thenReturn(renamed);
        assertThat(orgExecutor.fingerprint(proposal("x"), null))
                .as("业务字段被改必须被捕捉").isNotEqualTo(baseline);

        SysOrg touched = org();
        touched.setUpdatedAt(LocalDateTime.of(2026, 9, 30, 10, 0, 1));
        when(orgService.getEntityById(TARGET_ID)).thenReturn(touched);
        assertThat(orgExecutor.fingerprint(proposal("x"), null))
                .as("改了又改回（业务字段相同）也必须被 updated_at 捕捉")
                .isNotEqualTo(baseline);
    }

    @Test
    @DisplayName("CREATE 提案无目标 → 指纹为 null；目标已被删除 → 哨兵值")
    void createAndMissingTargetSemantics() {
        AiOperationProposal create = proposal(null);
        create.setAction("CREATE");
        create.setTargetId(null);
        assertThat(orgExecutor.fingerprint(create, null)).as("新建没有『确认前被改动』可言").isNull();

        when(orgService.getEntityById(TARGET_ID)).thenReturn(null);
        assertThat(orgExecutor.fingerprint(proposal("x"), null))
                .as("目标已不存在必须与任何已存指纹都不相等")
                .isEqualTo(ProposalExecutor.MISSING_FINGERPRINT)
                .isNotEqualTo("x");
    }

    // ==================================================================
    // 确认期闭环
    // ==================================================================

    @Test
    @DisplayName("确认前目标被改动 → 拒绝执行、置 INVALIDATED、写审计、给出可读原因（AC-CFG-11）")
    void confirmRejectsWhenFingerprintMismatch() {
        Fixture fixture = new Fixture();
        when(fixture.executor.fingerprint(any(), any())).thenReturn("CURRENT");
        AiOperationProposal pending = proposal("RECORDED");
        when(fixture.proposalMapper.selectById(9L)).thenReturn(pending);
        when(fixture.proposalMapper.claimForExecution(eq(9L), any())).thenReturn(1);

        assertThatThrownBy(() -> fixture.service.confirm(9L, fixture.context))
                .isInstanceOf(BizException.class)
                .hasMessageContaining(ProposalService.FINGERPRINT_MISMATCH_REASON);

        // 状态置为 INVALIDATED，并带上审计 id
        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        verify(fixture.proposalMapper).updateResult(eq(9L), status.capture(),
                eq(ProposalService.FINGERPRINT_MISMATCH_REASON), any(), any(), any());
        assertThat(status.getValue()).isEqualTo("INVALIDATED");

        // 拒绝必须有痕迹
        ArgumentCaptor<OperationAuditService.AuditEntry> entry =
                ArgumentCaptor.forClass(OperationAuditService.AuditEntry.class);
        verify(fixture.auditService).record(entry.capture(), any(), eq(9L), any(), any());
        assertThat(entry.getValue().result()).isEqualTo("REJECTED");
        assertThat(entry.getValue().errorMessage()).isEqualTo(ProposalService.FINGERPRINT_MISMATCH_REASON);
        assertThat(entry.getValue().targetType()).isEqualTo("ORG");
        assertThat(entry.getValue().targetId()).isEqualTo(TARGET_ID);

        // 绝不能在指纹不一致时执行
        verify(fixture.executor, never()).execute(any(), any(), any());
    }

    @Test
    @DisplayName("指纹一致 → 正常执行，不再是『写了但从不比对』（T-09 闭环）")
    void confirmProceedsWhenFingerprintMatches() {
        Fixture fixture = new Fixture();
        when(fixture.executor.fingerprint(any(), any())).thenReturn("SAME");
        AiOperationProposal pending = proposal("SAME");
        when(fixture.proposalMapper.selectById(9L)).thenReturn(pending, proposal("SAME"));
        when(fixture.proposalMapper.claimForExecution(eq(9L), any())).thenReturn(1);
        when(fixture.proposalMapper.updateResult(anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(fixture.auditService.record(any(), any(), any(), any(), any())).thenReturn(42L);
        when(fixture.executor.execute(any(), any(), any()))
                .thenReturn(ProposalExecutionResult.ok("机构已更新", Map.of("orgName", "旧"), Map.of("orgName", "新")));

        fixture.service.confirm(9L, fixture.context);

        verify(fixture.executor).fingerprint(any(), any());
        verify(fixture.executor).execute(any(), any(), any());
    }

    /** 确认链路的测试夹具：把 {@link ProposalService} 的 8 个依赖全部替换为 mock。 */
    private static final class Fixture {

        private final AiOperationProposalMapper proposalMapper = mock(AiOperationProposalMapper.class);
        private final ProposalSecretStore secretStore = mock(ProposalSecretStore.class);
        private final OperationAuditService auditService = mock(OperationAuditService.class);
        private final AiConversationService conversationService = mock(AiConversationService.class);
        private final ProposalEventPublisher eventPublisher = mock(ProposalEventPublisher.class);
        private final ProposalFailureRecorder failedRecorder = mock(ProposalFailureRecorder.class);
        private final ProposalExecutor executor = mock(ProposalExecutor.class);
        private final ProposalService service;
        private final ProposalExecutionContext context;

        @SuppressWarnings("unchecked")
        private Fixture() {
            ObjectProvider<List<ProposalExecutor>> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable(any())).thenReturn(List.of(executor));
            when(executor.targetType()).thenReturn("ORG");
            this.service = new ProposalService(proposalMapper, secretStore, auditService,
                    conversationService, eventPublisher, failedRecorder, new ObjectMapper(), provider);
            this.context = new ProposalExecutionContext(USER_ID, "admin", "超级管理员",
                    List.of("ADMIN"), List.of("ai:system:write", "system:org:update"),
                    DataScope.all(USER_ID, 1), "trace-fingerprint");
        }
    }
}
