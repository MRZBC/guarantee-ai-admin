package com.guarantee.ai.time;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 中文时间语义解析器。
 *
 * <p>把用户自然语言里的相对/绝对时间表达统一转换成 {@link TimeRange}。
 * 该组件是纯函数式的：{@code today} 始终显式传入（或取系统当天），因此可以稳定单测。</p>
 *
 * <p>支持：今天、昨天、前天、本月、上月、本季度、上季度、今年、去年、
 * Q1~Q4、2026年第三季度、2026年7月、最近N天/周/月。</p>
 */
@Component
public class TimeSemanticParser {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 支持“2026年第三季度 / 2026年第3季度 / 2026Q3 / 2026年3季度”。 */
    private static final Pattern ABS_YEAR_QUARTER = Pattern.compile(
            "(\\d{4})\\s*年?\\s*(?:第\\s*)?([一二三四1234])\\s*季度"
                    + "|(\\d{4})\\s*[年\\-]?\\s*[Qq]([1-4])");

    /** 支持“2026年7月”。 */
    private static final Pattern ABS_YEAR_MONTH = Pattern.compile("(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月");

    /** 支持“2026年”。 */
    private static final Pattern ABS_YEAR = Pattern.compile("(\\d{4})\\s*年(?!\\s*\\d)");

    /** 支持“第三季度 / 第3季度 / 3季度”。 */
    private static final Pattern BARE_QUARTER = Pattern.compile("第?\\s*([一二三四1234])\\s*季度");

    /** 支持“Q3 / q3”。 */
    private static final Pattern BARE_Q = Pattern.compile("(?<![\\dA-Za-z])[Qq]([1-4])(?![\\dA-Za-z])");

    /** 支持“最近N天/日、最近N周/个星期、最近N个月/月”。 */
    private static final Pattern RECENT = Pattern.compile(
            "(?:最近|近|过去|前)\\s*([一二三四五六七八九十\\d]+)\\s*(天|日|周|个?星期|个?月)");

    /**
     * 解析入口，使用系统当天作为基准日。
     */
    public Optional<TimeRange> parse(String text) {
        return parse(text, LocalDate.now());
    }

    /**
     * 解析入口。
     *
     * @param text 用户输入，例如“2026年第三季度投标订单有多少？”
     * @param today 基准日（便于测试）
     * @return 命中则返回明确区间，未命中返回 {@link Optional#empty()}
     */
    public Optional<TimeRange> parse(String text, LocalDate today) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String s = text.trim();

