package com.guarantee.analysis.service;

import com.guarantee.analysis.vo.OrderComparisonVO;
import com.guarantee.analysis.vo.OrderReportVO;
import java.util.Arrays;
import java.util.stream.Collectors;

/** 聚合摘要格式化，不接触订单明细。 */
public final class OrderReportCsv {
    private OrderReportCsv() { }

    public static String render(OrderReportVO report) {
        StringBuilder csv = new StringBuilder("\ufeff");
        var c = report.comparison();
        row(csv, "运营分析摘要", "导出时查询结果");
        row(csv, "生成时间（Asia/Shanghai）", report.generatedAt());
        row(csv, "订单类型", c.criteria().getOrderType());
        row(csv, "本期（申请日期，含首尾）", c.criteria().getStartDate(), c.criteria().getEndDate());
        row(csv, "上期", c.previousStartDate(), c.previousEndDate());
        row(csv, "地区编码（层级匹配）", c.criteria().getRegionCode(), "机构编号", c.criteria().getOrgId());
        row(csv, "比较数据", c.comparisonComplete() ? "覆盖完整" : "对比数据可能不完整");
        row(csv, "指标", "单位", "本期", "上期", "绝对变化", "变化率（%）", "状态");
        metric(csv, "订单数", "笔", c.orderCount());
        metric(csv, "担保金额", "元", c.guaranteeAmount());
        metric(csv, "保费金额", "元", c.premiumAmount());
        row(csv, "全量订单数", c.orderCount().current());
        row(csv, "地区分布（Top 10，非全量）", "地区编码", "订单数（笔）", "担保金额（元）", "保费金额（元）");
        report.regions().forEach(r -> row(csv, r.getRegionName(), r.getRegionCode(), r.getOrderCount(), r.getGuaranteeAmount(), r.getPremiumAmount()));
        row(csv, "险种分布（全部分类）", "险种编号", "订单数（笔）", "担保金额（元）", "保费金额（元）");
        report.insurances().forEach(r -> row(csv, r.getTypeName(), r.getInsuranceTypeId(), r.getOrderCount(), r.getGuaranteeAmount(), r.getPremiumAmount()));
        row(csv, "机构与地区排行（Top 10，非全量）", "机构编号", "地区编码", "订单数（笔）", "担保金额（元）", "保费金额（元）");
        report.institutions().forEach(r -> row(csv, r.getOrgName(), r.getOrgId(), r.getRegionCode(), r.getOrderCount(), r.getGuaranteeAmount(), r.getPremiumAmount()));
        return csv.toString();
    }

    private static void metric(StringBuilder csv, String name, String unit, OrderComparisonVO.Metric m) {
        String status = switch (m.status()) {
            case "NEW" -> "新增";
            case "UP" -> "上升";
            case "DOWN" -> "下降";
            default -> "无变化";
        };
        row(csv, name, unit, m.current(), m.previous(), m.change(), m.changeRate(), status);
    }

    private static void row(StringBuilder csv, Object... cells) {
        csv.append(Arrays.stream(cells).map(OrderReportCsv::cell).collect(Collectors.joining(","))).append("\r\n");
    }

    static String cell(Object value) {
        String text = value == null ? "" : value.toString();
        // 只对文本做公式防护，数值负号仍保持数值语义；忽略前导空白后的公式也必须覆盖。
        if (value instanceof String && text.stripLeading().matches("(?s)^[=+@\\-].*")) text = "'" + text;
        if (value instanceof String && (text.startsWith("\t") || text.startsWith("\r") || text.startsWith("\n"))) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
