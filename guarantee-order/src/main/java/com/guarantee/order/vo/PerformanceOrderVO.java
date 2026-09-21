package com.guarantee.order.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 履约订单视图对象：由 MyBatis 关联查询直接映射填充。
 *
 * <p>连接字段使用 {@code AS camelCase} 别名 + 全局 map-underscore-to-camel-case，
 * statusName 由 Service 在 Java 侧翻译补齐。相比投标订单多合同编号。</p>
 */
@Data
public class PerformanceOrderVO {

    private Long id;
    private String orderNo;
    /** 合同编号 */
    private String contractNo;
    private Long projectId;
    private String projectName;
    private Long enterpriseId;
    private String enterpriseName;
    private Long insuranceTypeId;
    private String insuranceTypeName;
    /** TENDER 投标 / PERFORMANCE 履约 / OTHER */
    private String insuranceTypeCategory;
    /** 承保机构 */
    private Long orgId;
    private String orgName;
    private String regionCode;
    private String regionName;
    /** 保函金额 */
    private BigDecimal guaranteeAmount;
    /** 保费 */
    private BigDecimal premiumAmount;
    /** 费率 */
    private BigDecimal premiumRate;
    /** DRAFT/UNDER_REVIEW/EFFECTIVE/EXPIRED/RELEASED */
    private String status;
    /** 状态中文名，Java 侧翻译 */
    private String statusName;
    private LocalDate applyDate;
    private LocalDate effectiveDate;
    private LocalDate expireDate;
}
