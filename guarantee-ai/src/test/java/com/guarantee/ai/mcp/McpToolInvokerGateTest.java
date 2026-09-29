package com.guarantee.ai.mcp;

import com.guarantee.ai.tool.AiToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MCP 入口权限（{@code ai:mcp:read}）硬门禁与 {@code tools/list} 口径（TEST-MCP-04 的单测部分）。
 *
 * <p><b>门禁与"注册裁剪"是两层，不能混为一谈</b>：</p>
 * <ol>
 *   <li>入口权限：没有 {@code ai:mcp:read} 的服务账号，MCP 面**整体**不可用
 *       （清单为空 + 调用拒绝，连"哪些工具存在"都不告诉它）；</li>
 *   <li>注册裁剪：有入口权限后，具体工具仍按域权限裁剪（与页面/助手同一套
 *       {@code AiToolRegistry} 语义）——聊天链路"登录即可见 4 个公开只读工具"说的
 *       是这一层，**不是**入口层。</li>
 * </ol>
 */
class McpToolInvokerGateTest {

    private static final McpPrincipal WITH_READ = new McpPrincipal(
            9L, "svc-mcp", "MCP 服务账号",
            List.of(McpToolCatalog.PERMISSION_MCP_READ, "system:org:view"),
            List.of("ADMIN"), "trace-gate", null);

    private static final McpPrincipal WITHOUT_READ = new McpPrincipal(
            9L, "svc-mcp", "MCP 服务账号",
            List.of("system:org:view"),
            List.of("ADMIN"), "trace-gate", null);

    private AiToolRegistry registry;
    private McpToolInvoker invoker;
    private Tools tools;

    @BeforeEach
    void setUp() {
        registry = mock(AiToolRegistry.class);
        invoker = new McpToolInvoker(registry, new ObjectMapper());
        tools = new Tools();
    }

    private ToolCallback[] callbacks() {
        return ToolCallbacks.from(tools);
    }

    // ==================================================================
    // 入口权限（硬门禁）
    // ==================================================================

    @Test
    @DisplayName("无 ai:mcp:read：清单为空，且**根本不查注册表**（不泄漏工具存在性）")
    void listWithoutEntryPermissionIsEmptyAndRegistryUntouched() {
        assertThat(invoker.listTools(WITHOUT_READ)).isEmpty();
        assertThat(invoker.listTools(null)).isEmpty();
        verifyNoInteractions(registry);
    }

    @Test
    @DisplayName("无 ai:mcp:read：调用一律可读拒绝（PERMISSION_REQUIRED/403），白名单都轮不到")
    void invokeWithoutEntryPermissionIsRejected() {
        McpException ex = catchThrowableOfType(
                () -> invoker.invoke("queryOrderSummary", "{}", WITHOUT_READ), McpException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(McpErrorCode.PERMISSION_REQUIRED);
        assertThat(ex.httpStatus()).isEqualTo(403);
        assertThat(ex.getMessage()).contains(McpToolCatalog.PERMISSION_MCP_READ).contains("清单为空");
        verifyNoInteractions(registry);
    }

    @Test
    @DisplayName("hasMcpRead：null 主体按「无权限」处理（fail-closed）")
    void hasMcpReadIsNullSafe() {
        assertThat(McpToolInvoker.hasMcpRead(null)).isFalse();
        assertThat(McpToolInvoker.hasMcpRead(WITHOUT_READ)).isFalse();
        assertThat(McpToolInvoker.hasMcpRead(WITH_READ)).isTrue();
    }

    // ==================================================================
    // 清单 = 调用面的子集
    // ==================================================================

    @Test
    @DisplayName("清单只用白名单内的只读工具：注册集里混进的写工具会被剔除（AC-MCP-04）")
    void listKeepsOnlyWhitelistedReadOnlyTools() {
        when(registry.callbacks(anyList())).thenReturn(callbacks());

        List<McpToolInvoker.McpToolDefinition> definitions = invoker.listTools(WITH_READ);

        assertThat(definitions).extracting(McpToolInvoker.McpToolDefinition::name)
                .as("registerOnlyFakeTool 不在白名单里，必须被剔除")
                .containsExactly("queryOrderSummary");
        assertThat(definitions.get(0).description()).contains("只读订单汇总");
        assertThat(definitions.get(0).inputSchemaJson()).contains("orderType");
    }

    @Test
    @DisplayName("清单把权限快照原样交给注册裁剪（MCP 不自己判权限）")
    void listDelegatesTrimmingToRegistry() {
        when(registry.callbacks(anyList())).thenReturn(callbacks());

        invoker.listTools(WITH_READ);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<String>> permissions =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(registry).callbacks(permissions.capture());
        assertThat(permissions.getValue()).containsExactlyElementsOf(WITH_READ.permissions());
    }

    // ==================================================================
    // 替身
    // ==================================================================

    /** 一个白名单内的只读工具 + 一个"看起来是写能力"的非白名单工具。 */
    public static final class Tools {

        @Tool(name = "queryOrderSummary", description = "测试用只读订单汇总工具")
        public String queryOrderSummary(
                @ToolParam(description = "订单类型：TENDER/PERFORMANCE/ALL", required = false) String orderType) {
            return "{\"dataSource\":\"订单汇总 · 全量\",\"truncated\":false,\"total\":3}";
        }

        @Tool(name = "registerOnlyFakeTool", description = "测试用：不在业务 MCP 白名单里的工具")
        public String registerOnlyFakeTool() {
            return "{}";
        }
    }
}
