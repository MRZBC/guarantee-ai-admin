package com.guarantee.ai.mcp;

import com.guarantee.ai.controller.McpController;
import com.guarantee.ai.service.OperationAuditService;
import com.guarantee.common.api.Result;
import com.guarantee.common.api.ResultCode;
import com.guarantee.common.exception.BizException;
import com.guarantee.common.security.CurrentUser;
import com.guarantee.common.security.Permissions;
import com.guarantee.system.vo.UserVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MCP 协议面的**响应口径**（TEST-MCP-06 的单测部分）。
 *
 * <p>这里钉死一件容易做错的事：{@code /api/ai/mcp/**} 必须返回**真实 HTTP 状态**
 * （401/403/429），而不是项目页面面惯用的"HTTP 200 + 业务码"。原因是冻结的网关
 * （{@code tools/business-mcp/src/backend.ts}）只在**非 2xx** 时读 {@code message}；
 * HTTP 200 + {@code code=401} 会被它判成成功体，最终抛 {@code INVALID_RESPONSE}，
 * AC-MCP-02 的"撤销后立刻给出可读错误"就落不了地。</p>
 */
class McpControllerProtocolTest {

    private McpTokenService tokenService;
    private McpToolInvoker invoker;
    private McpServiceAccountResolver resolver;
    private McpRateLimiter limiter;
    private OperationAuditService auditService;
    private McpController controller;

    private static final McpPrincipal PRINCIPAL = new McpPrincipal(
            77L, "svc-mcp", "MCP 服务账号",
            List.of(McpToolCatalog.PERMISSION_MCP_READ, Permissions.ORG_VIEW),
            List.of("ADMIN"), "trace-1", null);

    private static final McpTokenVerification VERIFICATION = new McpTokenVerification(
            5L, 77L, PRINCIPAL.permissions(), "mcp_9f3c2a", null);

    @BeforeEach
    void setUp() {
        tokenService = mock(McpTokenService.class);
        invoker = mock(McpToolInvoker.class);
        resolver = mock(McpServiceAccountResolver.class);
        limiter = mock(McpRateLimiter.class);
        auditService = mock(OperationAuditService.class);
        controller = new McpController(tokenService, invoker, resolver, limiter, auditService,
                new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    // ==================================================================
    // 凭据缺失 / 无效 / 撤销 / 过期 → 401 + 可读中文
    // ==================================================================

    @Test
    @DisplayName("缺 Authorization → HTTP 401 + code 401 + 可读中文")
    void missingTokenIsReadable401() {
        ResponseEntity<Result<List<McpController.McpToolView>>> response = controller.tools(null);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(401);
        assertThat(response.getBody().message()).contains("缺少 MCP Token").contains("Authorization");
    }

    @Test
    @DisplayName("Authorization 不是 Bearer → 401 + 可读中文（不是 500、不是静默匿名）")
    void wrongAuthSchemeIsReadable401() {
        ResponseEntity<Result<List<McpController.McpToolView>>> response = controller.tools("Basic abc");

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody().message()).contains("Bearer");
    }

    @Test
    @DisplayName("Token 无效 / 已撤销 / 已过期 → 各自的 401 可读文案")
    void invalidRevokedExpiredAreReadable401() {
        when(tokenService.verify("mcp_invalid")).thenThrow(
                new McpException(McpErrorCode.TOKEN_INVALID, "MCP Token 无效（未签发或已下架）"));
        when(tokenService.verify("mcp_revoked")).thenThrow(
                new McpException(McpErrorCode.TOKEN_REVOKED, "MCP Token 已被撤销，请重新签发"));
        when(tokenService.verify("mcp_expired")).thenThrow(
                new McpException(McpErrorCode.TOKEN_EXPIRED, "MCP Token 已过期，请重新签发"));

        assertThat(controller.tools("Bearer mcp_invalid").getStatusCode().value()).isEqualTo(401);
        assertThat(controller.tools("Bearer mcp_invalid").getBody().message()).contains("无效");

        ResponseEntity<Result<List<McpController.McpToolView>>> revoked =
                controller.tools("Bearer mcp_revoked");
        assertThat(revoked.getStatusCode().value()).isEqualTo(401);
        assertThat(revoked.getBody().message()).contains("撤销");

        ResponseEntity<Result<List<McpController.McpToolView>>> expired =
                controller.tools("Bearer mcp_expired");
        assertThat(expired.getStatusCode().value()).isEqualTo(401);
        assertThat(expired.getBody().message()).contains("过期");
    }

    // ==================================================================
    // 限流 / 配额 → 429 + 可读中文 + 审计
    // ==================================================================

    @Test
    @DisplayName("QPS 超限 → HTTP 429 + code 429 + 可读中文，并写一条审计")
    void rateLimitedIsReadable429AndAudited() {
        when(tokenService.verify("mcp_x")).thenReturn(VERIFICATION);
        when(resolver.resolve(any(), any())).thenReturn(PRINCIPAL);
        org.mockito.Mockito.doThrow(new McpException(McpErrorCode.RATE_LIMITED,
                        "调用过于频繁：每 Token 每秒最多 2 次 MCP 调用（本秒第 3 次）：请降低频率后重试"))
                .when(limiter).check(VERIFICATION.tokenPrefix());

        ResponseEntity<Result<List<McpController.McpToolView>>> response = controller.tools("Bearer mcp_x");

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getBody().code()).isEqualTo(429);
        assertThat(response.getBody().message()).contains("调用过于频繁");
        assertThat(response.getBody().message()).doesNotContain("Exception");
        verify(auditService).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("每日配额超限 → HTTP 429 + 可读中文（与 QPS 用不同 code，便于外部区分）")
    void quotaExceededIsReadable429() {
        when(tokenService.verify("mcp_x")).thenReturn(VERIFICATION);
        when(resolver.resolve(any(), any())).thenReturn(PRINCIPAL);
        org.mockito.Mockito.doThrow(new McpException(McpErrorCode.DAILY_QUOTA_EXCEEDED,
                        "已达每日调用配额上限（2 次/天，本次为当日第 3 次）：请次日重试"))
                .when(limiter).check(VERIFICATION.tokenPrefix());

        ResponseEntity<Result<List<McpController.McpToolView>>> response = controller.tools("Bearer mcp_x");

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getBody().message()).contains("每日调用配额");
    }

    // ==================================================================
    // 入口权限 fail-closed
    // ==================================================================

    @Test
    @DisplayName("无 ai:mcp:read → 清单为空（200 + 空数组），不是「看起来有工具」")
    void toolsWithoutEntryPermissionIsEmptyList() {
        McpPrincipal noRead = new McpPrincipal(77L, "svc-mcp", "MCP 服务账号",
                List.of(Permissions.ORG_VIEW), List.of("ADMIN"), "trace-1", null);
        when(tokenService.verify("mcp_x")).thenReturn(VERIFICATION);
        when(resolver.resolve(any(), any())).thenReturn(noRead);
        when(invoker.listTools(noRead)).thenReturn(List.of());

        ResponseEntity<Result<List<McpController.McpToolView>>> response = controller.tools("Bearer mcp_x");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().data()).isEmpty();
        verify(invoker).listTools(noRead);
    }

    @Test
    @DisplayName("无 ai:mcp:read 的调用 → HTTP 403 + 可读中文（fail-closed，不执行工具）")
    void callWithoutEntryPermissionIs403() {
        when(tokenService.verify("mcp_x")).thenReturn(VERIFICATION);
        when(resolver.resolve(any(), any())).thenReturn(PRINCIPAL);
        when(invoker.invoke(eq("queryOrg"), any(), any())).thenThrow(new McpException(
                McpErrorCode.PERMISSION_REQUIRED,
                "服务账号缺少 ai:mcp:read 权限：业务 MCP 只对该权限的持有者开放（清单为空、调用一律拒绝）"));

        ResponseEntity<Result<McpController.McpCallView>> response =
                controller.call("queryOrg", "{}", "Bearer mcp_x");

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody().code()).isEqualTo(403);
        assertThat(response.getBody().message()).contains("ai:mcp:read");
    }

    @Test
    @DisplayName("成功调用：返回冻结契约的 { result, dataSource, truncated } 三字段")
    void successfulCallMatchesFrozenContract() {
        when(tokenService.verify("mcp_x")).thenReturn(VERIFICATION);
        when(resolver.resolve(any(), any())).thenReturn(PRINCIPAL);
        when(invoker.invoke(eq("queryOrderSummary"), any(), any())).thenReturn(
                new McpToolInvocation("queryOrderSummary", "{\"total\":3}", "订单汇总 · 全量", true));

        ResponseEntity<Result<McpController.McpCallView>> response =
                controller.call("queryOrderSummary", "{\"orderType\":\"TENDER\"}", "Bearer mcp_x");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().data().result()).isEqualTo("{\"total\":3}");
        assertThat(response.getBody().data().dataSource()).isEqualTo("订单汇总 · 全量");
        assertThat(response.getBody().data().truncated()).isTrue();
        verify(tokenService).markUsed(VERIFICATION.tokenId());
    }

    // ==================================================================
    // 页面面：凭据管理
    // ==================================================================

    @Test
    @DisplayName("签发：明文只在响应里出现一次，并写一条 WEB 审计")
    void issueReturnsPlaintextOnceAndAudits() {
        CurrentUser.set(new CurrentUser.Principal(1L, "admin", "超级管理员",
                List.of("ADMIN"), List.of(Permissions.AI_MCP_MANAGE)));
        UserVO account = new UserVO();
        account.setId(77L);
        account.setUsername("svc-mcp");
        account.setStatus(1);
        when(resolver.requireEnabledAccount(77L)).thenReturn(account);
        when(tokenService.issue(eq(77L), any(), any(), eq("1"))).thenReturn(
                new McpTokenIssue(5L, "mcp_PLAINTEXT_ONLY_ONCE", "mcp_9f3c2a",
                        LocalDateTime.now().plusDays(30)));

        Result<McpTokenIssue> result = controller.issueToken(new McpController.McpTokenIssueRequest(
                77L, List.of(McpToolCatalog.PERMISSION_MCP_READ), null));

        assertThat(result.code()).isZero();
        assertThat(result.data().plainToken()).isEqualTo("mcp_PLAINTEXT_ONLY_ONCE");
        verify(auditService).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("签发：权限里没有 ai:mcp:read 时直接拒绝（否则签出一把在入口就是死的凭据）")
    void issueWithoutEntryPermissionIsRejected() {
        CurrentUser.set(new CurrentUser.Principal(1L, "admin", "超级管理员",
                List.of("ADMIN"), List.of(Permissions.AI_MCP_MANAGE)));

        assertThatThrownBy(() -> controller.issueToken(new McpController.McpTokenIssueRequest(
                77L, List.of(Permissions.ORG_VIEW), null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining(McpToolCatalog.PERMISSION_MCP_READ);

        verify(tokenService, never()).issue(any(), any(), any(), any());
    }

    @Test
    @DisplayName("撤销：不存在/已撤销 → 404 语义的可读错误；成功时给出「立即生效」的说明")
    void revokeIsReadable() {
        CurrentUser.set(new CurrentUser.Principal(1L, "admin", "超级管理员",
                List.of("ADMIN"), List.of(Permissions.AI_MCP_MANAGE)));
        when(tokenService.revoke(5L, "1")).thenReturn(false);
        when(tokenService.revoke(6L, "1")).thenReturn(true);

        assertThatThrownBy(() -> controller.revokeToken(5L))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode())
                        .isEqualTo(ResultCode.NOT_FOUND.code()))
                .hasMessageContaining("不存在或已经撤销");

        Result<McpController.McpTokenRevokeResult> ok = controller.revokeToken(6L);
        assertThat(ok.code()).isZero();
        assertThat(ok.data().revoked()).isTrue();
        assertThat(ok.data().message()).contains("立即失败");
    }

    @Test
    @DisplayName("未登录调用管理接口 → 401 语义的可读错误")
    void managementRequiresLogin() {
        assertThatThrownBy(() -> controller.issueToken(new McpController.McpTokenIssueRequest(
                77L, List.of(McpToolCatalog.PERMISSION_MCP_READ), null)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode())
                        .isEqualTo(ResultCode.UNAUTHORIZED.code()));
    }
}
