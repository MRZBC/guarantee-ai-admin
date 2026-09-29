package com.guarantee.web.ai;

import com.guarantee.ai.service.ProposalExecutionContext;
import com.guarantee.ai.service.ProposalPayload;
import com.guarantee.ai.service.ProposalPreview;
import com.guarantee.ai.service.ProposalRequest;
import com.guarantee.ai.service.ProposalService;
import com.guarantee.common.exception.BizException;
import com.guarantee.system.scope.DataScope;
import com.guarantee.web.GuaranteeAiAdminApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 目标指纹闭环的集成测试（TEST-CFG-07 / REQ-CFG-09 / AC-CFG-11）。
 *
 * <p><b>为什么必须有真实库的 IT</b>：单测只能证明"比对逻辑会拒绝"；
 * 这条链路真正容易断的地方在**生产者**——{@code target_fingerprint} 曾经全仓没有写入点，
 * 于是比对逻辑再正确也永远不生效（T-09 的真实形态）。因此本 IT 走完整链路：
 * {@code ProposalService.create}（真实执行器按真实行计算指纹）→ 模拟"确认前被他人改动"
 * → {@code confirm} 拒绝并落审计。</p>
 *
 * <p><b>不碰业务数据</b>：指纹不一致必须在**执行之前**拦下，因此
 * {@code sys_org} 的那一行自始至终不变（用例末尾会断言这一点）。
 * 模拟"被他人改动"的方式是把提案里**记录的**指纹改成过期值，而不是真的去改机构。</p>
 */
@SpringBootTest(
        classes = GuaranteeAiAdminApplication.class,
        properties = {
                "guarantee.data-init.enabled=false",
                "guarantee.ai.proposal-expire-interval-ms=3600000",
                "guarantee.ai.audit-inspect-interval-ms=3600000"
        })
class ProposalFingerprintIT {

    @Autowired
    private ProposalService proposalService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private Long adminId;
    private Long orgId;
    private String orgName;
    private Long proposalId;

    @BeforeEach
    void setUp() {
        adminId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = 'admin'", Long.class);
        Map<String, Object> org = jdbcTemplate.queryForMap(
                "SELECT id, org_name FROM sys_org WHERE is_deleted = 0 ORDER BY id LIMIT 1");
        orgId = ((Number) org.get("id")).longValue();
        orgName = String.valueOf(org.get("org_name"));
        // 清掉本用例可能残留的无会话提案（真实提案都带 conversation_id，这里只清测试夹具）
        jdbcTemplate.update("""
                DELETE FROM ai_operation_proposal
                WHERE conversation_id IS NULL AND tool_name = 'proposeOrgChange'
                  AND target_id = ? AND action = 'UPDATE'
                """, orgId);
    }

    @AfterEach
    void tearDown() {
        if (proposalId != null) {
            jdbcTemplate.update("DELETE FROM ai_operation_proposal WHERE id = ?", proposalId);
        }
    }

    @Test
    @DisplayName("create 写入真实指纹；确认前记录被改旧 → 拒绝执行、置 INVALIDATED、写审计、业务行不变")
    void confirmRejectsWhenTargetChangedAfterProposal() throws Exception {
        ProposalRequest request = objectMapper.readValue(
                "{\"id\":" + orgId + ",\"targetName\":\"" + orgName + "\",\"orgName\":\"改名后的机构\"}",
                ProposalRequest.class);
        ProposalPreview preview = ProposalPreview.of("把机构「" + orgName + "」改名为「改名后的机构」",
                List.of(new ProposalPreview.ChangeItem("orgName", "机构名称", orgName, "改名后的机构")),
                List.of(), List.of(), false);
        ProposalService.ProposalDraft draft = new ProposalService.ProposalDraft(
                null, adminId, "admin", "超级管理员", "proposeOrgChange", "UPDATE", "ORG",
                orgId, orgName, request, preview,
                Set.of("ai:system:write", "system:org:update"), "把机构改个名", null, "trace-fp-it");

        ProposalPayload payload = proposalService.create(draft);
        proposalId = payload.proposalId();

        // 1) 生产者闭环：提案里必须真的有指纹（曾经这里是恒为 null 的缺口）
        String recorded = jdbcTemplate.queryForObject(
                "SELECT target_fingerprint FROM ai_operation_proposal WHERE id = ?", String.class, proposalId);
        assertThat(recorded).as("create 必须按目标当前行写入指纹（T-09 闭环的生产者）")
                .isNotNull().hasSize(64).matches("[0-9a-f]{64}");

        // 2) 模拟"预览与确认之间目标被他人改动"：把提案记录的指纹改成一个过期值
        jdbcTemplate.update(
                "UPDATE ai_operation_proposal SET target_fingerprint = 'stale-fingerprint' WHERE id = ?",
                proposalId);

        // 3) 确认 → 必须在执行前拒绝，并留下痕迹
        ProposalExecutionContext context = new ProposalExecutionContext(adminId, "admin", "超级管理员",
                List.of("ADMIN"), List.of("ai:system:write", "system:org:update"),
                DataScope.all(null, null), "trace-fp-it");

        assertThatThrownBy(() -> proposalService.confirm(proposalId, context))
                .isInstanceOf(BizException.class)
                .hasMessageContaining(ProposalService.FINGERPRINT_MISMATCH_REASON);

        Map<String, Object> proposal = jdbcTemplate.queryForMap(
                "SELECT status, reject_reason, audit_id FROM ai_operation_proposal WHERE id = ?", proposalId);
        assertThat(proposal.get("status")).isEqualTo("INVALIDATED");
        assertThat(proposal.get("reject_reason")).isEqualTo(ProposalService.FINGERPRINT_MISMATCH_REASON);

        Object auditId = proposal.get("audit_id");
        assertThat(auditId).as("拒绝也要有审计（INVALIDATED 不能无痕）").isNotNull();
        Map<String, Object> audit = jdbcTemplate.queryForMap("""
                SELECT source, action, target_type, target_id, result, error_message
                FROM ai_operation_audit WHERE id = ?
                """, ((Number) auditId).longValue());
        assertThat(audit.get("source")).isEqualTo("AI");
        assertThat(audit.get("action")).isEqualTo("UPDATE");
        assertThat(audit.get("target_type")).isEqualTo("ORG");
        assertThat(((Number) audit.get("target_id")).longValue()).isEqualTo(orgId);
        assertThat(audit.get("result")).isEqualTo("REJECTED");
        assertThat(audit.get("error_message")).isEqualTo(ProposalService.FINGERPRINT_MISMATCH_REASON);

        // 4) 拒绝发生在执行之前：目标行一个字段都没变
        assertThat(jdbcTemplate.queryForObject(
                "SELECT org_name FROM sys_org WHERE id = ?", String.class, orgId))
                .as("指纹不一致必须在执行前拦下，业务行不得被改动").isEqualTo(orgName);
    }
}
