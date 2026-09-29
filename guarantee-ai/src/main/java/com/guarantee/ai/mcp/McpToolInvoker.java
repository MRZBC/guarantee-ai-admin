package com.guarantee.ai.mcp;

import com.guarantee.ai.metrics.AiTurnMetric;
import com.guarantee.ai.tool.AiToolContextKeys;
import com.guarantee.ai.tool.AiToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 在既有工具链上执行一次 MCP 只读调用（REQ-MCP-01 / 红线 §2.3-1）。
 *
 * <p><b>它不重新实现取数，也不重新实现权限</b>：</p>
 * <ol>
 *   <li>先过 {@link McpToolCatalog} 白名单——写工具与未知名字在**本地**就被拒，
 *       连注册表都不查；</li>
 *   <li>再用 {@link AiToolRegistry#callbacks(List)} 取"当前权限快照下的注册集"，
 *       名字不在其中即 {@code TOOL_UNAVAILABLE}。这就是页面/助手同一套注册裁剪
 *       （无权限 = 不可见 = 不可调用，fail-closed），MCP 侧**没有**第二份权限判定；</li>
 *   <li>最后把同一套 {@code ToolContext} 键（用户 id / 角色 / 权限 / traceId）写进去，
 *       工具内部的 {@code AiPermissionGuard} 与 {@code DataScopeService} 因此与页面完全同源。</li>
 * </ol>
 *
 * <p>返回值原样透传工具 JSON（不重新序列化），只额外解析出 {@code dataSource} /
 * {@code truncated} 供网关回显，保证与页面/助手逐字段一致（AC-MCP-01）。</p>
 */
@Component
public class McpToolInvoker {

    private static final Logger log = LoggerFactory.getLogger(McpToolInvoker.class);

    /** 错误信息里保留的最大长度（避免把工具内部的大段堆栈/数据回显给外部）。 */
    private static final int MAX_ERROR_TEXT = 240;

    private final AiToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    public McpToolInvoker(AiToolRegistry toolRegistry, ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行一次只读工具调用。
     *
     * <p><b>顺序是有意的</b>：① 入口权限（{@code ai:mcp:read}）→ ② 只读白名单 → ③ 注册裁剪。
     * 入口权限放在最前，是为了让"没有 MCP 入口权限的凭据"连"哪些工具存在"都问不出来
     * （fail-closed 的外部面：不给任何可探测信息）。</p>
     *
     * @param toolName      工具名（后端名，例如 {@code queryOrderSummary}）
     * @param argumentsJson 参数 JSON 对象文本（空/null 视为 {@code {}}）
     * @param principal     Token 校验后得到的主体（null 按"无权限"处理，fail-closed）
     */
    public McpToolInvocation invoke(String toolName, String argumentsJson, McpPrincipal principal) {
        requireMcpRead(principal);
        String name = McpToolCatalog.requireReadOnlyTool(toolName);
        String normalizedArguments = normalizeArguments(name, argumentsJson);
        List<String> permissions = principal == null ? List.of() : principal.permissionsOrEmpty();

        ToolCallback target = findRegisteredTool(name, permissions);
        ToolContext toolContext = new ToolContext(buildToolContext(principal));

        String raw;
        try {
            raw = target.call(normalizedArguments, toolContext);
        } catch (McpException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // 业务工具的内部异常（含 @PreAuthorize 拒绝）统一翻译成可读信息，不回显堆栈
            throw new McpException(McpErrorCode.TOOL_FAILED,
                    "工具 " + name + " 执行失败：" + truncate(safeMessage(ex)), ex);
        }

        McpToolInvocation invocation = describe(name, raw);
        log.info("MCP 工具调用完成 tool={} principal={} truncated={} hasDataSource={}",
                name, principal == null ? "anonymous" : principal.serviceAccountId(),
                invocation.truncated(), invocation.dataSource() != null);
        return invocation;
    }

    // ==================================================================
    // 工具清单（tools/list）
    // ==================================================================

    /**
     * 当前服务账号**可见**的只读工具清单（REQ-MCP-01 / AC-MCP-01/03）。
     *
     * <p>两步裁剪，缺一不可：</p>
     * <ol>
     *   <li>入口权限 {@code ai:mcp:read}——没有它直接返回**空清单**（MCP 是外部面，
     *       入口权限缺失时不是"少几个工具"，而是整个受控取数面拒绝服务）；</li>
     *   <li>与 {@code tools/call} 完全相同的注册裁剪（{@code AiToolRegistry.callbacks(permissions)}）
     *       + 只读白名单。**清单必须是"调用面的子集"**：清单里出现一个调不通的工具，
     *       外部 Agent 会反复重试并把平台故障误判成"工具坏了"（AC-MCP-01 的一致性要求）。</li>
     * </ol>
     *
     * <p>返回值里的 {@code inputSchema} 是 Spring AI {@code ToolDefinition} 的 JSON Schema
     * **原文**，不重新序列化：与页面/助手看到的是同一份定义（避免第二份说明漂移）。</p>
     */
    public List<McpToolDefinition> listTools(McpPrincipal principal) {
        if (!hasMcpRead(principal)) {
            return List.of();
        }
        ToolCallback[] callbacks = toolRegistry.callbacks(principal.permissionsOrEmpty());
        if (callbacks == null || callbacks.length == 0) {
            return List.of();
        }
        List<McpToolDefinition> tools = new ArrayList<>(callbacks.length);
        for (ToolCallback callback : callbacks) {
            ToolDefinition definition = callback.getToolDefinition();
            if (definition == null || definition.name() == null) {
                continue;
            }
            String name = definition.name();
            // 双保险：注册集里若混进写工具（例如未来 registry 调整），MCP 侧也不暴露
            if (!McpToolCatalog.isReadOnlyTool(name)) {
                log.warn("注册集里出现非业务 MCP 只读工具，已从清单剔除：{}", name);
                continue;
            }
            tools.add(new McpToolDefinition(name, definition.description(), definition.inputSchema()));
        }
        log.info("MCP 工具清单：principal={} 可见={} 项",
                principal == null ? "anonymous" : principal.serviceAccountId(), tools.size());
        return List.copyOf(tools);
    }

    /**
     * MCP 入口权限判定：服务账号是否持有 {@code ai:mcp:read}。
     *
     * <p>null 主体按"没有权限"处理（fail-closed）。</p>
     */
    public static boolean hasMcpRead(McpPrincipal principal) {
        return principal != null && principal.permissionsOrEmpty().contains(McpToolCatalog.PERMISSION_MCP_READ);
    }

    /** 入口权限硬校验；缺失时给**可读**拒绝（不是 500，也不泄漏清单）。 */
    private static void requireMcpRead(McpPrincipal principal) {
        if (!hasMcpRead(principal)) {
            throw new McpException(McpErrorCode.PERMISSION_REQUIRED,
                    "服务账号缺少 " + McpToolCatalog.PERMISSION_MCP_READ
                            + " 权限：业务 MCP 只对该权限的持有者开放（清单为空、调用一律拒绝）");
        }
    }

    /**
     * 一个可暴露给外部 Agent 的只读工具定义。
     *
     * @param name            后端工具名（如 {@code queryOrderSummary}）
     * @param description     说明（直接复用既有 {@code @Tool} 描述）
     * @param inputSchemaJson JSON Schema **原文**
     */
    public record McpToolDefinition(String name, String description, String inputSchemaJson) {
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private ToolCallback findRegisteredTool(String toolName, List<String> permissions) {
        ToolCallback[] callbacks = toolRegistry.callbacks(permissions);
        if (callbacks != null) {
            for (ToolCallback callback : callbacks) {
                ToolDefinition definition = callback.getToolDefinition();
                if (definition != null && toolName.equals(definition.name())) {
                    return callback;
                }
            }
        }
        throw new McpException(McpErrorCode.TOOL_UNAVAILABLE,
                "工具 " + toolName + " 对当前服务账号不可用（无权限或未注册）");
    }

    /** 参数必须是 JSON **对象**；不是对象/不是 JSON 都在调用前拒绝，并给出可读原因。 */
    private String normalizeArguments(String toolName, String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return "{}";
        }
        String trimmed = argumentsJson.trim();
        Object parsed;
        try {
            parsed = objectMapper.readValue(trimmed, Object.class);
        } catch (RuntimeException ex) {
            throw new McpException(McpErrorCode.INVALID_ARGUMENT,
                    "工具 " + toolName + " 的参数不是合法 JSON：" + truncate(safeMessage(ex)));
        }
        if (!(parsed instanceof Map)) {
            throw new McpException(McpErrorCode.INVALID_ARGUMENT,
                    "工具 " + toolName + " 的参数必须是 JSON 对象");
        }
        return trimmed;
    }

    /**
     * 构造 ToolContext——与 {@code AiChatService.buildToolContext} 同一套键。
     *
     * <p>注意 Spring AI 的 {@code ToolContext} 不允许 value 为 null，
     * 因此空值键必须**跳过**而不是写入 null。</p>
     */
    private Map<String, Object> buildToolContext(McpPrincipal principal) {
        Map<String, Object> context = new HashMap<>();
        // 来源固定为 MCP：落进 ai_tool_call.source 与 ai.tool.calls{source} 标签（AC-MCP-05）
        context.put(AiToolContextKeys.CALL_SOURCE, AiTurnMetric.SOURCE_MCP);
        if (principal == null) {
            // fail-closed：没有主体就没有权限快照 → 注册裁剪只会留下公开只读工具
            context.put(AiToolContextKeys.PERMISSIONS, List.of());
            context.put(AiToolContextKeys.ROLES, List.of());
            return context;
        }
        putIfNotNull(context, AiToolContextKeys.USER_ID, principal.serviceAccountId());
        putIfNotNull(context, AiToolContextKeys.USERNAME, principal.username());
        putIfNotNull(context, AiToolContextKeys.REAL_NAME, principal.realName());
        putIfNotNull(context, AiToolContextKeys.TRACE_ID, principal.traceId());
        putIfNotNull(context, AiToolContextKeys.CONVERSATION_ID, principal.conversationId());
        context.put(AiToolContextKeys.PERMISSIONS, principal.permissionsOrEmpty());
        context.put(AiToolContextKeys.ROLES, principal.rolesOrEmpty());
        return context;
    }

    private static void putIfNotNull(Map<String, Object> context, String key, Object value) {
        if (value != null) {
            context.put(key, value);
        }
    }

    /**
     * 解析工具返回值里的口径与截断标记。
     *
     * <p><b>为什么纯文本要"脱掉一层 JSON 引号"</b>：Spring AI 的 {@code MethodToolCallback}
     * 会把工具方法的返回值统一 JSON 序列化——返回 {@code "纯文本"} 时，调用方拿到的是
     * {@code "\"纯文本\""}（带转义引号）。若原样交给 MCP，外部 Agent/模型看到的就是一串
     * 带引号的转义文本。因此这里区分两种情况：</p>
     * <ul>
     *   <li>JSON **对象/数组** → 逐字原样透传（保证与页面/助手逐字段一致）；</li>
     *   <li>JSON **字符串字面量**（工具返回纯文本）→ 脱掉这层引号，返回纯文本；</li>
     *   <li>根本不是 JSON → 原样透传（不猜、不包装）。</li>
     * </ul>
     */
    private McpToolInvocation describe(String toolName, String raw) {
        String resultJson = raw == null ? "" : raw;
        if (resultJson.isBlank()) {
            return new McpToolInvocation(toolName, resultJson, null, false);
        }

        Object parsed;
        try {
            parsed = objectMapper.readValue(resultJson, Object.class);
        } catch (RuntimeException ex) {
            // 不是 JSON：原样透传，不伪造口径
            return new McpToolInvocation(toolName, resultJson, null, false);
        }

        if (parsed instanceof String text) {
            // 纯文本被 Spring AI 序列化成了 JSON 字符串字面量：脱掉引号
            return new McpToolInvocation(toolName, text, null, false);
        }

        Map<String, Object> root = asMap(parsed);
        if (root == null) {
            // JSON 数组/数字/布尔等：原样透传（当前工具不会返回这些，保持通用）
            return new McpToolInvocation(toolName, resultJson, null, false);
        }

        String dataSource = stringValue(root.get("dataSource"));
        boolean truncated = booleanValue(root.get("truncated"));
        Map<String, Object> meta = asMap(root.get("meta"));
        if (meta != null) {
            if (dataSource == null) {
                dataSource = stringValue(meta.get("dataSource"));
            }
            if (!truncated) {
                truncated = booleanValue(meta.get("truncated"));
            }
        }
        return new McpToolInvocation(toolName, resultJson, dataSource, truncated);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static String stringValue(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static boolean booleanValue(Object value) {
        return Boolean.TRUE.equals(value);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable == null ? "未知错误" : throwable.getClass().getSimpleName();
        }
        return message;
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= MAX_ERROR_TEXT) {
            return text;
        }
        return text.substring(0, MAX_ERROR_TEXT) + "…";
    }
}
