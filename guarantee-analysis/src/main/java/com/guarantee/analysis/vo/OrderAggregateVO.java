package com.guarantee.analysis.vo;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class OrderAggregateVO {
    private long orderCount;
    private BigDecimal guaranteeAmount = BigDecimal.ZERO;
    private BigDecimal premiumAmount = BigDecimal.ZERO;
    private LocalDate dataStartDate;
    private LocalDate dataEndDate;
}
