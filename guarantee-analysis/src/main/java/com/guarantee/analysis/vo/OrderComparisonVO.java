package com.guarantee.analysis.vo;

import com.guarantee.analysis.dto.OrderTrendQuery;
import java.math.BigDecimal;
import java.time.LocalDate;

public record OrderComparisonVO(OrderTrendQuery criteria, LocalDate previousStartDate,
                                LocalDate previousEndDate, LocalDate dataStartDate,
                                LocalDate dataEndDate, boolean comparisonComplete,
                                boolean changeAlert, Metric orderCount,
                                Metric guaranteeAmount, Metric premiumAmount) {
    public record Metric(BigDecimal current, BigDecimal previous, BigDecimal change,
                         BigDecimal changeRate, String status) { }
}
