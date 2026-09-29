package com.guarantee.web.ai.mcp;

import com.guarantee.ai.controller.McpController;
import com.guarantee.ai.mcp.McpTokenIssue;
import com.guarantee.ai.mcp.McpTokenService;
import com.guarantee.ai.mcp.McpToolCatalog;
import com.guarantee.common.trace.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 限流类 IT 的公共夹具（不注册为测试类）。
 *
 * <p>两个子类各自用不同的 {@code guarantee.ai.mcp.qps-limit / daily-quota} 启动上下文，
 * 因此 {@code @SpringBootTest} 放在子类上（不同属性 = 不同上下文），本类只放夹具与清理。</p>
 */
abstract class McpRateLimitSupport {

    protected static final List<String> PERMISSIONS = List.of(
            McpToolCatalog.PERMISSION_MCP_READ, "system:org:view");

    private static final String FIXTURE_PREFIX = "__mcp_it_lim_";

    @Autowired
    protected McpController controller;
    @Autowired
    protected McpTokenService tokenService;
    @Autowired
    protected JdbcTemplate jdbcTemplate;

    protected Long serviceAccountId;
    protected String traceId;
    private String fixtureUsername;
    private final List<Long> issuedTokenIds = new ArrayList<>();

    @BeforeEach
    void setUpFixture() {
        // 限流夹具也必须是**服务账号**（account_type=SERVICE）：签发路径有机器身份强校验
        fixtureUsername = FIXTURE_PREFIX + UUID.randomUUID().toString().substring(0, 8);
        Long deptId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_department WHERE is_deleted = 0 ORDER BY id LIMIT 1", Long.class);
        jdbcTemplate.update(
                "INSERT INTO sys_user (username, password, real_name, dept_id, status, must_change_password,"
                        + " account_type, is_deleted, deleted_by) VALUES (?, ?, ?, ?, 1, 0, 'SERVICE', 0, 'DB')",
                fixtureUsername, "not-a-login-account", "MCP 服务账号(IT/限流)", deptId);
        serviceAccountId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = ?", Long.class, fixtureUsername);

        traceId = "it-mcp-limit-" + UUID.randomUUID();
        MDC.put(TraceContext.MDC_KEY, traceId);
    }

    @AfterEach
    void tearDownFixture() {
        MDC.remove(TraceContext.MDC_KEY);
        for (Long id : issuedTokenIds) {
            jdbcTemplate.update("DELETE FROM ai_mcp_token WHERE id = ?", id);
        }
        issuedTokenIds.clear();
        jdbcTemplate.update("DELETE FROM ai_operation_audit WHERE trace_id = ?", traceId);
        jdbcTemplate.update("DELETE FROM ai_tool_call WHERE trace_id = ?", traceId);
        jdbcTemplate.update("DELETE FROM ai_audit_log WHERE trace_id = ?", traceId);
        jdbcTemplate.update("DELETE FROM sys_user WHERE username = ?", fixtureUsername);
    }

    /** 签发一把夹具凭据（权限固定；角色来自服务账号）。 */
    protected String issueToken() {
        McpTokenIssue issue = tokenService.issue(serviceAccountId, PERMISSIONS, null, "it");
        issuedTokenIds.add(issue.id());
        return issue.plainToken();
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }
}
