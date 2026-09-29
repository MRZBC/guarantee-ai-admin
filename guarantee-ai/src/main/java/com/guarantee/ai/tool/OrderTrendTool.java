package com.guarantee.ai.tool;

import com.guarantee.analysis.dto.OrderTrendQuery;
import com.guarantee.analysis.service.OrderAnalysisService;
import com.guarantee.analysis.vo.OrderTrendVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 订单趋势类 AI Tool（只读）。
 *
 * <p><b>为什么必须有它</b>：汇总与分布工具都答不了"趋势"。用户问「2026 年保费的月度趋势、
 * 拐点在哪」时，模型只能取两个区间总量相减——那是**两点之间的一条直线**，不是序列：
 * 中间的涨跌、拐点、连续几个月的方向全部丢失，而"哪个月开始转向"恰恰是管理者要的结论。
 * 本工具把数据概览页的趋势口径（{@link OrderAnalysisService#trend(OrderTrendQuery)}）接到助手侧。</p>
 *
 * <p><b>分层约束</b>：与 {@link OrderSummaryTool}、{@link OrderDistributionTool} 一致，
 * 只依赖业务 Service，不注入 Mapper、不写 SQL（零新 SQL：粒度、区域、机构过滤都由既有的
 * {@code OrderTrendQuery} + {@code criteriaFilter} 承担）。</p>
 *
 * <p><b>为什么默认 24 点、上限 120 且从最近端截断</b>：日粒度跨 21 个月约 640 个点，
 * 整段回灌会撞上工具返回值的 16KB 上限（SYS-Q-10），被静默截断后模型反而不知道缺了什么。
 * 这里的做法是主动截断 + 显式标记：保留**最近**的周期（问趋势的人关心的是现在），
 * 并在 {@code truncated} 里说清"只给了最近 N 个周期"。</p>
 *
 * <p>粒度归一化沿用 {@link OrderTrendQuery.Granularity#normalize(String)}：大小写不敏感，
 * 无法识别的值回落为按月——与数据概览页同一行为，避免"页面点得出来、助手却报错"的不一致。</p>
 */
@Component
public class OrderTrendTool {

    private static final Logger log = LoggerFactory.getLogger(OrderTrendTool.class);

    /**
     * 行政区划编码 -> 名称，仅用于口径行更好读（{@code 区域：330000（浙江省）}）。
     *
     * <p>与 {@link OrderSummaryTool} / {@link OrderDistributionTool} 里的同名表刻意保持同源：
     * 不抽公共类是为了让 M2.x 并行改动时各工具互不牵连；新增省份时几处一起补。</p>
     */
    private static final Map<String, String> REGION_NAMES = Map.ofEntries(
            Map.entry("110000", "北京市"),
            Map.entry("310000", "上海市"),
            Map.entry("320000", "江苏省"),
            Map.entry("330000", "浙江省"),
            Map.entry("370000", "山东省"),
            Map.entry("420000", "湖北省"),
            Map.entry("440000", "广东省"),
            Map.entry("510000", "四川省"));

    /** 默认点数：两年月度序列（24 个点）足够看出走势与拐点。 */
    static final int DEFAULT_LIMIT = 24;

    /**
     * 点数上限：即便按日也只有 120 个点，远低于 16KB 上限。
     *
     * <p>选 120 而不是 24：日粒度看"最近四个月"是常见诉求，给 24 会逼模型反复调大 limit；
     * 上限压住的是"一次把 640 个点全灌进上下文"这种失控情形。</p>
     */
    static final int MAX_LIMIT = 120;

    /** 归一化粒度 -> 口径行里的中文说法（口径行不得出现英文参数名，见提示词第 42 条）。 */
    private static final Map<String, String> GRANULARITY_NAMES = Map.of(
            OrderTrendQuery.Granularity.DAY, "按日",
            OrderTrendQuery.Granularity.MONTH, "按月",
            OrderTrendQuery.Granularity.YEAR, "按年");

    /** 订单类型编码 -> 业务名称（与 {@code OrderSummaryTool} 同一张表）。 */
    private static final Map<String, String> ORDER_TYPE_NAMES = Map.of(
            "TENDER", "投标保函",
            "PERFORMANCE", "履约保函",
            "ALL", "全部险种");

    private final OrderAnalysisService orderAnalysisService;

    public OrderTrendTool(OrderAnalysisService orderAnalysisService) {
        this.orderAnalysisService = orderAnalysisService;
    }

    @Tool(name = "queryOrderTrend",
            description = """
                    查询订单趋势：按时间粒度（DAY=按日 / MONTH=按月（默认）/ YEAR=按年）返回每个周期的
                    订单量、保函金额合计、保费合计，按时间升序排列。
                    当用户问“趋势/走势/拐点/逐月变化/一年里怎么走”时使用本工具。
                    比较两个时间区间的大小（两个季度的总量谁多）用 queryOrderSummary 或 queryOrderDistribution，
                    **不要**用“两个区间相减”假装趋势——那只有两个点，不是序列。
                    支持按 orderType / regionCode / orgId 过滤；区域编码是层级前缀匹配（选省含其全部市/区县）。
                    周期很多时只返回**最近 limit 个周期**（默认 24，上限 120），返回值里会带 truncated 提示，
                    必须如实说明“只看了最近 N 个周期”，不要把缺失的早期周期当成 0 或说成全部。
                    日期必须使用 yyyy-MM-dd 的明确格式，禁止传入“本季度”“上个月”这类相对表述。""")
    public OrderTrendToolResult queryOrderTrend(
            @ToolParam(description = "订单类型：TENDER=投标订单，PERFORMANCE=履约订单，ALL=全部。默认 ALL", required = false)
            String orderType,
            @ToolParam(description = "统计起始日期，格式 yyyy-MM-dd（含当天）。不传表示不限", required = false)
            String startDate,
            @ToolParam(description = "统计结束日期，格式 yyyy-MM-dd（含当天）。不传表示不限", required = false)
            String endDate,
            @ToolParam(description = "时间粒度：DAY=按日，MONTH=按月（默认），YEAR=按年。大小写不敏感", required = false)
            String granularity,
            @ToolParam(description = "行政区划编码，例如 330000 表示浙江省；按层级前缀匹配（选省含其全部市/区县）。不传表示不限", required = false)
            String regionCode,
            @ToolParam(description = "承保机构 ID。不传表示不限", required = false)
            Long orgId,
            @ToolParam(description = "返回的周期数（取最近的点），默认 24，上限 120。只影响返回条数，不影响口径", required = false)
            Integer limit) {

        LocalDate start = parseDate(startDate, "startDate");
        LocalDate end = parseDate(endDate, "endDate");
        if (start != null && end != null && start.isAfter(end)) {
            throw new IllegalArgumentException(
                    "startDate(" + startDate + ") 不能晚于 endDate(" + endDate + ")");
        }
        int size = normalizeLimit(limit);
        // 归一化交给 DTO（大小写不敏感 + 非法值回落 month），与数据概览页同一行为
        String normalizedGranularity = OrderTrendQuery.Granularity.normalize(granularity);

        OrderTrendQuery query = new OrderTrendQuery();
        query.setOrderType(orderType);
        query.setStartDate(start);
        query.setEndDate(end);
        query.setRegionCode(regionCode);
        query.setOrgId(orgId);
        query.setGranularity(normalizedGranularity);

        // 只调用业务 Service，绝不直接访问 Mapper（与数据概览页同一条 SQL）
        List<OrderTrendVO> rows = orderAnalysisService.trend(query);
        boolean capped = rows.size() > size;
        List<OrderTrendToolResult.TrendPoint> points = tailPoints(rows, size);

        String type = query.normalizedOrderType();
        String dataSource = buildDataSource(normalizedGranularity, type, start, end, regionCode, orgId, size);
        log.info("Tool queryOrderTrend 执行完成 orderType={} granularity={} start={} end={} region={} orgId={} "
                        + "limit={} 实际周期={} 返回={}",
                type, normalizedGranularity, start, end, regionCode, orgId, size, rows.size(), points.size());

        ToolResultMeta meta = capped
                ? ToolResultMeta.truncated(dataSource,
                        "只给了最近 " + size + " 个周期（符合条件的一共 " + rows.size()
                                + " 个）；需要更早的数据时请缩小日期区间或提高 limit（上限 " + MAX_LIMIT + "）")
                : ToolResultMeta.ok(dataSource);
        return new OrderTrendToolResult(type, normalizedGranularity,
                start == null ? null : start.toString(), end == null ? null : end.toString(),
                regionCode == null || regionCode.isBlank() ? null : regionCode.trim(), orgId, points, meta);
    }

    /**
     * 取**最近**的 size 个周期。
     *
     * <p>{@code Service} 按 period 升序返回（SQL 里 {@code ORDER BY period ASC}），
     * 因此"最近端"就是列表尾部——截断必须从这里切，否则返回的是两年前的数据，
     * 而用户问的是"现在的趋势"。</p>
     */
    private static List<OrderTrendToolResult.TrendPoint> tailPoints(List<OrderTrendVO> rows, int size) {
        int from = Math.max(0, rows.size() - size);
        List<OrderTrendToolResult.TrendPoint> points = new ArrayList<>(rows.size() - from);
        for (int i = from; i < rows.size(); i++) {
            OrderTrendVO row = rows.get(i);
            points.add(new OrderTrendToolResult.TrendPoint(row.getPeriod(), row.getOrderCount(),
                    row.getGuaranteeAmount(), row.getPremiumAmount()));
        }
        return points;
    }

    private static String buildDataSource(String granularity, String orderType, LocalDate start,
                                          LocalDate end, String regionCode, Long orgId, int size) {
        return DataSourceText.of("订单趋势", DataSourceText.parts(
                "粒度", GRANULARITY_NAMES.getOrDefault(granularity, granularity),
                "orderType", ORDER_TYPE_NAMES.getOrDefault(orderType, orderType),
                "时间区间", (start == null ? "不限" : start) + " ~ " + (end == null ? "不限" : end),
                "region", regionLabel(regionCode),
                "orgId", orgId,
                "条数", "最近 " + size + " 个周期"));
    }

    /**
     * 区域口径：{@code 330000（浙江省）}；编码不在名称表里时只回显编码（宁可少一个括号，
     * 也不要猜一个错省份）。
     */
    static String regionLabel(String regionCode) {
        if (regionCode == null || regionCode.isBlank()) {
            return null;
        }
        String code = regionCode.trim();
        String name = REGION_NAMES.get(code);
        return name == null ? code : code + "（" + name + "）";
    }

    static int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static LocalDate parseDate(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(
                    field + " 必须是 yyyy-MM-dd 格式的明确日期，实际收到: " + raw);
        }
    }
}
