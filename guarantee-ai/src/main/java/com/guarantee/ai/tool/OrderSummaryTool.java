package com.guarantee.ai.tool;

import com.guarantee.order.dto.OrderSummaryCriteria;
import com.guarantee.order.service.OrderStatisticsService;
import com.guarantee.order.vo.OrderSummaryVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 订单统计类 AI Tool（只读）。
 *
 * <p><b>分层约束</b>：本类只允许依赖业务 Service（{@link OrderStatisticsService}），
 * 严禁注入任何 Mapper，也严禁生成 SQL。
 * 调用链固定为 Tool -&gt; Service -&gt; Mapper -&gt; DB。</p>
 */
@Component
public class OrderSummaryTool {

    private static final Logger log = LoggerFactory.getLogger(OrderSummaryTool.class);

    /** 行政区划编码 -> 名称，仅用于让返回值更易读。 */
    private static final Map<String, String> REGION_NAMES = Map.ofEntries(
            Map.entry("110000", "北京市"),
            Map.entry("310000", "上海市"),
            Map.entry("320000", "江苏省"),
            Map.entry("330000", "浙江省"),
            Map.entry("370000", "山东省"),
            Map.entry("420000", "湖北省"),
            Map.entry("440000", "广东省"),
            Map.entry("510000", "四川省"));

    private final OrderStatisticsService orderStatisticsService;

    public OrderSummaryTool(OrderStatisticsService orderStatisticsService) {
        this.orderStatisticsService = orderStatisticsService;
    }

    @Tool(name = "queryOrderSummary",
            description = """
                    查询电子保函订单的核心统计指标：订单量、保函金额合计、保费合计、去重企业数、去重项目数。
                    支持按订单类型（投标/履约）、申请日期区间、行政区划、承保机构过滤。
                    当用户询问“订单量/保额/保费/多少家企业/多少个项目”等统计类问题时使用本工具。
                    日期必须使用 yyyy-MM-dd 的明确格式，禁止传入“本季度”“上个月”这类相对表述。""")
    public OrderSummaryToolResult queryOrderSummary(
            @ToolParam(description = "订单类型：TENDER=投标订单，PERFORMANCE=履约订单，ALL=全部。默认 ALL", required = false)
            String orderType,
            @ToolParam(description = "申请起始日期，格式 yyyy-MM-dd（含当天）。不传表示不限", required = false)
            String startDate,
            @ToolParam(description = "申请结束日期，格式 yyyy-MM-dd（含当天）。不传表示不限", required = false)
            String endDate,
            @ToolParam(description = "行政区划编码，例如 330000 表示浙江省。不传表示不限", required = false)
            String regionCode,
            @ToolParam(description = "承保机构 ID。不传表示不限", required = false)
            Long orgId) {

        LocalDate start = parseDate(startDate, "startDate");
        LocalDate end = parseDate(endDate, "endDate");
        if (start != null && end != null && start.isAfter(end)) {
            throw new IllegalArgumentException(
                    "startDate(" + startDate + ") 不能晚于 endDate(" + endDate + ")");
        }

        OrderSummaryCriteria criteria = new OrderSummaryCriteria();
        criteria.setOrderType(orderType);
        criteria.setStartDate(start);
        criteria.setEndDate(end);
        criteria.setRegionCode(regionCode);
        criteria.setOrgId(orgId);

        // 只调用业务 Service，绝不直接访问 Mapper
        OrderSummaryVO summary = orderStatisticsService.summarize(criteria);

        String normalizedType = criteria.normalizedOrderType();
        log.info("Tool queryOrderSummary 执行完成 orderType={} start={} end={} region={} orgId={} count={}",
                normalizedType, start, end, regionCode, orgId, summary.getOrderCount());

        return new OrderSummaryToolResult(
                normalizedType,
                start == null ? null : start.toString(),
                end == null ? null : end.toString(),
                regionCode,
                orgId,
                summary.getOrderCount(),
                summary.getGuaranteeAmount(),
                summary.getPremiumAmount(),
                summary.getEnterpriseCount(),
                summary.getProjectCount(),
                buildDataSource(normalizedType, start, end, regionCode, orgId));
    }

    /**
     * 供模型获取“今天”的工具。相对时间必须先落到明确日期，
     * 而模型本身并不知道系统当前日期，因此需要一个可信来源。
     */
    @Tool(name = "getCurrentDate",
            description = "获取系统当前日期，格式 yyyy-MM-dd。在把“本季度/上月/最近三个月”等相对时间转换成明确日期前，应先调用本工具。")
    public String getCurrentDate() {
        LocalDate today = LocalDate.now();
        return today + " (" + today.getDayOfWeek() + ")";
    }

    private static String buildDataSource(String orderType, LocalDate start, LocalDate end,
                                          String regionCode, Long orgId) {
        StringBuilder sb = new StringBuilder("queryOrderSummary(orderType=").append(orderType);
        sb.append(", startDate=").append(start == null ? "不限" : start);
        sb.append(", endDate=").append(end == null ? "不限" : end);
        if (regionCode != null && !regionCode.isBlank()) {
            sb.append(", region=").append(regionCode)
                    .append(REGION_NAMES.containsKey(regionCode) ? "(" + REGION_NAMES.get(regionCode) + ")" : "");
        }
        if (orgId != null) {
            sb.append(", orgId=").append(orgId);
        }
        return sb.append(")").toString();
    }

    private static LocalDate parseDate(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(
                    field + " 必须是 yyyy-MM-dd 格式的明确日期，实际收到: " + raw);
        }
    }
}
