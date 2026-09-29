package com.guarantee.ai.mcp;

import com.guarantee.ai.tool.AiPermissionGuard;
import com.guarantee.ai.tool.AiToolContextKeys;
import com.guarantee.ai.tool.AiToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
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
 * MCP 工具调用器单元测试（不连库、不起 Spring 上下文）。
 *
 * <p>验证三件事：</p>
 * <ol>
 *   <li><b>够不到写能力</b>：写工具与未知名字在本地即被拒，注册表都不会被查询；</li>
 *   <li><b>权限只有一套</b>：可见性完全由 {@code AiToolRegistry.callbacks(permissions)}
 *       决定（无权限 → 不在集合里 → 可读地报"不可用"），MCP 侧没有第二份权限判定；</li>
 *   <li><b>上下文与助手同源</b>：ToolContext 里写入的用户 id / 角色 / 权限 / traceId
 *       与 {@code AiChatService} 同一套键，工具内部的 {@code AiPermissionGuard}
 *       与数据范围判定因此天然一致。</li>
 * </ol>
 */
class McpToolInvokerTest {

    private static final McpPrincipal PRINCIPAL = new McpPrincipal(
            99L,
            "svc-mcp",
            "MCP 服务账号",
            List.of("ai:mcp:read", "system:order:tender:view"),
            List.of("VIEWER"),
            "trace-1",
            null);

    private AiToolRegistry registry;
    private McpToolInvoker invoker;
    private RecordingReadTool readTool;

    @BeforeEach
    void setUp() {
        registry = mock(AiToolRegistry.class);
        invoker = new McpToolInvoker(registry, new ObjectMapper());
        readTool = new RecordingReadTool();
    }

    private void register(ToolCallback... callbacks) {
        when(registry.callbacks(anyList())).thenReturn(callbacks);
    }

    private ToolCallback[] readToolCallbacks() {
        return ToolCallbacks.from(readTool);
    }

    // ==================================================================
    // 正常路径
    // ==================================================================

    @Test
    @DisplayName("正常调用：原样透传工具 JSON，并解析出 dataSource / truncated")
    void invokesRegisteredToolAndPassesJsonThrough() {
        register(readToolCallbacks());

        McpToolInvocation invocation = invoker.invoke("queryOrderSummary", "{\"orderType\":\"TENDER\"}", PRINCIPAL);

        assertThat(invocation.toolName()).isEqualTo("queryOrderSummary");
        assertThat(invocation.resultJson()).as("必须原样透传，不重新序列化").isEqualTo(readTool.response);
        assertThat(invocation.dataSource()).isEqualTo("订单汇总 · 全量");
        assertThat(invocation.truncated()).isTrue();
        assertThat(readTool.capturedInput).isEqualTo("TENDER");
    }

    @Test
    @DisplayName("ToolContext 与助手同源：用户 id / 角色 / 权限 / traceId 全部就位")
    void toolContextMirrorsAssistantContext() {
        register(readToolCallbacks());

        invoker.invoke("queryOrderSummary", "{}", PRINCIPAL);

        ToolContext context = readTool.capturedContext;
        assertThat(context).as("工具必须拿到 ToolContext，否则权限/范围判定会全线失败").isNotNull();
        assertThat(AiPermissionGuard.userId(context)).isEqualTo(99L);
        assertThat(AiPermissionGuard.username(context)).isEqualTo("svc-mcp");
        assertThat(AiPermissionGuard.realName(context)).isEqualTo("MCP 服务账号");
        assertThat(AiPermissionGuard.permissions(context))
                .containsExactly("ai:mcp:read", "system:order:tender:view");
        assertThat(AiPermissionGuard.roles(context)).containsExactly("VIEWER");
        assertThat(AiPermissionGuard.traceId(context)).isEqualTo("trace-1");
        assertThat(AiPermissionGuard.conversationId(context))
                .as("MCP 没有会话，不得写入 null（ToolContext 不允许 null 值）")
                .isNull();
        assertThat(context.getContext()).doesNotContainKey(AiToolContextKeys.CONVERSATION_ID);
    }

