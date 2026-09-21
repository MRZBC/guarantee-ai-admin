package com.guarantee.analysis.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * 跨表分析查询条件（数据概览趋势 / 地区 / 险种 / 机构共用）。
 *
 * <p>使用 POJO 而非 record：MyBatis 需要通过标准 getter 解析 OGNL 表达式，
 * 且 Spring MVC 需要通过 setter 绑定 query string 参数。</p>
 */
@Data
public class AnalysisCriteria {

    /** TENDER 投标 / PERFORMANCE 履约 / ALL 全部，默认 ALL。 */
    private String orderType = OrderType.ALL;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startDate;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;

    /** 行政区划编码，可选。 */
    private String regionCode;

    /** 承保机构ID，可选。 */
    private Long orgId;

    /** 归一化后的订单类型，非法值退回 ALL。 */
    public String normalizedOrderType() {
        return OrderType.normalize(orderType);
    }

    /**
     * 是否纳入投标订单分支。Mapper 中通过 {@code c.includeTender()} 调用。
     * 方法名不是 getter 命名规范，Jackson 不会序列化，也不会被请求参数绑定。
     */
    public boolean includeTender() {
        String type = normalizedOrderType();
        return OrderType.TENDER.equals(type) || OrderType.ALL.equals(type);
    }

    /** 是否纳入履约订单分支（orderType=ALL 时两个分支都走 UNION ALL）。 */
    public boolean includePerformance() {
        String type = normalizedOrderType();
        return OrderType.PERFORMANCE.equals(type) || OrderType.ALL.equals(type);
    }

    /** 订单类型常量，归一化口径与 guarantee-order 的 OrderSummaryCriteria 保持一致。 */
    public static final class OrderType {
        public static final String TENDER = "TENDER";
        public static final String PERFORMANCE = "PERFORMANCE";
        public static final String ALL = "ALL";

        private OrderType() {
        }

        public static String normalize(String raw) {
            if (raw == null || raw.isBlank()) {
                return ALL;
            }
            String v = raw.trim().toUpperCase();
            return switch (v) {
                case TENDER, "投标", "投标保函", "TENDER_ORDER" -> TENDER;
                case PERFORMANCE, "履约", "履约保函", "PERFORMANCE_ORDER" -> PERFORMANCE;
                default -> ALL;
            };
        }
    }
}
