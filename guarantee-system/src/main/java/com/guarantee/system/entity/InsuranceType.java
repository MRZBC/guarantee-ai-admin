package com.guarantee.system.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 险种配置。
 */
@Data
public class InsuranceType {

    private Long id;
    private String typeCode;
    private String typeName;
    /** TENDER 投标 / PERFORMANCE 履约 / OTHER */
    private String category;
    private BigDecimal baseRate;
    private BigDecimal minAmount;
    private BigDecimal maxAmount;
    private Integer status;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 逻辑删除 0正常 1已删除（LD-01：所有业务查询默认只看 0） */
    private Integer isDeleted;
    /** 删除时间（DATETIME(6)，未删除为 null；同时是唯一键分量） */
    private LocalDateTime deletedAt;
    /** 删除人：应用写 sys_user.id 字符串，数据库直连删除为 'DB' */
    private String deletedBy;

}
