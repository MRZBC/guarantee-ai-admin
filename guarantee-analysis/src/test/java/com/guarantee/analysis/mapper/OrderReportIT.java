package com.guarantee.analysis.mapper;

import com.guarantee.analysis.AnalysisItConfig;
import com.guarantee.analysis.dto.OrderTrendQuery;
import com.guarantee.analysis.service.OrderReportCsv;
import com.guarantee.analysis.service.OrderReportService;
import com.guarantee.order.dto.TenderOrderQuery;
import com.guarantee.order.dto.PerformanceOrderQuery;
import com.guarantee.order.mapper.TenderOrderMapper;
import com.guarantee.order.mapper.PerformanceOrderMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** 事务内自建边界样本，回滚后不改变共享演示数据。 */
@SpringBootTest(classes = {AnalysisItConfig.class, OrderReportIT.OrderMappers.class})
@Transactional
class OrderReportIT {
    @Configuration(proxyBeanMethods = false)
    @MapperScan("com.guarantee.order.mapper")
    static class OrderMappers { }
    @Autowired JdbcTemplate jdbc;
    @Autowired OrderReportService reports;
    @Autowired TenderOrderMapper tender;
    @Autowired PerformanceOrderMapper performance;

    @ParameterizedTest @ValueSource(strings = {"TENDER", "PERFORMANCE"})
    void reportMatchesIndependentSqlAndAllThreeDrilldowns(String type) {
        String table = type.equals("TENDER") ? "tender_order" : "performance_order";
        long org = 9000031L;
        for (int i = 0; i < 5; i++) {
            boolean perf = type.equals("PERFORMANCE");
            jdbc.update("INSERT INTO " + table + " (order_no," + (perf ? "contract_no," : "")
                    + "project_id,enterprise_id,insurance_type_id,org_id,region_code,region_name,guarantee_amount,premium_amount,premium_rate,status,apply_date,is_deleted) VALUES (?,"
                    + (perf ? "'OA-CONTRACT'," : "") + "1,1,1,?,?,?,100.25,1.25,0.01,'EFFECTIVE',?,?)",
                    "OA-" + UUID.randomUUID(), org, i == 3 ? "110100" : "110000", "测试地区",
                    new String[]{"2024-02-27", "2024-02-28", "2024-02-29", "2024-03-01", "2024-03-01"}[i], i == 4 ? 1 : 0);
        }
        var q = new OrderTrendQuery();
        q.setOrderType(type); q.setOrgId(org); q.setRegionCode("110000");
        q.setStartDate(LocalDate.parse("2024-02-29")); q.setEndDate(LocalDate.parse("2024-03-01"));
        var report = reports.report(q);
        BigDecimal sum = jdbc.queryForObject("SELECT SUM(guarantee_amount) FROM " + table
                + " WHERE org_id=? AND is_deleted=0 AND apply_date BETWEEN '2024-02-29' AND '2024-03-01'", BigDecimal.class, org);
        assertThat(report.comparison().guaranteeAmount().current()).isEqualByComparingTo(sum);
        assertThat(report.comparison().orderCount().current()).isEqualByComparingTo("2");
        assertThat(report.comparison().orderCount().previous()).isEqualByComparingTo("2");
        assertThat(report.comparison().comparisonComplete()).isTrue();
        report.regions().forEach(r -> assertThat(count(type, org, r.getRegionCode(), null)).isEqualTo(r.getOrderCount()));
        report.insurances().forEach(r -> assertThat(count(type, org, null, r.getInsuranceTypeId())).isEqualTo(r.getOrderCount()));
        report.institutions().forEach(r -> assertThat(count(type, r.getOrgId(), r.getRegionCode(), null)).isEqualTo(r.getOrderCount()));
        String csv = OrderReportCsv.render(report);
        assertThat(csv).startsWith("\ufeff").contains("2024-02-29", "2024-03-01", "200.50", "Top 10，非全量", "Asia/Shanghai");
        assertThat(report.trend().stream().mapToLong(r -> r.getOrderCount()).sum()).isEqualTo(2);
    }

    private long count(String type, long org, String region, Long insurance) {
        if (type.equals("TENDER")) {
            var q = new TenderOrderQuery();
            q.setOrgId(org); q.setRegionCode(region); q.setRegionExact(true); q.setInsuranceTypeId(insurance);
            q.setStartDate(LocalDate.parse("2024-02-29")); q.setEndDate(LocalDate.parse("2024-03-01"));
            return tender.countByQuery(q);
        }
        var q = new PerformanceOrderQuery();
        q.setOrgId(org); q.setRegionCode(region); q.setRegionExact(true); q.setInsuranceTypeId(insurance);
        q.setStartDate(LocalDate.parse("2024-02-29")); q.setEndDate(LocalDate.parse("2024-03-01"));
        return performance.countByQuery(q);
    }
}
