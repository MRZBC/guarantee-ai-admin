package com.guarantee.analysis.service;

import com.guarantee.analysis.dto.OrderTrendQuery;
import com.guarantee.analysis.mapper.OrderAnalysisMapper;
import com.guarantee.analysis.vo.OrderAggregateVO;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrderReportServiceTest {
    private final OrderAnalysisMapper mapper = mock(OrderAnalysisMapper.class);
    private final OrderReportService service = new OrderReportService(mapper, mock(OrderAnalysisService.class));

    @Test void zeroBaselineAndZeroCurrentHaveExplicitMeaning() {
        assertThat(OrderReportService.metric(BigDecimal.TEN, BigDecimal.ZERO).status()).isEqualTo("NEW");
        assertThat(OrderReportService.metric(BigDecimal.TEN, BigDecimal.ZERO).changeRate()).isNull();
        assertThat(OrderReportService.metric(BigDecimal.ZERO, BigDecimal.ZERO).status()).isEqualTo("UNCHANGED");
        assertThat(OrderReportService.metric(BigDecimal.ZERO, BigDecimal.TEN).changeRate()).isEqualByComparingTo("-100");
        assertThat(OrderReportService.metric(BigDecimal.ONE, new BigDecimal("3")).changeRate()).isEqualByComparingTo("-66.67");
    }

    @Test void leapDayAndEqualLengthPreviousPeriod() {
        stub(26, 20);
        var result = service.compare(query("2024-02-29", "2024-03-01"));
        assertThat(result.previousStartDate()).isEqualTo("2024-02-27");
        assertThat(result.previousEndDate()).isEqualTo("2024-02-28");
        assertThat(result.changeAlert()).isTrue();
    }

    @Test void alertUsesRawRatioAndMinimumBaseline() {
        stub(25999, 20000); // 29.995% 显示为 30.00%，但未达到 30%。
        assertThat(service.compare(query("2024-03-01", "2024-03-01")).changeAlert()).isFalse();
        reset(mapper);
        stub(26, 19);
        assertThat(service.compare(query("2024-03-01", "2024-03-01")).changeAlert()).isFalse();
        reset(mapper);
        stub(14, 20);
        assertThat(service.compare(query("2024-03-01", "2024-03-01")).changeAlert()).isTrue();
    }

    @Test void incompleteCoverageSuppressesAlert() {
        stub(100, 20);
        var result = service.compare(query("2024-01-01", "2024-01-02"));
        assertThat(result.comparisonComplete()).isFalse();
        assertThat(result.changeAlert()).isFalse();
    }

    @Test void defaultsUseLatestDataAndDoNotMutateInput() {
        stub(10, 0);
        var input = query(null, null);
        var result = service.compare(input);
        assertThat(result.criteria().getEndDate()).isEqualTo("2024-12-31");
        assertThat(result.criteria().getStartDate()).isEqualTo("2024-12-02");
        assertThat(input.getStartDate()).isNull();
    }

    @Test void emptyDatabaseReturnsEmptyAndIncompleteComparison() {
        when(mapper.selectAggregate(any())).thenReturn(new OrderAggregateVO());
        var result = service.compare(query(null, null));
        assertThat(result.orderCount().current()).isZero();
        assertThat(result.comparisonComplete()).isFalse();
        assertThat(result.orderCount().status()).isEqualTo("UNCHANGED");
    }

    @Test void invalidRequestsNeverQueryDatabase() {
        for (var input : new OrderTrendQuery[]{query("2024-03-02", "2024-03-01"),
                query("2024-01-01", "2025-01-01"), query("2024-01-01", null)}) {
            assertThatThrownBy(() -> service.compare(input)).isInstanceOf(com.guarantee.common.exception.BizException.class);
        }
        var invalid = query(null, null);
        invalid.setOrderType("ALL");
        assertThatThrownBy(() -> service.compare(invalid)).hasMessageContaining("订单类型");
        verifyNoInteractions(mapper);
    }

    private void stub(long now, long before) {
        var coverage = aggregate(100);
        coverage.setDataStartDate(LocalDate.of(2024, 1, 1));
        coverage.setDataEndDate(LocalDate.of(2024, 12, 31));
        when(mapper.selectAggregate(any())).thenReturn(coverage, aggregate(now), aggregate(before));
    }

    private static OrderAggregateVO aggregate(long count) {
        var vo = new OrderAggregateVO();
        vo.setOrderCount(count);
        return vo;
    }

    private static OrderTrendQuery query(String start, String end) {
        var q = new OrderTrendQuery();
        q.setOrderType("TENDER");
        q.setStartDate(start == null ? null : LocalDate.parse(start));
        q.setEndDate(end == null ? null : LocalDate.parse(end));
        return q;
    }
}
