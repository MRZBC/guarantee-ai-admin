package com.guarantee.ai.tool;

import java.math.BigDecimal;

/**
 * {@code queryOrderSummary} 的工具返回值。
 *
 * <p>回显查询条件与数据来源，便于模型在回答中说明口径，也便于人工核对。</p>
 */
public record OrderSummaryToolResult(
        String orderType,
        String startDate,
        String endDate,
        String regionCode,
        Long orgId,
        long orderCount,
        BigDecimal guaranteeAmount,
        BigDecimal premiumAmount,
        long enterpriseCount,
        long projectCount,
        String dataSource) {
}
