package com.guarantee.ai.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 只读白名单的结构性测试（TEST-MCP-04 的静态部分 / AC-MCP-04）。
 *
 * <p>核心断言只有一条：Java 侧白名单与网关侧
 * {@code tools/business-mcp/src/catalog.ts} 的 {@code backendName} **逐名一致**。
 * 两边各有一份清单是不可避免的（一个跑在 Java、一个跑在 Node），
 * 因此必须由测试把它们钉在一起，而不是靠"改一边记得改另一边"。</p>
 */
class McpToolCatalogTest {

    /** 与 {@code AiToolRegistry.readTools} 的 13 个只读 {@code @Tool} 方法一一对应。 */
    private static final List<String> EXPECTED_TOOLS = List.of(
            "queryOrderSummary",
            "getCurrentDate",
            "queryBusinessKnowledge",
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

    /** Java 侧现有的 5 个写工具（永远不得出现在 MCP 白名单里）。 */
    private static final List<String> WRITE_TOOLS = List.of(
            "proposeOrgChange",
            "proposeDepartmentChange",
            "proposeUserChange",
            "proposeRoleChange",
            "proposeInsuranceTypeChange");

    private static final Path GATEWAY_CATALOG = resolveGatewayCatalog();

    @Test
    @DisplayName("白名单就是这 13 个只读工具（顺序固定，便于逐名核对）")
    void catalogIsExactlyTheTwelveReadOnlyTools() {
        assertThat(McpToolCatalog.readOnlyToolNames()).containsExactlyElementsOf(EXPECTED_TOOLS);
        assertThat(McpToolCatalog.readOnlyToolNames()).hasSize(13);

        for (String tool : EXPECTED_TOOLS) {
            assertThat(McpToolCatalog.isReadOnlyTool(tool)).as("%s 应在白名单内", tool).isTrue();
        }
    }

    @Test
    @DisplayName("与网关清单逐名一致：tools/business-mcp/src/catalog.ts 的 backendName 必须完全相同")
    void catalogMatchesGatewayCatalogFile() throws IOException {
        String source = Files.readString(GATEWAY_CATALOG, StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("backendName:\\s*'([A-Za-z0-9_]+)'").matcher(source);

        List<String> gatewayTools = new ArrayList<>();
        while (matcher.find()) {
            gatewayTools.add(matcher.group(1));
        }

        assertThat(gatewayTools)
                .as("网关清单（%s）与 Java 白名单必须逐名一致", GATEWAY_CATALOG)
                .containsExactlyInAnyOrderElementsOf(McpToolCatalog.readOnlyToolNames());
        assertThat(gatewayTools).as("网关清单个数也必须是 13").hasSize(EXPECTED_TOOLS.size());
    }

    @Test
    @DisplayName("写工具一律拒绝（不允许外部 Agent 够到任何 propose*）")
    void writeToolsAreRejected() {
        for (String writeTool : WRITE_TOOLS) {
            assertThat(McpToolCatalog.isReadOnlyTool(writeTool)).as("%s 不得在白名单内", writeTool).isFalse();
            assertMcpError(() -> McpToolCatalog.requireReadOnlyTool(writeTool), McpErrorCode.TOOL_NOT_ALLOWED);
        }
    }

    @Test
    @DisplayName("未知工具 / 空名字一律拒绝，且拒绝发生在调用之前")
    void unknownToolsAreRejected() {
        assertMcpError(() -> McpToolCatalog.requireReadOnlyTool("dropDatabase"), McpErrorCode.TOOL_NOT_ALLOWED);
        assertMcpError(() -> McpToolCatalog.requireReadOnlyTool("/../../actuator/env"),
                McpErrorCode.TOOL_NOT_ALLOWED);
        assertMcpError(() -> McpToolCatalog.requireReadOnlyTool(""), McpErrorCode.TOOL_NOT_ALLOWED);
        assertMcpError(() -> McpToolCatalog.requireReadOnlyTool("   "), McpErrorCode.TOOL_NOT_ALLOWED);
        assertMcpError(() -> McpToolCatalog.requireReadOnlyTool(null), McpErrorCode.TOOL_NOT_ALLOWED);
    }

    @Test
    @DisplayName("合法名字返回去空白后的原名")
    void validToolNameIsNormalized() {
        assertThat(McpToolCatalog.requireReadOnlyTool("  queryOrg  ")).isEqualTo("queryOrg");
    }

    @Test
    @DisplayName("权限码固定为 ai:mcp:read（task-15 改为引用 Permissions 后仍必须一致）")
    void permissionCodeIsStable() {
        assertThat(McpToolCatalog.PERMISSION_MCP_READ).isEqualTo("ai:mcp:read");
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private static void assertMcpError(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
                                       McpErrorCode expected) {
        McpException ex = catchThrowableOfType(callable, McpException.class);
        assertThat(ex).as("必须抛 McpException（%s）", expected).isNotNull();
        assertThat(ex.getCode()).isEqualTo(expected);
    }

    /**
     * 定位网关清单源文件。
     *
     * <p>surefire 的工作目录是模块目录（guarantee-ai），IDE 里可能是仓库根，
     * 因此给几个候选路径而不是写死一个。</p>
     */
    private static Path resolveGatewayCatalog() {
        List<Path> candidates = new ArrayList<>();
        String basedir = System.getProperty("basedir");
        if (basedir != null && !basedir.isBlank()) {
            candidates.add(Path.of(basedir, "..", "tools", "business-mcp", "src", "catalog.ts"));
        }
        candidates.add(Path.of("..", "tools", "business-mcp", "src", "catalog.ts"));
        candidates.add(Path.of("tools", "business-mcp", "src", "catalog.ts"));
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate.normalize();
            }
        }
        throw new AssertionError("找不到网关清单 catalog.ts，候选路径：" + candidates);
    }
}
