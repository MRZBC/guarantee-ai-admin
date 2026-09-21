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
}
