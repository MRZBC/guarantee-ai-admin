package com.guarantee.ai.mcp;

import com.guarantee.ai.controller.McpController;
import com.guarantee.ai.service.OperationAuditService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 开关 fail-closed（REQ-MCP-05 / AC-MCP-06）：
 * {@code guarantee.ai.mcp.enabled=false}（**默认值**）时 {@link McpController} **不注册**，
 * 接口根本不存在；显式置 true 才注册。
 *
 * <p>为什么单测这一层：E2E 证据只能证明"那一次启动的 8088 上没有这个接口"，
 * 而这个测试证明的是"**默认就是关的**"——两者缺一不可（否则可能有人把默认值改成 true
 * 而 E2E 仍全绿）。</p>
 */
class McpEnabledConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(McpController.class)
            .withBean(McpTokenService.class, () -> mock(McpTokenService.class))
            .withBean(McpToolInvoker.class, () -> mock(McpToolInvoker.class))
            .withBean(McpServiceAccountResolver.class, () -> mock(McpServiceAccountResolver.class))
            .withBean(McpRateLimiter.class, () -> mock(McpRateLimiter.class))
            .withBean(OperationAuditService.class, () -> mock(OperationAuditService.class))
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    @DisplayName("不设开关（默认 false）：McpController 不注册 → 接口不存在")
    void defaultOffDoesNotRegisterController() {
        runner.run(context -> assertThat(context).doesNotHaveBean(McpController.class));
    }

    @Test
    @DisplayName("显式 guarantee.ai.mcp.enabled=false：同样不注册")
    void explicitFalseDoesNotRegisterController() {
        runner.withPropertyValues("guarantee.ai.mcp.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(McpController.class));
    }

    @Test
    @DisplayName("显式 guarantee.ai.mcp.enabled=true：注册控制器（接口才存在）")
    void explicitTrueRegistersController() {
        runner.withPropertyValues("guarantee.ai.mcp.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(McpController.class));
    }
}
