package com.guarantee.order.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 履约订单。
 *
 * <p>字段与 schema.sql 的 performance_order 表一一对应，仅用于持久层，
 * 不对外暴露（Controller 只返回 VO）。相比投标订单多一个合同编号。</p>
 */
@Data
public class PerformanceOrder {

    private Long id;
    private String orderNo;
    /** 合同编号 */
    private String contractNo;
    private Long projectId;
    private Long enterpriseId;
    private Long insuranceTypeId;
    /** 承保机构 */
    private Long orgId;
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
    /** 申请日期（分析主时间维度） */
    private LocalDate applyDate;
    private LocalDate effectiveDate;
    private LocalDate expireDate;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 逻辑删除 0正常 1已删除（LD-01：所有业务查询默认只看 0） */
    private Integer isDeleted;
    /** 删除时间（DATETIME(6)，未删除为 null；同时是唯一键分量） */
    private LocalDateTime deletedAt;
    /** 删除人：应用写 sys_user.id 字符串，数据库直连删除为 'DB' */
    private String deletedBy;

}