    @Test
    @DisplayName("可见性完全交给注册裁剪：传给注册表的就是 Token 里的权限快照")
    void visibilityIsDelegatedToRegistryTrimming() {
        register(readToolCallbacks());

        invoker.invoke("queryOrderSummary", "{}", PRINCIPAL);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> permissions = ArgumentCaptor.forClass(List.class);
        verify(registry).callbacks(permissions.capture());
        assertThat(permissions.getValue())
                .as("MCP 不自己判权限：把快照交给同一套注册裁剪（无权限 = 不可见）")
                .containsExactlyElementsOf(PRINCIPAL.permissions());
    }

    @Test
    @DisplayName("主体为 null 时 fail-closed：入口权限缺失 → PERMISSION_REQUIRED，工具绝不执行")
    void nullPrincipalIsFailClosed() {
        // 刻意不 stub 注册表：本用例要证明的是"根本没走到注册表"
        register(readToolCallbacks());

        assertMcpError(() -> invoker.invoke("queryOrderSummary", "{}", null),
                McpErrorCode.PERMISSION_REQUIRED);

        verifyNoInteractions(registry);
        assertThat(readTool.capturedContext).as("工具绝不能被执行").isNull();
    }

    // ==================================================================
    // 只读边界
    // ==================================================================

    @Test
    @DisplayName("写工具在本地即被拒：连注册表都不查询（不可能被越权调用）")
    void writeToolsAreRejectedBeforeTouchingRegistry() {
        // 刻意不 stub 注册表：本用例要证明的是"根本没走到注册表"
        assertMcpError(() -> invoker.invoke("proposeOrgChange", "{\"action\":\"DISABLE\"}", PRINCIPAL),
                McpErrorCode.TOOL_NOT_ALLOWED);
        assertMcpError(() -> invoker.invoke("queryOrderSummary; DROP TABLE ai_tool_call", "{}", PRINCIPAL),
                McpErrorCode.TOOL_NOT_ALLOWED);

        verifyNoInteractions(registry);
        assertThat(readTool.capturedContext).as("工具绝不能被执行").isNull();
    }

    @Test
    @DisplayName("工具不在当前权限快照的注册集里 → TOOL_UNAVAILABLE（fail-closed，不降级放行）")
    void unregisteredToolIsReportedAsUnavailable() {
        register();

        assertMcpError(() -> invoker.invoke("queryUser", "{}", PRINCIPAL), McpErrorCode.TOOL_UNAVAILABLE);
        assertThat(readTool.capturedContext).isNull();
    }

    // ==================================================================
    // 参数
    // ==================================================================

    @Test
    @DisplayName("参数不是 JSON 对象时先拒绝，不执行工具")
    void nonObjectArgumentsAreRejected() {
        register(readToolCallbacks());

        assertMcpError(() -> invoker.invoke("queryOrderSummary", "[1,2,3]", PRINCIPAL),
                McpErrorCode.INVALID_ARGUMENT);
        assertMcpError(() -> invoker.invoke("queryOrderSummary", "\"TENDER\"", PRINCIPAL),
                McpErrorCode.INVALID_ARGUMENT);
        assertMcpError(() -> invoker.invoke("queryOrderSummary", "{not json}", PRINCIPAL),
                McpErrorCode.INVALID_ARGUMENT);

        assertThat(readTool.capturedContext).isNull();
    }

    @Test
    @DisplayName("参数为空/空白 → 传空对象 {}（不因缺参数直接失败）")
    void blankArgumentsBecomeEmptyObject() {
        register(readToolCallbacks());

        McpToolInvocation invocation = invoker.invoke("queryOrderSummary", "   ", PRINCIPAL);

        assertThat(invocation.resultJson()).isEqualTo(readTool.response);
        assertThat(readTool.capturedInput).as("空对象里没有 orderType").isNull();
    }

    // ==================================================================
    // 结果与失败
    // ==================================================================

    @Test
    @DisplayName("口径在 meta 里时也能取到（知识类工具的形状是 {items, meta}）")
    void extractsDataSourceFromMeta() {
        register(readToolCallbacks());
        readTool.response = "{\"items\":[],\"meta\":{\"dataSource\":\"机构查询 · 本省\",\"truncated\":true}}";

        McpToolInvocation invocation = invoker.invoke("queryOrderSummary", "{}", PRINCIPAL);

        assertThat(invocation.dataSource()).isEqualTo("机构查询 · 本省");
        assertThat(invocation.truncated()).isTrue();
    }

