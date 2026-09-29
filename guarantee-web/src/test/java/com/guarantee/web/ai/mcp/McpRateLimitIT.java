package com.guarantee.web.ai.mcp;

import com.guarantee.ai.controller.McpController;
import com.guarantee.common.api.Result;
import com.guarantee.web.GuaranteeAiAdminApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QPS 限流（TEST-MCP-06 的 IT 部分）：超限返回**可读** 429，并写一条 {@code source=MCP} 的审计。
 *
 * <p>属性刻意把 {@code qps-limit} 压到 1、配额放到极大：这样"被拒绝"只可能是 QPS 造成的，
 * 断言不会因两条闸门互相掩盖而假绿。</p>
 */
@SpringBootTest(
        classes = GuaranteeAiAdminApplication.class,
        properties = {
                "guarantee.data-init.enabled=false",
                "guarantee.ai.mcp.enabled=true",
                "guarantee.ai.mcp.qps-limit=1",
                "guarantee.ai.mcp.daily-quota=100000"
        })
class McpRateLimitIT extends McpRateLimitSupport {

    @Test
    @DisplayName("同一秒内第 2 次调用 → 429 + 可读中文，并写审计（MCP_RATE_LIMITED/REJECTED）")
    void qpsExceededIsReadableAndAudited() {
        String token = issueToken();

        ResponseEntity<Result<List<McpController.McpToolView>>> limited = null;
        for (int attempt = 0; attempt < 5 && limited == null; attempt++) {
            ResponseEntity<Result<List<McpController.McpToolView>>> first = controller.tools(bearer(token));
            if (first.getStatusCode().value() == 429) {
                limited = first;
                break;
            }
            ResponseEntity<Result<List<McpController.McpToolView>>> second = controller.tools(bearer(token));
            if (second.getStatusCode().value() == 429) {
                limited = second;
            }
        }

        assertThat(limited).as("qps-limit=1 时同一秒内的第 2 次调用必须被限流").isNotNull();
        assertThat(limited.getBody().code()).isEqualTo(429);
        assertThat(limited.getBody().message()).contains("过于频繁").contains("每秒最多 1 次");
        assertThat(limited.getBody().message()).doesNotContain("Exception");

        Integer audits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_operation_audit "
                        + "WHERE trace_id = ? AND source = 'MCP' AND action = 'MCP_RATE_LIMITED' AND result = 'REJECTED'",
                Integer.class, traceId);
        assertThat(audits).as("限流拒绝也要留痕（REQ-MCP-03）").isGreaterThanOrEqualTo(1);
    }
}
