package com.guarantee.ai.time;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TimeSemanticParser} 单元测试。
 *
 * <p>基准日固定为 2026-09-21，保证断言与运行时间无关。</p>
 */
class TimeSemanticParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 21);

    private final TimeSemanticParser parser = new TimeSemanticParser();

    private TimeRange range(String text) {
        Optional<TimeRange> parsed = parser.parse(text, TODAY);
        assertThat(parsed).as("应能解析: %s", text).isPresent();
        return parsed.get();
    }

    @Test
    @DisplayName("题目中的演示问题：2026年第三季度")
    void shouldParseAbsoluteYearQuarter() {
        TimeRange range = range("2026年第三季度投标订单有多少？");

        assertThat(range.startDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(range.endDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(range.description()).contains("2026年第3季度");
        assertThat(range.isValid()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "2026年第1季度, 2026-01-01, 2026-03-31",
            "2026年第2季度, 2026-04-01, 2026-06-30",
            "2026年第4季度, 2026-10-01, 2026-12-31",
            "2026年3季度,  2026-07-01, 2026-09-30",
            "2026Q3,       2026-07-01, 2026-09-30",
    })
    void shouldParseQuarterVariants(String text, String start, String end) {
        TimeRange range = range(text);
        assertThat(range.startDate()).isEqualTo(LocalDate.parse(start));
        assertThat(range.endDate()).isEqualTo(LocalDate.parse(end));
    }

    @Test
    @DisplayName("今年 / 去年")
    void shouldParseRelativeYear() {
        assertThat(range("今年的订单").startDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(range("今年的订单").endDate()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(range("去年订单量").startDate()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(range("去年订单量").endDate()).isEqualTo(LocalDate.of(2025, 12, 31));
    }

    @Test
    @DisplayName("本季度 / 上季度（跨年回退到上一年 Q4）")
    void shouldParseRelativeQuarter() {
        assertThat(range("本季度").startDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(range("本季度").endDate()).isEqualTo(LocalDate.of(2026, 9, 30));

        assertThat(range("上季度").startDate()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(range("上季度").endDate()).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    @Test
    @DisplayName("Q1 基准日落在 Q1 时，上季度应回退到上一年 Q4")
    void shouldRollBackQuarterAcrossYearBoundary() {
        TimeRange range = parser.parse("上季度", LocalDate.of(2026, 2, 10)).orElseThrow();
        assertThat(range.startDate()).isEqualTo(LocalDate.of(2025, 10, 1));
        assertThat(range.endDate()).isEqualTo(LocalDate.of(2025, 12, 31));
    }

    @Test
    @DisplayName("本月 / 上月")
    void shouldParseRelativeMonth() {
        assertThat(range("本月").startDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(range("本月").endDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(range("上个月").startDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(range("上个月").endDate()).isEqualTo(LocalDate.of(2026, 8, 31));
    }

    @Test
    @DisplayName("今天 / 昨天")
    void shouldParseRelativeDay() {
        assertThat(range("今天").startDate()).isEqualTo(TODAY);
        assertThat(range("今天").endDate()).isEqualTo(TODAY);
        assertThat(range("昨天").startDate()).isEqualTo(LocalDate.of(2026, 9, 20));
    }

    @Test
    @DisplayName("最近三个月应转换为明确区间")
    void shouldParseRecentMonths() {
        TimeRange range = range("最近三个月订单趋势");
        assertThat(range.startDate()).isEqualTo(LocalDate.of(2026, 6, 22));
        assertThat(range.endDate()).isEqualTo(TODAY);
        assertThat(range.description()).contains("最近3个月");
    }

    @Test
    @DisplayName("纯 Q3 默认取当年")
    void shouldDefaultToCurrentYearForBareQuarter() {
        TimeRange range = range("Q3 的投标订单");
        assertThat(range.startDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(range.endDate()).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    @DisplayName("2026年7月 与 2026年")
    void shouldParseAbsoluteMonthAndYear() {
        assertThat(range("2026年7月").startDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(range("2026年7月").endDate()).isEqualTo(LocalDate.of(2026, 7, 31));
        assertThat(range("2026年的情况").startDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(range("2026年的情况").endDate()).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    @DisplayName("无法识别时间时返回空，交由模型调用 getCurrentDate")
    void shouldReturnEmptyWhenNoTimeExpression() {
        assertThat(parser.parse("一共有多少家企业？", TODAY)).isEmpty();
        assertThat(parser.parse("", TODAY)).isEmpty();
        assertThat(parser.parse(null, TODAY)).isEmpty();
    }

    @Test
    @DisplayName("中文数字解析")
    void shouldParseChineseNumbers() {
        assertThat(TimeSemanticParser.parseChineseNumber("三")).isEqualTo(3);
        assertThat(TimeSemanticParser.parseChineseNumber("十")).isEqualTo(10);
        assertThat(TimeSemanticParser.parseChineseNumber("十二")).isEqualTo(12);
        assertThat(TimeSemanticParser.parseChineseNumber("二十")).isEqualTo(20);
        assertThat(TimeSemanticParser.parseChineseNumber("6")).isEqualTo(6);
    }
}
