package com.guarantee.ai.tool;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@code queryOrderDistribution} 的工具返回值（SYS-Q-07：工具返回值必须是 record）。
 *
 * <p>它存在的理由：原来只有 {@link OrderSummaryTool}（单个汇总值），
 * 于是"哪个区域/机构/险种贡献最大、变化最多"这类问题模型只能逐个维度硬查
 * （实测 36 次 {@code queryOrderSummary}，把工具轮次用满后一个结论都没给出），
 * 或者像现场那次一样如实回答"我给不出"。</p>
 */
public record OrderDistributionToolResult(
        /** 归一化后的订单类型：TENDER / PERFORMANCE / ALL */
        String orderType,
        /** 归一化后的维度：REGION（区域）/ ORG（机构）/ INSURANCE（险种） */
        String dimension,
        String startDate,
        String endDate,
        /** 该维度的明细，按订单量倒序；最多 limit 条 */
        List<DistributionItem> items,
        ToolResultMeta meta) {

    /**
     * 一条维度明细。
     *
     * <p>{@code code} / {@code name} 的含义随 {@code dimension} 变化：
     * 区域 = 行政区划码 / 省名；机构 = 机构编码 / 机构名；险种 = 险种编码 / 险种名。</p>
     */
    public record DistributionItem(
            /** 排名，从 1 开始（模型直接引用它比"第一条"更不容易错位） */
            int rank,
            String code,
            String name,
            long orderCount,
            BigDecimal guaranteeAmount,
            BigDecimal premiumAmount,
            /** 去重企业数：区域/机构维度有意义，险种维度为 null */
            Long enterpriseCount) {
    }
}
