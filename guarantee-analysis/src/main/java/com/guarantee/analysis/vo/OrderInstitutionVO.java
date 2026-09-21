package com.guarantee.analysis.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单承保机构分布。
 */
@Data
public class OrderInstitutionVO {

    private Long orgId;
    private String orgCode;
    private String orgName;
    private String regionCode;
    private String regionName;
    private long orderCount;
    private BigDecimal guaranteeAmount = BigDecimal.ZERO;
    private BigDecimal premiumAmount = BigDecimal.ZERO;
    /** 该机构去重企业数 */
    private long enterpriseCount;
}
