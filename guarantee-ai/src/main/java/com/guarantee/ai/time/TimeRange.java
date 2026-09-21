package com.guarantee.ai.time;

import java.time.LocalDate;

/**
 * 解析后的明确时间区间。
 *
 * <p>AI 层永远不把“本季度”“Q3”这类模糊语义传给业务 Tool，
 * 必须先由 {@link TimeSemanticParser} 转换成明确日期。</p>
 *
 * @param startDate   起始日期（含）
 * @param endDate     结束日期（含）
 * @param description 人类可读描述，会展示给用户作为“数据来源”的一部分
 */
public record TimeRange(LocalDate startDate, LocalDate endDate, String description) {

    public static TimeRange of(LocalDate startDate, LocalDate endDate, String description) {
        return new TimeRange(startDate, endDate, description);
    }

    public boolean isValid() {
        return startDate != null && endDate != null && !startDate.isAfter(endDate);
    }
}
