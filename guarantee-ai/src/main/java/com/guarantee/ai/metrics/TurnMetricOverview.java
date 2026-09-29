package com.guarantee.ai.metrics;

import lombok.Data;

/**
 * 概览卡数据（REQ-MCP-11 / §5.3.4）：近 24h / 7d 的问答数、失败率、触顶率、平均轮次、平均耗时、token 合计。
 *
 * <p>这是 Mapper 聚合查询的结果载体，由 {@link TurnMetricService} 做空值归一：
 * <b>没有数据时返回全 0，绝不用 {@code null} 或"估计值"冒充</b>（空结果不编造）。</p>
 */
@Data
public class TurnMetricOverview {

    /** 问答数（一行 = 一次问答，含失败与触顶）。 */
    private Long turns;
    /** 失败数（outcome = ERROR）。 */
    private Long errorTurns;
    /** 触顶数（capped = 1）。 */
    private Long cappedTurns;
    /** 失败率 = errorTurns / turns；无数据时为 0。 */
    private Double errorRate;
    /** 触顶率 = cappedTurns / turns；无数据时为 0。 */
    private Double cappedRate;
    /** 平均轮次。 */
    private Double avgRounds;
    /** 平均端到端耗时（ms）。 */
    private Double avgTotalCostMs;
    /** 输入 token 合计（真实 usage）。 */
    private Long inputTokens;
    /** 输出 token 合计（真实 usage）。 */
    private Long outputTokens;

    /** 空结果：全部为 0（"没有问答"和"问答很少"必须能区分于 null）。 */
    public static TurnMetricOverview empty() {
        TurnMetricOverview overview = new TurnMetricOverview();
        overview.turns = 0L;
        overview.errorTurns = 0L;
        overview.cappedTurns = 0L;
        overview.errorRate = 0.0;
        overview.cappedRate = 0.0;
        overview.avgRounds = 0.0;
        overview.avgTotalCostMs = 0.0;
        overview.inputTokens = 0L;
        overview.outputTokens = 0L;
        return overview;
    }

    /** 把 Mapper 可能返回的 {@code null}（无行、SUM/AVG 为 NULL）归一成 0，并算出两个比率。 */
    public static TurnMetricOverview normalized(TurnMetricOverview raw) {
        if (raw == null) {
            return empty();
        }
        TurnMetricOverview result = new TurnMetricOverview();
        result.turns = nonNegative(raw.turns);
        result.errorTurns = nonNegative(raw.errorTurns);
        result.cappedTurns = nonNegative(raw.cappedTurns);
        result.avgRounds = nonNegative(raw.avgRounds);
        result.avgTotalCostMs = nonNegative(raw.avgTotalCostMs);
        result.inputTokens = nonNegative(raw.inputTokens);
        result.outputTokens = nonNegative(raw.outputTokens);
        result.errorRate = rate(result.errorTurns, result.turns);
        result.cappedRate = rate(result.cappedTurns, result.turns);
        return result;
    }

    private static long nonNegative(Long value) {
        return value == null || value < 0 ? 0L : value;
    }

    private static double nonNegative(Double value) {
        return value == null || value.isNaN() || value < 0 ? 0.0 : value;
    }

    private static double rate(long part, long total) {
        if (total <= 0) {
            return 0.0;
        }
        return (double) part / (double) total;
    }
}
