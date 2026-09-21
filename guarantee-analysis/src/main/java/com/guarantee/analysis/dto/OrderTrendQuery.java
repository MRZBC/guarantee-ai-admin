package com.guarantee.analysis.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 订单趋势查询条件：在共享条件（orderType/日期/regionCode/orgId）之上增加粒度。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OrderTrendQuery extends AnalysisCriteria {

    /** month（默认，yyyy-MM） / day（yyyy-MM-dd）。 */
    private String granularity = Granularity.MONTH;

    /** 归一化粒度，Service 先归一再查询，避免非法值进入 SQL。 */
    public String normalizedGranularity() {
        return Granularity.normalize(granularity);
    }

    /** 趋势粒度常量与归一化。 */
    public static final class Granularity {
        public static final String MONTH = "month";
        public static final String DAY = "day";

        private Granularity() {
        }

        public static String normalize(String raw) {
            if (raw == null || raw.isBlank()) {
                return MONTH;
            }
            return DAY.equalsIgnoreCase(raw.trim()) ? DAY : MONTH;
        }
    }
}
