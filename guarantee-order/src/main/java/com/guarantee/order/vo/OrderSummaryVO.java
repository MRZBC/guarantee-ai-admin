package com.guarantee.order.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单汇总结果：AI Tool {@code queryOrderSummary} 的五个核心指标。
 *
 * <p>使用 POJO 以便 MyBatis 直接映射聚合查询结果。</p>
 */
@Data
public class OrderSummaryVO {

    /** 订单量 */
    private long orderCount;

    /** 保函金额合计（元） */
    private BigDecimal guaranteeAmount = BigDecimal.ZERO;

    /** 保费合计（元） */
    private BigDecimal premiumAmount = BigDecimal.ZERO;

    /** 去重企业数 */
    private long enterpriseCount;

    /** 去重项目数 */
    private long projectCount;
}
