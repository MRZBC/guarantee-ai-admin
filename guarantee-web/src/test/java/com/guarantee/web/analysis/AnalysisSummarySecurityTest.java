package com.guarantee.web.analysis;

import com.guarantee.ai.analysis.AnalysisSummaryService;
import com.guarantee.ai.controller.AnalysisSummaryController;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import com.guarantee.common.security.CurrentUser;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(AnalysisSummarySecurityTest.Config.class)
class AnalysisSummarySecurityTest {
    @Configuration @EnableMethodSecurity static class Config {
        @Bean AnalysisSummaryService summaries() { return mock(AnalysisSummaryService.class); }
        @Bean AnalysisSummaryController controller(AnalysisSummaryService service) { return new AnalysisSummaryController(service); }
    }
    @Autowired AnalysisSummaryService service;
    @Autowired AnalysisSummaryController controller;
    @AfterEach void clear() { SecurityContextHolder.clearContext(); CurrentUser.clear(); reset(service); }
    @Test void eitherPermissionMissingRejectsBeforeService() {
        for (String permission : List.of("ai:chat", "analysis:overview:view")) {
            auth(permission);
            assertThatThrownBy(() -> controller.generate(null)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        }
        verifyNoInteractions(service);
    }
    @Test void changedReportUsesHttpConflictWithoutLosingPayload() {
        auth("ai:chat", "analysis:overview:view");
        CurrentUser.set(new CurrentUser.Principal(8L, "analyst", "分析员", List.of(), List.of()));
        var result = new AnalysisSummaryService.Summary("REPORT_CHANGED", null, "hash", "time", List.of(), List.of(), List.of(), "数据已更新", "trace");
        when(service.generate(8L, null)).thenReturn(result);
        var response = controller.generate(null);
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().data()).isSameAs(result);
    }
    private void auth(String... permissions) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("analyst", "unused",
                Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));
    }
}
