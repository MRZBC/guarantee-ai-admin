package com.guarantee.web.analysis;

import com.guarantee.ai.analysis.AnalysisSummaryService;
import com.guarantee.analysis.dto.OrderTrendQuery;
import com.guarantee.analysis.service.OrderReportService;
import com.guarantee.web.GuaranteeAiAdminApplication;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 真实聚合与受控模型贯通，不调用付费服务、不写订单或聊天历史。 */
@SpringBootTest(classes = {GuaranteeAiAdminApplication.class, AnalysisSummaryIT.Models.class})
class AnalysisSummaryIT {
    @TestConfiguration static class Models {
        @Bean @Primary ChatModel summaryModel() {
            return new ChatModel() {
                @Override public ChatResponse call(Prompt prompt) {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    assertThat(prompt.getUserMessage().getText()).contains("orders", "guarantee").doesNotContain("drilldown");
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("{\"evidenceIds\":[\"orders\",\"guarantee\",\"premium\"]}"))));
                }
            };
        }
    }
    @Autowired OrderReportService reports;
    @Autowired AnalysisSummaryService summaries;

    @Test void bothOrderTypesMatchRealReportAndControlledPerformanceIsRecorded() throws Exception {
        List<Long> timings = new ArrayList<>();
        for (String type : List.of("TENDER", "PERFORMANCE")) {
            var q = new OrderTrendQuery(); q.setOrderType(type);
            q.setStartDate(LocalDate.of(2026, 9, 1)); q.setEndDate(LocalDate.of(2026, 9, 30));
            var report = reports.report(q);
            assertThat(report.comparison().orderCount().current()).isPositive();
            for (int i = 0; i < 12; i++) {
                long started = System.nanoTime();
                var summary = summaries.generate(9000099L, new AnalysisSummaryService.Request(report.comparison().criteria(), report.getReportFingerprint()));
                long elapsed = (System.nanoTime() - started) / 1_000_000;
                if (i >= 2) timings.add(elapsed);
                assertThat(summary.status()).isEqualTo("GENERATED");
                assertThat(summary.reportFingerprint()).isEqualTo(report.getReportFingerprint());
                assertThat(summary.evidence().getFirst().text()).contains(report.comparison().orderCount().current().stripTrailingZeros().toPlainString() + "笔");
            }
        }
        var sorted = timings.stream().sorted().toList();
        long p95 = sorted.get((int) Math.ceil(sorted.size() * .95) - 1);
        Path output = Path.of("target/eval/analysis-summary-controlled-performance.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, new ObjectMapper().writeValueAsString(Map.of("model", "controlled-stub", "samplesMs", timings, "p95Ms", p95,
                "scope", "single-user local DB, 10 measured + 2 warmups per order type; not real model latency")));
        assertThat(p95).isLessThanOrEqualTo(3000);
    }
}
