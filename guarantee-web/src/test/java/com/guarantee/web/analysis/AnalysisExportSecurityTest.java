package com.guarantee.web.analysis;

import com.guarantee.analysis.dto.OrderTrendQuery;
import com.guarantee.analysis.service.OrderReportService;
import com.guarantee.analysis.vo.OrderComparisonVO;
import com.guarantee.analysis.vo.OrderReportVO;
import com.guarantee.system.service.WebAuditor;
import com.guarantee.web.controller.AnalysisExportController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringJUnitConfig(AnalysisExportSecurityTest.Config.class)
class AnalysisExportSecurityTest {
    @Configuration @EnableMethodSecurity
    static class Config {
        @Bean OrderReportService reports() { return mock(OrderReportService.class); }
        @Bean WebAuditor auditor() { return mock(WebAuditor.class); }
        @Bean AnalysisExportController controller(OrderReportService reports, WebAuditor auditor) {
            return new AnalysisExportController(reports, auditor);
        }
    }
    @Autowired AnalysisExportController controller;
    @Autowired OrderReportService reports;
    @Autowired WebAuditor auditor;
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); reset(reports, auditor); }

    @Test void directExportNeedsBothPermissionsAndDoesNotQueryWhenDenied() {
        for (String[] permissions : new String[][]{{}, {"analysis:overview:view"}, {"analysis:export"}}) {
            authenticate(permissions);
            assertThatThrownBy(() -> controller.export(new OrderTrendQuery()))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        }
        verifyNoInteractions(reports, auditor);
    }

    @Test void authorizedExportHasCsvAndAuditsConditionsWithoutFileBody() {
        authenticate("analysis:overview:view", "analysis:export");
        var q = new OrderTrendQuery();
        q.setOrderType("TENDER"); q.setStartDate(LocalDate.parse("2024-02-01")); q.setEndDate(LocalDate.parse("2024-02-29"));
        var metric = new OrderComparisonVO.Metric(BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, null, "NEW");
        var comparison = new OrderComparisonVO(q, q.getStartDate().minusDays(29), q.getStartDate().minusDays(1),
                q.getStartDate(), q.getEndDate(), false, false, metric, metric, metric);
        when(reports.report(q)).thenReturn(new OrderReportVO(OffsetDateTime.now(), comparison, List.of(), List.of(), List.of(), List.of()));
        var response = controller.export(q);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8)).startsWith("\ufeff").contains("2024-02-29");
        verify(auditor).success(eq("EXPORT"), eq("ANALYSIS_REPORT"), isNull(), eq("运营分析摘要"), isNull(), argThat(snapshot ->
                snapshot.containsKey("orderCount") && snapshot.containsKey("resultRows") && !snapshot.containsKey("body")));
    }

    private void authenticate(String... permissions) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("analyst", "unused",
                Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));
    }
}
