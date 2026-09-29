package com.guarantee.ai.metrics;

import lombok.Data;

/**
 * Top 工具统计（REQ-MCP-11 §5.3.4 的"Top 工具"面板）：调用次数与耗时。
 *
 * <p>数据来自 {@code ai_tool_call}（不是本阶段新表）——那里本来就有 {@code duration_ms}，
 * 只是以前没人聚合。p95 用**最近秩法**（nearest-rank）：升序第 {@code ceil(0.95*n)} 条，
 * 样本少时即最大值，比插值更能反映"最慢的那一次有多慢"。</p>
 */
@Data
public class ToolCallStat {

    private String toolName;
    private Long calls;
    private Double avgDurationMs;
    private Long maxDurationMs;
    /** 第 95 百分位耗时（nearest-rank，ms）。 */
    private Long p95DurationMs;

    public static ToolCallStat normalized(ToolCallStat raw) {
        ToolCallStat stat = new ToolCallStat();
        if (raw == null) {
            stat.calls = 0L;
            stat.avgDurationMs = 0.0;
            stat.maxDurationMs = 0L;
            stat.p95DurationMs = 0L;
            return stat;
        }
        stat.toolName = raw.toolName;
        stat.calls = raw.calls == null || raw.calls < 0 ? 0L : raw.calls;
        stat.avgDurationMs = raw.avgDurationMs == null || raw.avgDurationMs < 0 ? 0.0 : raw.avgDurationMs;
        stat.maxDurationMs = raw.maxDurationMs == null || raw.maxDurationMs < 0 ? 0L : raw.maxDurationMs;
        stat.p95DurationMs = raw.p95DurationMs == null || raw.p95DurationMs < 0 ? 0L : raw.p95DurationMs;
        return stat;
    }
}
