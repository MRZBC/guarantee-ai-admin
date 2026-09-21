package com.guarantee.analysis.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单地区分布。
 */
@Data
public class OrderRegionVO {

    private String regionCode;
    private String regionName;
    private long orderCount;
    private BigDecimal guaranteeAmount = BigDecimal.ZERO;
    private BigDecimal premiumAmount = BigDecimal.ZERO;
    /** 该地区去重企业数 */
    private long enterpriseCount;
}
