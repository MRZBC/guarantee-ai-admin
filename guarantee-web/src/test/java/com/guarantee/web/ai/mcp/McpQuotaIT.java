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
 * 每日配额（TEST-MCP-06 的 IT 部分）：超出当日配额后返回**可读** 429（不是 500）。
 *
 * <p>属性刻意把 {@code daily-quota} 压到 2、QPS 放到极大：这样"被拒绝"只可能是配额造成的，
 * 与 {@link McpRateLimitIT}（QPS）形成两条互不掩盖的证据。</p>
 */
@SpringBootTest(
        classes = GuaranteeAiAdminApplication.class,
        properties = {
                "guarantee.data-init.enabled=false",
                "guarantee.ai.mcp.enabled=true",
                "guarantee.ai.mcp.qps-limit=100000",
                "guarantee.ai.mcp.daily-quota=2"
        })
class McpQuotaIT extends McpRateLimitSupport {

    @Test
    @DisplayName("当日第 3 次调用 → 429 + 可读中文（配额口径写清 N 次/天）")
    void dailyQuotaExceededIsReadable() {
        String token = issueToken();

        assertThat(controller.tools(bearer(token)).getStatusCode().value())
                .as("配额 2 次/天：第 1 次放行").isEqualTo(200);
        assertThat(controller.tools(bearer(token)).getStatusCode().value())
                .as("第 2 次放行").isEqualTo(200);

        ResponseEntity<Result<List<McpController.McpToolView>>> third = controller.tools(bearer(token));

        assertThat(third.getStatusCode().value()).isEqualTo(429);
        assertThat(third.getBody().code()).isEqualTo(429);
        assertThat(third.getBody().message()).contains("每日调用配额").contains("2 次/天");
    }
}
