package com.guarantee.analysis.vo;

import java.time.OffsetDateTime;
import java.util.List;

public record OrderReportVO(OffsetDateTime generatedAt, OrderComparisonVO comparison,
                            List<OrderTrendVO> trend, List<OrderRegionVO> regions,
                            List<OrderInsuranceVO> insurances,
                            List<OrderInstitutionVO> institutions) {
    public String getReportFingerprint() {
        return com.guarantee.analysis.service.ReportFingerprint.of(this);
    }
}
