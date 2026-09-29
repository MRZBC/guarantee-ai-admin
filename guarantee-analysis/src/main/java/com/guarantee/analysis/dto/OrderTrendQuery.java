package com.guarantee.analysis.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Locale;

/**
 * 订单趋势查询条件：在共享条件（orderType/日期/regionCode/orgId）之上增加粒度。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OrderTrendQuery extends AnalysisCriteria {

    /** day（yyyy-MM-dd） / month（默认，yyyy-MM） / year（yyyy），大小写不敏感。 */
    private String granularity = Granularity.MONTH;

    /** 归一化粒度，Service 先归一再查询，避免非法值进入 SQL。 */
    public String normalizedGranularity() {
        return Granularity.normalize(granularity);
    }

    /**
     * 是否日粒度。Mapper 中通过 {@code c.dayGranularity()} 调用。
     *
     * <p>方法名不满足 getter 命名规范，因此不会被 Jackson 序列化、也不参与请求参数绑定
     * （与 {@link AnalysisCriteria#includeTender()} 同一处理方式）。</p>
     */
    public boolean dayGranularity() {
        return Granularity.DAY.equals(normalizedGranularity());
    }

    /** 是否年粒度。Mapper 中通过 {@code c.yearGranularity()} 调用。 */
    public boolean yearGranularity() {
        return Granularity.YEAR.equals(normalizedGranularity());
    }

    /** 趋势粒度常量与归一化。 */
    public static final class Granularity {
        public static final String MONTH = "month";
        public static final String DAY = "day";
        public static final String YEAR = "year";

        private Granularity() {
        }

        /**
         * 归一化粒度，非法/缺省值退回 {@link #MONTH}。
         *
         * <p><b>大小写不敏感</b>：前端 {@code Granularity} 类型发的是大写
         * {@code DAY/MONTH/YEAR}，早期实现只认小写 {@code day}，
         * 于是「按日 / 按年」都被当成按月——归一化必须在这里做且必须忽略大小写。</p>
         */
        public static String normalize(String raw) {
            if (raw == null || raw.isBlank()) {
                return MONTH;
            }
            String value = raw.trim().toLowerCase(Locale.ROOT);
            if (DAY.equals(value)) {
                return DAY;
            }
            if (YEAR.equals(value)) {
                return YEAR;
            }
            return MONTH;
        }
    }
}
