package com.guarantee.analysis.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单趋势：按 period（yyyy-MM 或 yyyy-MM-dd）聚合。
 */
@Data
public class OrderTrendVO {

    /** 统计周期：月粒度 yyyy-MM，日粒度 yyyy-MM-dd */
    private String period;
    private long orderCount;
    private BigDecimal guaranteeAmount = BigDecimal.ZERO;
    private BigDecimal premiumAmount = BigDecimal.ZERO;
}
