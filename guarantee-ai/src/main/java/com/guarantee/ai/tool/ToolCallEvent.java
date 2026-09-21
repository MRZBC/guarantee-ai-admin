package com.guarantee.ai.tool;

/**
 * 一次 Tool Call 的实时事件，用于 SSE 推送给前端。
 *
 * <p>字段与 {@code ai_tool_call} 表一致，前端展示与落库内容保持同源。</p>
 */
public record ToolCallEvent(
        Long id,
        String toolName,
        String toolType,
        String arguments,
        String result,
        String status,
        Long durationMs,
        String errorMessage) {
}
