package com.guarantee.analysis.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 企业列表视图对象：基础信息 + 跨两张订单表的订单指标。
 */
@Data
public class EnterpriseVO {

    private Long id;
    private String entCode;
    private String entName;
    private String creditCode;
    private String regionCode;
    private String regionName;
    private String industry;
    /** AAA/AA/A/BBB */
    private String entLevel;
    private String contactName;
    private String contactPhone;
    /** 1启用 0停用 */
    private Integer status;
    /** 订单量（投标 + 履约） */
    private long orderCount;
    /** 保函金额合计（投标 + 履约） */
    private BigDecimal totalGuaranteeAmount = BigDecimal.ZERO;
}
