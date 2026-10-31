package com.guarantee.analysis.service;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class OrderReportCsvTest {
    @Test void protectsSpreadsheetFormulaTextsWithoutChangingNumericValues() {
        for (String value : new String[]{"=1+1", " +SUM(A1)", "@evil", "-1+2", "\t=1", "\r=1", "\n=1"}) {
            assertThat(OrderReportCsv.cell(value)).startsWith("\"'");
        }
        assertThat(OrderReportCsv.cell(new BigDecimal("-30.5"))).isEqualTo("\"-30.5\"");
        assertThat(OrderReportCsv.cell("中文,\"引号\"\n下一行")).isEqualTo("\"中文,\"\"引号\"\"\n下一行\"");
    }
}
