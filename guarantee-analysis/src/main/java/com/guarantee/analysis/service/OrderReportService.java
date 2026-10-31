package com.guarantee.analysis.service;

import com.guarantee.analysis.dto.OrderTrendQuery;
import com.guarantee.analysis.mapper.OrderAnalysisMapper;
import com.guarantee.analysis.vo.*;
import com.guarantee.common.exception.BizException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/** 页面与导出共用同一统计口径；一份报告内使用可重复读快照。 */
@Service
public class OrderReportService {
    static final int MIN_BASELINE_ORDERS = 20;
    static final BigDecimal ALERT_PERCENT = new BigDecimal("30");
    private final OrderAnalysisMapper mapper;
    private final OrderAnalysisService analysis;

    public OrderReportService(OrderAnalysisMapper mapper, OrderAnalysisService analysis) {
        this.mapper = mapper;
        this.analysis = analysis;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OrderComparisonVO compare(OrderTrendQuery input) {
        return comparison(input);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OrderReportVO report(OrderTrendQuery input) {
        OrderComparisonVO comparison = comparison(input);
        OrderTrendQuery c = comparison.criteria();
        return new OrderReportVO(OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), comparison,
                analysis.trend(c), analysis.regionDistribution(c, 10),
                analysis.insuranceDistribution(c), analysis.institutionDistribution(c, 10));
    }

    private OrderComparisonVO comparison(OrderTrendQuery input) {
        // 严格验证新入口；不改变旧分析接口的兼容行为。
        OrderTrendQuery c = copy(input);
        String type = c.getOrderType() == null ? "" : c.getOrderType().trim().toUpperCase(Locale.ROOT);
        if (!type.equals("TENDER") && !type.equals("PERFORMANCE")) {
            throw BizException.badRequest("订单类型必须为 TENDER 或 PERFORMANCE");
        }
        c.setOrderType(type);
        if (c.getRegionCode() != null && !c.getRegionCode().matches("[0-9]{6}")) {
            throw BizException.badRequest("地区编码必须为六位数字");
        }
        if (c.getOrgId() != null && c.getOrgId() <= 0) throw BizException.badRequest("机构编号无效");
        if ((c.getStartDate() == null) != (c.getEndDate() == null)) {
            throw BizException.badRequest("开始日期与结束日期须同时填写");
        }
        if (c.getStartDate() != null) validateDates(c);
        OrderTrendQuery coverageQuery = copy(c);
        coverageQuery.setStartDate(null);
        coverageQuery.setEndDate(null);
        OrderAggregateVO coverage = mapper.selectAggregate(coverageQuery);
        if (c.getStartDate() == null) {
            LocalDate end = coverage.getDataEndDate() == null ? LocalDate.now(ZoneId.of("Asia/Shanghai")) : coverage.getDataEndDate();
            LocalDate start = end.minusDays(29);
            if (coverage.getDataStartDate() != null && start.isBefore(coverage.getDataStartDate())) start = coverage.getDataStartDate();
            c.setStartDate(start);
            c.setEndDate(end);
        }
        validateDates(c);
        long days = ChronoUnit.DAYS.between(c.getStartDate(), c.getEndDate()) + 1;
        OrderTrendQuery previous = copy(c);
        previous.setEndDate(c.getStartDate().minusDays(1));
        previous.setStartDate(c.getStartDate().minusDays(days));
        OrderAggregateVO now = mapper.selectAggregate(c);
        OrderAggregateVO before = mapper.selectAggregate(previous);
        boolean complete = coverage.getDataStartDate() != null
                && !previous.getStartDate().isBefore(coverage.getDataStartDate())
                && !c.getEndDate().isAfter(coverage.getDataEndDate());
        BigDecimal delta = BigDecimal.valueOf(now.getOrderCount() - before.getOrderCount());
        // 用未舍入的交叉乘法判断阈值，避免 29.995% 被显示舍入误触发。
        boolean alert = complete && before.getOrderCount() >= MIN_BASELINE_ORDERS
                && delta.abs().multiply(BigDecimal.valueOf(100))
                .compareTo(BigDecimal.valueOf(before.getOrderCount()).multiply(ALERT_PERCENT)) >= 0;
        return new OrderComparisonVO(c, previous.getStartDate(), previous.getEndDate(),
                coverage.getDataStartDate(), coverage.getDataEndDate(), complete, alert,
                metric(BigDecimal.valueOf(now.getOrderCount()), BigDecimal.valueOf(before.getOrderCount())),
                metric(now.getGuaranteeAmount(), before.getGuaranteeAmount()),
                metric(now.getPremiumAmount(), before.getPremiumAmount()));
    }

    private static void validateDates(OrderTrendQuery c) {
        if (c.getStartDate().getYear() < 1001 || c.getEndDate().getYear() > 9999
                || c.getEndDate().isBefore(c.getStartDate())
                || ChronoUnit.DAYS.between(c.getStartDate(), c.getEndDate()) >= 366) {
            throw BizException.badRequest("统计区间须为 1–366 天，年份须在 1001–9999 之间");
        }
    }

    static OrderComparisonVO.Metric metric(BigDecimal now, BigDecimal previous) {
        BigDecimal change = now.subtract(previous);
        BigDecimal rate = previous.signum() == 0 ? null
                : change.multiply(BigDecimal.valueOf(100)).divide(previous, 2, RoundingMode.HALF_UP);
        String status = change.signum() == 0 ? "UNCHANGED"
                : previous.signum() == 0 ? "NEW" : change.signum() > 0 ? "UP" : "DOWN";
        return new OrderComparisonVO.Metric(now, previous, change, rate, status);
    }

    private static OrderTrendQuery copy(OrderTrendQuery input) {
        OrderTrendQuery c = new OrderTrendQuery();
        c.setOrderType(input.getOrderType());
        c.setStartDate(input.getStartDate());
        c.setEndDate(input.getEndDate());
        c.setRegionCode(input.getRegionCode());
        c.setOrgId(input.getOrgId());
        c.setGranularity(input.normalizedGranularity());
        return c;
    }
}
