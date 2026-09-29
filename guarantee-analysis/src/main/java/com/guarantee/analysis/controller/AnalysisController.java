package com.guarantee.analysis.controller;

import com.guarantee.analysis.dto.AnalysisCriteria;
import com.guarantee.analysis.dto.AnalysisLimitQuery;
import com.guarantee.analysis.dto.OrderTrendQuery;
import com.guarantee.analysis.service.OrderAnalysisService;
import com.guarantee.analysis.service.OverviewService;
import com.guarantee.analysis.vo.OrderInstitutionVO;
import com.guarantee.analysis.vo.OrderInsuranceVO;
import com.guarantee.analysis.vo.OrderRegionVO;
import com.guarantee.analysis.vo.OrderTrendVO;
import com.guarantee.analysis.vo.OverviewVO;
import com.guarantee.common.api.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 数据概览接口。
 */
@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    private final OverviewService overviewService;
    private final OrderAnalysisService orderAnalysisService;

    public AnalysisController(OverviewService overviewService, OrderAnalysisService orderAnalysisService) {
        this.overviewService = overviewService;
        this.orderAnalysisService = orderAnalysisService;
    }

    /** 概览指标：订单量、保额保费、企业/项目/机构基数、数据时间范围。 */
    @GetMapping("/overview")
    public Result<OverviewVO> overview() {
        return Result.ok(overviewService.overview());
    }

    /** 订单趋势：granularity=day（yyyy-MM-dd）/ month（yyyy-MM，默认）/ year（yyyy），大小写不敏感。 */
    @GetMapping("/order-trend")
    public Result<List<OrderTrendVO>> orderTrend(@Valid OrderTrendQuery query) {
        return Result.ok(orderAnalysisService.trend(query));
    }

    /** 订单地区分布 TOP N（默认 10）。 */
    @GetMapping("/order-region")
    public Result<List<OrderRegionVO>> orderRegion(@Valid AnalysisLimitQuery query) {
        return Result.ok(orderAnalysisService.regionDistribution(query, query.getLimit()));
    }

    /** 订单险种分布，按订单量倒序。 */
    @GetMapping("/order-insurance")
    public Result<List<OrderInsuranceVO>> orderInsurance(@Valid AnalysisCriteria criteria) {
        return Result.ok(orderAnalysisService.insuranceDistribution(criteria));
    }

    /** 订单承保机构分布 TOP N（默认 20）。 */
    @GetMapping("/order-institution")
    public Result<List<OrderInstitutionVO>> orderInstitution(@Valid AnalysisLimitQuery query) {
        return Result.ok(orderAnalysisService.institutionDistribution(query, query.getLimit()));
    }
}
