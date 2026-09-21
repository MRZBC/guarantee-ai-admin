package com.guarantee.analysis.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 项目详情视图对象：列表字段 + 该项目的订单指标。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ProjectDetailVO extends ProjectVO {

    private long tenderOrderCount;
    private long performanceOrderCount;
    private BigDecimal totalGuaranteeAmount = BigDecimal.ZERO;
    private BigDecimal totalPremiumAmount = BigDecimal.ZERO;
}
