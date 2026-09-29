package com.guarantee.web.ai.mcp;

import com.guarantee.ai.controller.McpController;
import com.guarantee.ai.mcp.McpErrorCode;
import com.guarantee.ai.mcp.McpException;
import com.guarantee.ai.mcp.McpTokenIssue;
import com.guarantee.ai.mcp.McpTokenService;
import com.guarantee.ai.mcp.McpTokenView;
import com.guarantee.ai.mcp.McpToolCatalog;
import com.guarantee.common.api.Result;
import com.guarantee.common.security.CurrentUser;
import com.guarantee.common.security.Permissions;
import com.guarantee.common.trace.TraceContext;
import com.guarantee.system.scope.DataScopeService;
import com.guarantee.system.service.UserService;
import com.guarantee.web.GuaranteeAiAdminApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 业务 MCP 平台侧集成测试（TEST-MCP-04 / TEST-MCP-06 的 IT 部分，AC-MCP-02/03/05）。
 *
 * <p><b>跑真实 MySQL + 真实 Redis + 真实 Spring 上下文</b>（含 {@code AiToolRegistry} /
 * {@code DataScopeService} / {@code RecordingToolCallback} 落库链路），证明"接口真的接上了工具链"，
 * 而不是"mock 返回值长得对"。</p>
 *
 * <p>覆盖：</p>
 * <ol>
 *   <li>凭据管理面（AC-MCP-02）：只有 {@code account_type=SERVICE} 的账号能签发；
 *       <b>HUMAN 账号必须被可读拒绝</b>；签发响应含明文；列表不含明文；撤销后立即 401；</li>
 *   <li>工具面（AC-MCP-03）：有 {@code ai:mcp:read} → 13 个只读工具（含
 *       {@code queryBusinessKnowledge}）且无 {@code propose*}；无它 → 清单为空 + 调用 403；</li>
 *   <li>数据范围同源：{@code queryOrg} 的口径文本含 {@code DataScopeService} 对**同一账号**
 *       算出的范围描述（MCP 没有第二份范围判定）；</li>
 *   <li>来源与追溯（AC-MCP-05）：{@code ai_tool_call.source=MCP} + {@code trace_id} 一致，
 *       且审计可识别服务账号。</li>
 * </ol>
 *
 * <p>数据纪律：只建 {@code __mcp_it_svc_} 前缀的 SERVICE 账号夹具，{@code @AfterEach}
 * 物理清理由本用例产生的账号 / 凭据 / 记录（记录按本次独有的 trace_id 定位）。</p>
 */
@SpringBootTest(
        classes = GuaranteeAiAdminApplication.class,
        properties = {
                "guarantee.data-init.enabled=false",
                "guarantee.ai.mcp.enabled=true",
                "guarantee.ai.mcp.qps-limit=1000",
                "guarantee.ai.mcp.daily-quota=100000"
        })
class McpBackendIT {

    /** 覆盖 13 个只读工具所需要的全部权限（权限来自 Token，角色来自账号）。 */
    private static final List<String> READ_PERMISSIONS = List.of(
            McpToolCatalog.PERMISSION_MCP_READ,
            "system:org:view", "system:dept:view", "system:user:view",
            "system:role:view", "system:insurance:view",
            "system:audit:view", "ai:system:query");

    private static final String FIXTURE_PREFIX = "__mcp_it_svc_";

    @Autowired
    private McpController controller;
    @Autowired
    private McpTokenService tokenService;
    @Autowired
    private UserService userService;
    @Autowired
    private DataScopeService dataScopeService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long serviceAccountId;
    private Long adminUserId;
    private String fixtureUsername;
    private String traceId;
    private final List<Long> issuedTokenIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fixtureUsername = FIXTURE_PREFIX + UUID.randomUUID().toString().substring(0, 8);
        Long deptId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_department WHERE is_deleted = 0 ORDER BY id LIMIT 1", Long.class);
        jdbcTemplate.update(
                "INSERT INTO sys_user (username, password, real_name, dept_id, status, must_change_password,"
                        + " account_type, is_deleted, deleted_by) VALUES (?, ?, ?, ?, 1, 0, 'SERVICE', 0, 'DB')",
                fixtureUsername, "not-a-login-account", "MCP 服务账号(IT)", deptId);
        serviceAccountId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = ?", Long.class, fixtureUsername);

