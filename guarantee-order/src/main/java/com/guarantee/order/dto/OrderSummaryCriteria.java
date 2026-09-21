package com.guarantee.order.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * 订单汇总查询条件。
 *
 * <p>这是 AI Tool {@code queryOrderSummary} 与业务统计共用的入参对象。
 * 使用 POJO（而非 record）是为了让 MyBatis 能通过标准 getter 解析 OGNL 表达式。</p>
 */
@Data
public class OrderSummaryCriteria {

    /** TENDER 投标 / PERFORMANCE 履约 / ALL 全部，默认 ALL。 */
    private String orderType = OrderType.ALL;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startDate;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;

    private String regionCode;

    private Long orgId;

    /** 归一化后的订单类型，非法值退回 ALL。 */
    public String normalizedOrderType() {
        return OrderType.normalize(orderType);
    }

    public boolean includeTender() {
        String type = normalizedOrderType();
        return OrderType.TENDER.equals(type) || OrderType.ALL.equals(type);
    }

    public boolean includePerformance() {
        String type = normalizedOrderType();
        return OrderType.PERFORMANCE.equals(type) || OrderType.ALL.equals(type);
    }

    /** 订单类型常量。 */
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