    @Test
    @DisplayName("根级口径优先于 meta（同一份语义只认一处，避免两处不一致时静默取错）")
    void rootDataSourceWins() {
        register(readToolCallbacks());
        readTool.response = "{\"dataSource\":\"根口径\",\"meta\":{\"dataSource\":\"元口径\",\"truncated\":false}}";

        McpToolInvocation invocation = invoker.invoke("queryOrderSummary", "{}", PRINCIPAL);

        assertThat(invocation.dataSource()).isEqualTo("根口径");
    }

    @Test
    @DisplayName("纯文本结果脱掉 Spring AI 包上的那层 JSON 引号（模型不该看到转义引号）")
    void plainTextResultIsUnwrappedFromJsonString() {
        register(readToolCallbacks());
        readTool.response = "纯文本结果";

        McpToolInvocation invocation = invoker.invoke("queryOrderSummary", "{}", PRINCIPAL);

        // Spring AI 会把 String 返回值序列化成 "\"纯文本结果\""，这里必须还原为纯文本
        assertThat(invocation.resultJson()).isEqualTo("纯文本结果");
        assertThat(invocation.dataSource()).isNull();
        assertThat(invocation.truncated()).isFalse();
    }

    @Test
    @DisplayName("JSON 对象结果逐字原样透传（不重新序列化、字段顺序都不变）")
    void jsonObjectResultIsPassedThroughVerbatim() {
        register(readToolCallbacks());
        readTool.response =
                "{\"total\":3,\"items\":[{\"region\":\"浙江\"}],\"dataSource\":\"订单汇总 · 全量\",\"truncated\":false}";

        McpToolInvocation invocation = invoker.invoke("queryOrderSummary", "{}", PRINCIPAL);

        assertThat(invocation.resultJson()).isEqualTo(readTool.response);
        assertThat(invocation.resultJson()).startsWith("{").endsWith("}");
        assertThat(invocation.dataSource()).isEqualTo("订单汇总 · 全量");
        assertThat(invocation.truncated()).isFalse();
    }

    @Test
    @DisplayName("工具抛异常 → 翻译成可读 TOOL_FAILED，不回显堆栈类名")
    void toolFailureBecomesReadableError() {
        register(readToolCallbacks());
        readTool.failure = new IllegalStateException("数据库连接失败");

        McpException ex = catchThrowableOfType(
                () -> invoker.invoke("queryOrderSummary", "{}", PRINCIPAL), McpException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(McpErrorCode.TOOL_FAILED);
        assertThat(ex.getMessage()).contains("queryOrderSummary").contains("数据库连接失败");
        assertThat(ex.getMessage()).doesNotContain("IllegalStateException");
    }

    // ==================================================================
    // 测试替身
    // ==================================================================

    private static void assertMcpError(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
                                       McpErrorCode expected) {
        McpException ex = catchThrowableOfType(callable, McpException.class);
        assertThat(ex).as("必须抛 McpException（%s）", expected).isNotNull();
        assertThat(ex.getCode()).isEqualTo(expected);
    }

    /**
     * 用**真实 Spring AI 工具注解**包装的只读工具替身。
     *
     * <p>故意不用手写的假 {@code ToolCallback}：只有走真实的
     * {@code ToolCallbacks.from} → {@code MethodToolCallback} 路径，才能证明
     * "ToolContext 真的能到达工具方法"，以及"权限快照真的能被工具读到"。</p>
     */
    public static final class RecordingReadTool {

        ToolContext capturedContext;
        String capturedInput;
        String response = "{\"dataSource\":\"订单汇总 · 全量\",\"truncated\":true,\"total\":3}";
        RuntimeException failure;

        @Tool(name = "queryOrderSummary", description = "测试用只读订单汇总工具")
        public String queryOrderSummary(
                @ToolParam(description = "订单类型：TENDER/PERFORMANCE/ALL", required = false) String orderType,
                ToolContext context) {
            this.capturedInput = orderType;
            this.capturedContext = context;
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }
}