        adminUserId = jdbcTemplate.queryForObject(
                "SELECT u.id FROM sys_user u JOIN sys_user_role ur ON ur.user_id = u.id"
                        + " JOIN sys_role r ON r.id = ur.role_id"
                        + " WHERE r.role_code = 'ADMIN' AND u.status = 1 AND u.is_deleted = 0"
                        + " AND ur.is_deleted = 0 ORDER BY u.id LIMIT 1", Long.class);

        traceId = "it-mcp-" + UUID.randomUUID();
        // 与 TraceIdFilter 同一把钥匙：本类直接调 Controller，不走 HTTP，自己注入 MDC
        MDC.put(TraceContext.MDC_KEY, traceId);
    }

    @AfterEach
    void tearDown() {
        MDC.remove(TraceContext.MDC_KEY);
        CurrentUser.clear();
        SecurityContextHolder.clearContext();
        for (Long id : issuedTokenIds) {
            jdbcTemplate.update("DELETE FROM ai_mcp_token WHERE id = ?", id);
        }
        issuedTokenIds.clear();
        jdbcTemplate.update("DELETE FROM ai_tool_call WHERE trace_id = ?", traceId);
        jdbcTemplate.update("DELETE FROM ai_audit_log WHERE trace_id = ?", traceId);
        jdbcTemplate.update("DELETE FROM ai_operation_audit WHERE trace_id = ?", traceId);
        jdbcTemplate.update("DELETE FROM sys_user WHERE username = ?", fixtureUsername);
    }

    // ==================================================================
    // AC-MCP-02：签发（机器身份强校验）
    // ==================================================================

    @Test
    @DisplayName("SERVICE 账号可签发：响应含明文、库中只有前缀；列表不出现明文")
    void serviceAccountCanIssueAndPlaintextAppearsOnce() {
        loginAsAdmin();
        Result<McpTokenIssue> issued = controller.issueToken(
                new McpController.McpTokenIssueRequest(serviceAccountId, READ_PERMISSIONS, null));
        assertThat(issued.code()).isZero();
        issuedTokenIds.add(issued.data().id());

        assertThat(issued.data().plainToken()).startsWith("mcp_");
        String stored = jdbcTemplate.queryForObject(
                "SELECT token_hash FROM ai_mcp_token WHERE id = ?", String.class, issued.data().id());
        assertThat(stored).as("库里只有哈希").hasSize(64).matches("[0-9a-f]+")
                .doesNotContain(issued.data().plainToken());

        List<McpTokenView> views = controller.listTokens().data();
        assertThat(views).extracting(McpTokenView::tokenPrefix).contains(issued.data().tokenPrefix());
        assertThat(views.toString()).as("列表**不含明文**（也不含哈希）")
                .doesNotContain(issued.data().plainToken()).doesNotContain(stored);
    }

    @Test
    @DisplayName("HUMAN 账号调用签发接口/服务 → 可读拒绝（account_type=SERVICE 强校验）")
    void humanAccountCannotIssueToken() {
        Long humanId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE status = 1 AND is_deleted = 0 AND account_type = 'HUMAN'"
                        + " ORDER BY id LIMIT 1", Long.class);

        // ① 服务层（McpTokenService.issue 的强校验）
        McpException fromService = catchThrowableOfType(
                () -> tokenService.issue(humanId, READ_PERMISSIONS, null, "it"), McpException.class);
        assertThat(fromService).isNotNull();
        assertThat(fromService.getCode()).isEqualTo(McpErrorCode.ACCOUNT_DISABLED);
        assertThat(fromService.httpStatus()).isEqualTo(403);
        assertThat(fromService.getMessage()).contains("人类账号").contains("SERVICE");

        // ② 管理接口（McpServiceAccountResolver.requireEnabledAccount）
        loginAsAdmin();
        McpException fromController = catchThrowableOfType(() -> controller.issueToken(
                new McpController.McpTokenIssueRequest(humanId, READ_PERMISSIONS, null)), McpException.class);
        assertThat(fromController).isNotNull();
        assertThat(fromController.getCode()).isEqualTo(McpErrorCode.ACCOUNT_DISABLED);
        assertThat(fromController.getMessage()).contains("人类账号");

        Integer tokens = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_mcp_token WHERE service_account_id = ?", Integer.class, humanId);
        assertThat(tokens).as("被拒的签发不能留下任何凭据").isZero();
    }

    @Test
    @DisplayName("撤销后立即失效：管理接口撤销 → 下一次调用就是 401 可读（无缓存窗口）")
    void revokedTokenFailsImmediately() {
        loginAsAdmin();
        Result<McpTokenIssue> issued = controller.issueToken(
                new McpController.McpTokenIssueRequest(serviceAccountId, READ_PERMISSIONS, null));
        issuedTokenIds.add(issued.data().id());
        String token = issued.data().plainToken();

        assertThat(controller.tools(bearer(token)).getStatusCode().value())
                .as("撤销前可用").isEqualTo(200);

        Result<McpController.McpTokenRevokeResult> revoked = controller.revokeToken(issued.data().id());
        assertThat(revoked.code()).isZero();
        assertThat(revoked.data().message()).contains("立即失败");

        ResponseEntity<Result<List<McpController.McpToolView>>> after = controller.tools(bearer(token));
        assertThat(after.getStatusCode().value()).isEqualTo(401);
        assertThat(after.getBody().message()).contains("撤销");
    }

    // ==================================================================
    // AC-MCP-03：入口权限与清单
    // ==================================================================

    @Test
    @DisplayName("有 ai:mcp:read：清单 = 13 个只读工具（含 queryBusinessKnowledge），无任何写工具")
    void listToolsReturnsThirteenReadOnlyTools() {
        String token = issueViaService(READ_PERMISSIONS);

        ResponseEntity<Result<List<McpController.McpToolView>>> response = controller.tools(bearer(token));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        List<String> names = response.getBody().data().stream().map(McpController.McpToolView::name).toList();
        assertThat(names)
                .as("与 Java 侧白名单逐名一致（白名单又与网关 catalog.ts 逐名一致）")
                .containsExactlyInAnyOrderElementsOf(McpToolCatalog.readOnlyToolNames());
        assertThat(names).hasSize(13).contains("queryBusinessKnowledge");
        assertThat(names).noneMatch(name -> name.startsWith("propose"));
    }

    @Test
    @DisplayName("无 ai:mcp:read：清单为空 + 调用 403 可读拒绝（TEST-MCP-04 fail-closed）")
    void tokenWithoutEntryPermissionSeesNothing() {
        String token = issueViaService(List.of("system:org:view"));

        ResponseEntity<Result<List<McpController.McpToolView>>> tools = controller.tools(bearer(token));
        assertThat(tools.getStatusCode().value()).isEqualTo(200);
        assertThat(tools.getBody().data()).as("入口权限缺失时不给任何工具（不泄漏工具存在性）").isEmpty();

        ResponseEntity<Result<McpController.McpCallView>> call = controller.call("queryOrg", "{}", bearer(token));
        assertThat(call.getStatusCode().value()).isEqualTo(403);
        assertThat(call.getBody().message()).contains("ai:mcp:read");
    }

    @Test
    @DisplayName("凭据缺失 / 无效 → 401 可读中文（不是 500，也不是静默匿名）")
    void missingOrInvalidTokenIsReadable401() {
        ResponseEntity<Result<List<McpController.McpToolView>>> missing = controller.tools(null);
        assertThat(missing.getStatusCode().value()).isEqualTo(401);
        assertThat(missing.getBody().message()).contains("缺少 MCP Token");

        ResponseEntity<Result<List<McpController.McpToolView>>> invalid =
                controller.tools("Bearer mcp_this_token_was_never_issued_000000");
        assertThat(invalid.getStatusCode().value()).isEqualTo(401);
        assertThat(invalid.getBody().message()).contains("无效");
    }

    // ==================================================================
    // AC-MCP-03：数据范围与页面同源
    // ==================================================================

    @Test
    @DisplayName("同账号同条件：MCP 结果的口径文本含 DataScopeService 对同一账号算出的范围")
    void dataScopeIsSameSourceAsPage() {
        String token = issueViaService(READ_PERMISSIONS);

        ResponseEntity<Result<McpController.McpCallView>> response =
                controller.call("queryOrg", "{\"limit\":3}", bearer(token));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String result = response.getBody().data().result();
        assertThat(result).isNotBlank();

        List<String> roles = userService.listRoleCodesByUserId(serviceAccountId);
        String expectedScope = dataScopeService.resolve(serviceAccountId, roles).description();
        assertThat(result)
                .as("工具内部用的就是同一个 DataScopeService（MCP 没有第二份范围判定）")
                .contains(expectedScope);
    }

    // ==================================================================
    // AC-MCP-05：来源与 traceId 落库
    // ==================================================================

    @Test
    @DisplayName("每次调用落 ai_tool_call（source=MCP + traceId）与审计（归属服务账号）")
    void callPersistsSourceAndTraceId() {
        String token = issueViaService(READ_PERMISSIONS);

        ResponseEntity<Result<McpController.McpCallView>> response =
                controller.call("getCurrentDate", "{}", bearer(token));
        assertThat(response.getStatusCode().value()).isEqualTo(200);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT source, trace_id, status, conversation_id FROM ai_tool_call "
                        + "WHERE trace_id = ? AND tool_name = 'getCurrentDate'", traceId);
        assertThat(row.get("source")).isEqualTo("MCP");
        assertThat(row.get("trace_id")).isEqualTo(traceId);
        assertThat(row.get("status")).isEqualTo("SUCCESS");
        assertThat(row.get("conversation_id")).as("MCP 没有会话（V10 方案 A：可空）").isNull();

        Integer auditRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_audit_log WHERE trace_id = ? AND action = 'TOOL_CALL' AND user_id = ?",
                Integer.class, traceId, serviceAccountId);
        assertThat(auditRows).as("审计必须能识别是哪个服务账号调用的").isGreaterThanOrEqualTo(1);
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /** 走**管理接口**签发（与页面同一条路），返回明文。 */
    private String issueViaService(List<String> permissions) {
        McpTokenIssue issue = tokenService.issue(serviceAccountId, permissions, null, "it");
        issuedTokenIds.add(issue.id());
        return issue.plainToken();
    }

    /**
     * 模拟页面操作者：ADMIN 身份。
     *
     * <p>必须**同时**填两处，缺一不可：</p>
     * <ol>
     *   <li>{@code CurrentUser}（ThreadLocal）：controller 里的 {@code requirePrincipal()} 读它，
     *       用于审计的 operator 归属；</li>
     *   <li>Spring Security 的 {@code SecurityContext}：{@code @PreAuthorize("hasAuthority('ai:mcp:manage')")}
     *       读的是它——只设 ThreadLocal 会直接抛
     *       {@code AuthenticationCredentialsNotFoundException}（这不是业务拒绝，是夹具没搭好）。</li>
     * </ol>
     */
    private void loginAsAdmin() {
        CurrentUser.set(new CurrentUser.Principal(adminUserId, "admin", "超级管理员",
                List.of("ADMIN"), List.of(Permissions.AI_MCP_MANAGE)));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin", null,
                List.of(new SimpleGrantedAuthority(Permissions.AI_MCP_MANAGE))));
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
