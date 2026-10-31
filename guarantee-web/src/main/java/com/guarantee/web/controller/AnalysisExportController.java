package com.guarantee.web.controller;

import com.guarantee.analysis.dto.OrderTrendQuery;
import com.guarantee.analysis.service.OrderReportCsv;
import com.guarantee.analysis.service.OrderReportService;
import com.guarantee.common.security.Permissions;
import com.guarantee.system.service.WebAuditor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** 放在组合层以复用分析与审计，避免模块间反向依赖。 */
@RestController
public class AnalysisExportController {
    private final OrderReportService reports;
    private final WebAuditor auditor;

    public AnalysisExportController(OrderReportService reports, WebAuditor auditor) {
        this.reports = reports;
        this.auditor = auditor;
    }

    @GetMapping("/api/analysis/order-report/export")
    @PreAuthorize("hasAuthority('" + Permissions.ANALYSIS_EXPORT + "') and hasAuthority('analysis:overview:view')")
    public ResponseEntity<byte[]> export(OrderTrendQuery query) {
        var report = reports.report(query);
        byte[] bytes = OrderReportCsv.render(report).getBytes(StandardCharsets.UTF_8);
        var c = report.comparison().criteria();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("orderType", c.getOrderType());
        snapshot.put("startDate", c.getStartDate().toString());
        snapshot.put("endDate", c.getEndDate().toString());
        snapshot.put("regionCode", c.getRegionCode());
        snapshot.put("orgId", c.getOrgId());
        snapshot.put("orderCount", report.comparison().orderCount().current());
        snapshot.put("resultRows", report.regions().size() + report.insurances().size() + report.institutions().size());
        auditor.success("EXPORT", "ANALYSIS_REPORT", null, "运营分析摘要", null, snapshot);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=analysis-report.csv")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8)).body(bytes);
    }
}
