package com.guarantee.ai.mcp;

/**
 * 一次只读工具调用的结果（与网关/平台冻结契约逐字段一致）。
 *
 * @param toolName   工具名（后端名，例如 {@code queryOrderSummary}）
 * @param resultJson 工具返回的 JSON 原文（**不重新序列化**，保证与页面/助手逐字段一致）
 * @param dataSource 口径文本（工具返回值里的 {@code dataSource} / {@code meta.dataSource}）
 * @param truncated  结果是否被截断（工具返回值里的 {@code truncated} / {@code meta.truncated}）
 */
public record McpToolInvocation(String toolName, String resultJson, String dataSource, boolean truncated) {
}
