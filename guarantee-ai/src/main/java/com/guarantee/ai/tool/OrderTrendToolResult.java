package com.guarantee.ai.tool;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@code queryOrderTrend} 的工具返回值（SYS-Q-07：工具返回值必须是 record）。
 *
 * <p><b>它补的是什么</b>：分布工具回答"谁贡献最大"，汇总工具回答"这段时间总共多少"，
 * 但"保费逐月怎么走、拐点在哪"两者都答不了——模型只能拿两个区间的总量相减，
 * 得出的不是趋势。本 record 承载按等距周期排好的序列，让"趋势"成为一次取数就能回答的问题。</p>
 *
 * <p>{@code points} 按 {@code period} **升序**（与 {@code OrderAnalysisService.trend} 一致），
 * 只包含**最近 limit 个周期**；被截断时 {@link #meta()} 里带 {@code truncated} 与提示。</p>
 */
public record OrderTrendToolResult(
        /** 归一化后的订单类型：TENDER / PERFORMANCE / ALL */
        String orderType,
        /** 归一化后的粒度（小写）：day / month / year */
        String granularity,
        String startDate,
        String endDate,
        /** 回显的区域编码（未过滤时为 null） */
        String regionCode,
        /** 回显的承保机构 ID（未过滤时为 null） */
        Long orgId,
        /** 时间升序的周期序列；最多 limit 个，且是**最近**的若干个周期 */
        List<TrendPoint> points,
        ToolResultMeta meta) {

    /**
     * 一个统计周期。
     *
     * <p>{@code period} 的格式由粒度决定：按日 {@code yyyy-MM-dd} / 按月 {@code yyyy-MM} /
     * 按年 {@code yyyy}。</p>
     */
    public record TrendPoint(
            String period,
            long orderCount,
            BigDecimal guaranteeAmount,
            BigDecimal premiumAmount) {
    }
}
