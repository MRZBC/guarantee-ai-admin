package com.guarantee.ai.mcp;

import java.util.List;

/**
 * 业务 MCP 的**只读工具白名单**（REQ-MCP-01 / REQ-MCP-04 / AC-MCP-04）。
 *
 * <p>这是 Java 侧的权威清单，与网关侧 {@code tools/business-mcp/src/catalog.ts}
 * 的 12 个 {@code backendName} **逐名一致**（由 {@code McpToolCatalogTest} 读源文件断言，
 * 不靠人工同步）。它同时等于 {@code AiToolRegistry.readTools} 注册的 12 个只读
 * {@code @Tool} 方法（11 个只读类，其中 {@code OrderSummaryTool} 提供两个方法）。</p>
 *
 * <p><b>为什么是白名单而不是"排除 propose*"</b>：写能力清单会长大，黑名单必然滞后；
 * 只有"名字在清单里才放行"才能保证新增写工具默认不会被外部 Agent 调用到
 * （第二道防线是 {@code AiToolRegistry} 的注册裁剪与工具内的 {@code AiPermissionGuard}）。</p>
 *
 * <p>注意：这里的 {@code PERMISSION_MCP_READ} 是**字符串字面量**，而不是引用
 * {@code Permissions.AI_MCP_READ}——新增权限码常量属于 T5-03（task-15）的写入范围
 * （{@code Permissions} / {@code PermissionCatalog} 都是共享热点文件）。task-15 落地后，
 * 本常量应改为引用 {@code Permissions.AI_MCP_READ}，测试会守住两边取值一致。</p>
 */
public final class McpToolCatalog {

    /**
     * MCP 读权限码（REQ-MCP-02）。
     *
     * <p>它属于**危险权限**：拿到它就等于拿到平台的受控取数面。仅授予服务账号，
     * 且要同步登记进危险权限清单（{@code PermissionTree.vue} 与角色管理文档）。</p>
     */
    public static final String PERMISSION_MCP_READ = "ai:mcp:read";

    /** 12 个只读工具（与 {@code AiToolRegistry.readTools} 的方法名一一对应）。 */
    private static final List<String> READ_ONLY_TOOLS = List.of(
            "queryOrderSummary",
            "getCurrentDate",
            "queryOrderDistribution",
            "queryOrderTrend",
            "queryOrg",
            "queryDepartment",
            "queryUser",
            "queryRole",
            "queryInsuranceType",
            "queryOperationAudit",
            "queryMyToolCalls",
            "queryMyProposals");

    private McpToolCatalog() {
    }

    /** 只读工具名（不可变副本）。 */
    public static List<String> readOnlyToolNames() {
        return READ_ONLY_TOOLS;
    }

    public static boolean isReadOnlyTool(String toolName) {
        return toolName != null && READ_ONLY_TOOLS.contains(toolName.trim());
    }

    /**
     * 校验并返回规范化后的工具名；不在白名单（含所有 {@code propose*} 与未知名字）即拒绝。
     *
     * <p>拒绝发生在**发出任何调用之前**：外部 Agent 无论怎么写名字都够不到写能力，
     * 也不会因为"名字打错"而把请求打到平台内部。</p>
     */
    public static String requireReadOnlyTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            throw new McpException(McpErrorCode.TOOL_NOT_ALLOWED, "必须指定工具名");
        }
        String normalized = toolName.trim();
        if (!READ_ONLY_TOOLS.contains(normalized)) {
            throw new McpException(McpErrorCode.TOOL_NOT_ALLOWED,
                    "工具 " + normalized + " 不是业务 MCP 暴露的只读工具（写能力只能在平台页面内确认执行）");
        }
        return normalized;
    }
}
