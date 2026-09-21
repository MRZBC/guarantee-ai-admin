package com.guarantee.system.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 险种配置响应对象。实体不直接对外暴露。
 */
public record InsuranceTypeVO(
        Long id,
        String typeCode,
        String typeName,
        String category,
        String categoryName,
        BigDecimal baseRate,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        Integer status,
        String description,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
