package com.guarantee.analysis.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 企业详情视图对象：列表字段 + 项目/订单细分指标。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class EnterpriseDetailVO extends EnterpriseVO {

    /** 该企业关联项目去重数 */
    private long projectCount;
    private long tenderOrderCount;
    private long performanceOrderCount;
    /** 保费合计（投标 + 履约） */
    private BigDecimal totalPremiumAmount = BigDecimal.ZERO;
}
