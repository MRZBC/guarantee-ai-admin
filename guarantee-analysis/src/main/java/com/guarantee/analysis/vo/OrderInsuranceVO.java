package com.guarantee.analysis.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单险种分布。
 */
@Data
public class OrderInsuranceVO {

    private Long insuranceTypeId;
    private String typeCode;
    private String typeName;
    /** TENDER 投标 / PERFORMANCE 履约 / OTHER */
    private String category;
    private long orderCount;
    private BigDecimal guaranteeAmount = BigDecimal.ZERO;
    private BigDecimal premiumAmount = BigDecimal.ZERO;
}
