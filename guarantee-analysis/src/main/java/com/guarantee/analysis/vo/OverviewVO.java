package com.guarantee.analysis.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 数据概览：订单规模、保额保费总量、企业与项目基数、数据时间范围。
 */
@Data
public class OverviewVO {

    /** 投标订单量 */
    private long tenderOrderCount;
    /** 履约订单量 */
    private long performanceOrderCount;
    /** 订单总量（投标 + 履约） */
    private long totalOrderCount;
    /** 投标保函金额合计 */
    private BigDecimal tenderGuaranteeAmount = BigDecimal.ZERO;
    /** 履约保函金额合计 */
    private BigDecimal performanceGuaranteeAmount = BigDecimal.ZERO;
    /** 保函金额合计 */
    private BigDecimal totalGuaranteeAmount = BigDecimal.ZERO;
    /** 保费合计 */
    private BigDecimal totalPremiumAmount = BigDecimal.ZERO;
    /** 企业数（两张订单表去重） */
    private long enterpriseCount;
    /** 项目数（两张订单表去重） */
    private long projectCount;
    /** 机构数（sys_org） */
    private long orgCount;
    /** 生效订单量（status=EFFECTIVE） */
    private long effectiveOrderCount;
    /** 数据起始日期（两表 MIN(apply_date)） */
    private LocalDate dataStartDate;
    /** 数据截止日期（两表 MAX(apply_date)） */
    private LocalDate dataEndDate;
}