        // 顺序很关键：先匹配最具体的表达，再退化到更宽泛的。
        // 1) 绝对 年 + 季度
        Matcher m = ABS_YEAR_QUARTER.matcher(s);
        if (m.find()) {
            int year = Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(3));
            int quarter = m.group(2) != null ? quarterOf(m.group(2)) : Integer.parseInt(m.group(4));
            return Optional.of(quarterRange(year, quarter));
        }

        // 2) 绝对 年 + 月
        m = ABS_YEAR_MONTH.matcher(s);
        if (m.find()) {
            int year = Integer.parseInt(m.group(1));
            int month = Integer.parseInt(m.group(2));
            if (month >= 1 && month <= 12) {
                return Optional.of(monthRange(year, month));
            }
        }

        // 3) 相对“上季度”要在“本季度”之前判断，二者不重叠但语义需成对出现
        if (containsAny(s, "上个季度", "上季度", "上一季度")) {
            int year = today.getYear();
            int prevQuarter = quarterOf(today) - 1;
            if (prevQuarter == 0) {
                prevQuarter = 4;
                year -= 1;
            }
            return Optional.of(quarterRange(year, prevQuarter));
        }
        if (containsAny(s, "本季度", "当季度", "这个季度", "这季度")) {
            int q = quarterOf(today);
            return Optional.of(quarterRange(today.getYear(), q));
        }

        // 4) 相对 月
        if (containsAny(s, "上个月", "上月")) {
            LocalDate target = today.minusMonths(1);
            return Optional.of(monthRange(target.getYear(), target.getMonthValue()));
        }
        if (containsAny(s, "本月", "这个月", "当月")) {
            return Optional.of(monthRange(today.getYear(), today.getMonthValue()));
        }

        // 5) 最近 N 天/周/月
        m = RECENT.matcher(s);
        if (m.find()) {
            int n = parseChineseNumber(m.group(1));
            if (n > 0) {
                String unit = m.group(2);
                if (unit.startsWith("天") || unit.startsWith("日")) {
                    return Optional.of(new TimeRange(today.minusDays(n - 1L), today,
                            "最近" + n + "天 (" + fmt(today.minusDays(n - 1L)) + " ~ " + fmt(today) + ")"));
                }
                if (unit.startsWith("周") || unit.contains("星期")) {
                    long days = n * 7L;
                    return Optional.of(new TimeRange(today.minusDays(days - 1), today,
                            "最近" + n + "周 (" + fmt(today.minusDays(days - 1)) + " ~ " + fmt(today) + ")"));
                }
                // 月
                LocalDate start = today.minusMonths(n).plusDays(1);
                return Optional.of(new TimeRange(start, today,
                        "最近" + n + "个月 (" + fmt(start) + " ~ " + fmt(today) + ")"));
            }
        }

        // 6) 今天 / 昨天 / 前天
        if (containsAny(s, "今天", "今日")) {
            return Optional.of(new TimeRange(today, today, "今天 (" + fmt(today) + ")"));
        }
        if (containsAny(s, "昨天", "昨日")) {
            LocalDate d = today.minusDays(1);
            return Optional.of(new TimeRange(d, d, "昨天 (" + fmt(d) + ")"));
        }
        if (containsAny(s, "前天", "前日")) {
            LocalDate d = today.minusDays(2);
            return Optional.of(new TimeRange(d, d, "前天 (" + fmt(d) + ")"));
        }

        // 7) 相对 年
        if (containsAny(s, "去年", "上一年", "上年")) {
            int y = today.getYear() - 1;
            return Optional.of(yearRange(y));
        }
        if (containsAny(s, "今年", "本年度", "当年")) {
            return Optional.of(yearRange(today.getYear()));
        }

        // 8) 纯季度（默认当年）
        m = BARE_QUARTER.matcher(s);
        if (m.find()) {
            return Optional.of(quarterRange(today.getYear(), quarterOf(m.group(1))));
        }
        m = BARE_Q.matcher(s);
        if (m.find()) {
            return Optional.of(quarterRange(today.getYear(), Integer.parseInt(m.group(1))));
        }

        // 9) 绝对年（最宽泛，放最后）
        m = ABS_YEAR.matcher(s);
        if (m.find()) {
            return Optional.of(yearRange(Integer.parseInt(m.group(1))));
        }

        return Optional.empty();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static TimeRange yearRange(int year) {
        return new TimeRange(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31),
                year + "年全年 (" + year + "-01-01 ~ " + year + "-12-31)");
    }

    private static TimeRange monthRange(int year, int month) {
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        return new TimeRange(start, end,
                year + "年" + month + "月 (" + fmt(start) + " ~ " + fmt(end) + ")");
    }

    private static TimeRange quarterRange(int year, int quarter) {
        int startMonth = (quarter - 1) * 3 + 1;
        LocalDate start = LocalDate.of(year, startMonth, 1);
        LocalDate end = start.plusMonths(2).withDayOfMonth(start.plusMonths(2).lengthOfMonth());
        return new TimeRange(start, end,
                year + "年第" + quarter + "季度 (" + fmt(start) + " ~ " + fmt(end) + ")");
    }

    private static int quarterOf(LocalDate date) {
        return (date.getMonthValue() - 1) / 3 + 1;
    }

    private static int quarterOf(String raw) {
        return switch (raw) {
            case "一" -> 1;
            case "二" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            default -> Integer.parseInt(raw);
        };
    }

    private static String fmt(LocalDate date) {
        return date.format(DATE_FMT);
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String k : keywords) {
            if (text.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /** 支持 1~99 的中文数字，够用即可（“十二个月”等）。 */
    static int parseChineseNumber(String raw) {
        if (raw == null || raw.isEmpty()) {
            return -1;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(v);
        }
        List<String> digits = List.of("零", "一", "二", "三", "四", "五", "六", "七", "八", "九");
        if ("十".equals(v)) {
            return 10;
        }
        int idx = v.indexOf('十');
        if (idx >= 0) {
            int tens = idx == 0 ? 1 : digits.indexOf(String.valueOf(v.charAt(0)));
            int ones = idx == v.length() - 1 ? 0 : digits.indexOf(String.valueOf(v.charAt(v.length() - 1)));
            if (tens < 0 || ones < 0) {
                return -1;
            }
            return tens * 10 + ones;
        }
        int single = digits.indexOf(v);
        return single >= 0 ? single : -1;
    }
}
