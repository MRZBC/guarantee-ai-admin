package com.guarantee.ai.tool;

import java.util.List;

/**
 * {@code queryMyToolCalls} 的工具返回值（SYS-Q-06a / SYS-Q-07）。
 *
 * <p><b>不含 arguments / result 原文</b>，只有 {@code resultSummary}。
 * 这是 D-4/SYS-A-09 的落点之一：ANALYST 对自己的会话有完整查询权，
 * 若自查接口返回工具入参原文，写操作里的手机号就会从这条路径泄漏。</p>
 */
public record MyToolCallsToolResult(
        long total,
        List<MyToolCallItem> items,
        ToolResultMeta meta) {

    /** 单条自查记录。 */
    public record MyToolCallItem(
            Long id,
            Long conversationId,
            String toolName,
            String toolType,
            String status,
            Long durationMs,
            String createdAt,
            /** 例如"命中 12 条记录"。 */
            String resultSummary) {
    }

    public static MyToolCallsToolResult denied(String reason) {
        return new MyToolCallsToolResult(0L, List.of(), ToolResultMeta.denied(reason));
    }
}
