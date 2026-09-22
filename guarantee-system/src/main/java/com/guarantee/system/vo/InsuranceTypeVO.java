package com.guarantee.system.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 险种配置响应对象。实体不直接对外暴露。
 *
 * <p>{@code baseRatePercent} 与 {@code baseRate} 同源同现（SYS-Q-05 口径）：
 * 同时给出小数（0.008000）与百分比（0.8%），避免模型自行换算时出错。</p>
 */
public record InsuranceTypeVO(
        Long id,
        String typeCode,
        String typeName,
        String category,
        String categoryName,
        BigDecimal baseRate,
        BigDecimal baseRatePercent,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        Integer status,
        String description,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        /** 逻辑删除 0正常 1已删除（LD-01） */
        Integer isDeleted,
        /** 删除时间（DATETIME(6)，未删除为 null） */
        LocalDateTime deletedAt,
        /** 删除人标识：应用删除记为 sys_user.id 的字符串，数据库直连删除为 "DB"（设计 §2.1a） */
        String deletedBy) {
}
