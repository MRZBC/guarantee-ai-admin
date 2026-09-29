package com.guarantee.ai.metrics;

import lombok.Data;

import java.time.LocalDate;

/**
 * 按天趋势的一个点（REQ-MCP-11 §5.3.4 的"趋势"面板）。
 *
 * <p>只包含库里真实存在的日期：**不补零、不插值**——没有数据的日期不画点，
 * 避免把"没跑过"渲染成"跑了且为 0"（空结果不编造）。</p>
 */
@Data
public class TurnMetricTrendPoint {

    /** 统计日（{@code DATE(created_at)}）。 */
    private LocalDate statDate;
    private Long turns;
    private Long errorTurns;
    private Long cappedTurns;
    private Double avgRounds;
    private Double avgTotalCostMs;
    private Long inputTokens;
    private Long outputTokens;

    public static TurnMetricTrendPoint normalized(TurnMetricTrendPoint raw) {
        TurnMetricTrendPoint point = new TurnMetricTrendPoint();
        if (raw == null) {
            point.statDate = null;
            point.turns = 0L;
            point.errorTurns = 0L;
            point.cappedTurns = 0L;
            point.avgRounds = 0.0;
            point.avgTotalCostMs = 0.0;
            point.inputTokens = 0L;
            point.outputTokens = 0L;
            return point;
        }
        point.statDate = raw.statDate;
        point.turns = raw.turns == null || raw.turns < 0 ? 0L : raw.turns;
        point.errorTurns = raw.errorTurns == null || raw.errorTurns < 0 ? 0L : raw.errorTurns;
        point.cappedTurns = raw.cappedTurns == null || raw.cappedTurns < 0 ? 0L : raw.cappedTurns;
        point.avgRounds = raw.avgRounds == null || raw.avgRounds < 0 ? 0.0 : raw.avgRounds;
        point.avgTotalCostMs = raw.avgTotalCostMs == null || raw.avgTotalCostMs < 0 ? 0.0 : raw.avgTotalCostMs;
        point.inputTokens = raw.inputTokens == null || raw.inputTokens < 0 ? 0L : raw.inputTokens;
        point.outputTokens = raw.outputTokens == null || raw.outputTokens < 0 ? 0L : raw.outputTokens;
        return point;
    }
}
