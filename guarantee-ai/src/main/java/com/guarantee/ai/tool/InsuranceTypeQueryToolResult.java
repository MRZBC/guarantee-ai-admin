package com.guarantee.ai.tool;

import java.util.List;

/**
 * {@code queryInsuranceType} 的工具返回值（SYS-Q-05 / SYS-Q-07）。
 *
 * <p>{@code baseRate} 与 {@code baseRatePercent} 同时给出，避免模型自行乘 100 时算错。</p>
 */
public record InsuranceTypeQueryToolResult(
        long total,
        List<InsuranceItem> items,
        ToolResultMeta meta) {

    /** 单条险种。 */
    public record InsuranceItem(
            Long id,
            String typeCode,
            String typeName,
            String category,
            String categoryName,
            /** 小数费率，例如 0.008000。 */
            String baseRate,
            /** 百分比表示，例如 0.8（单位 %）。 */
            String baseRatePercent,
            String minAmount,
            String maxAmount,
            Integer status,
            String statusName,
            String description,
            /** 是否已逻辑删除：1=已删除（仅 includeDeleted=true 时可能出现），0/null=未删除。 */
            Integer isDeleted,
            /** 删除时间（ISO-8601 字符串）；未删除时为 null。 */
            String deletedAt) {
    }

    public static InsuranceTypeQueryToolResult denied(String reason) {
        return new InsuranceTypeQueryToolResult(0L, List.of(), ToolResultMeta.denied(reason));
    }
}
