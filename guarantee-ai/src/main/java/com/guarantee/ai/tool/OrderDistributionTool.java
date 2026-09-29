package com.guarantee.ai.tool;

import com.guarantee.analysis.dto.AnalysisCriteria;
import com.guarantee.analysis.service.OrderAnalysisService;
import com.guarantee.analysis.vo.OrderInstitutionVO;
import com.guarantee.analysis.vo.OrderInsuranceVO;
import com.guarantee.analysis.vo.OrderRegionVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 订单维度分布类 AI Tool（只读）。
 *
 * <p><b>为什么必须有它</b>（2026-09-29 现场）：用户问「请分析 2026 年第二季度投标订单，
 * 和第一季度比较，并从区域、机构、险种三个维度找出主要变化」。当时助手只有
 * {@link OrderSummaryTool}（一个汇总值），于是只有两条路：</p>
 * <ul>
 *   <li>逐个区域/机构去调 {@code queryOrderSummary}（实测 36 次调用把 4 轮工具轮次用满，
 *       收尾轮被截断 → 一个结论都没给出）；</li>
 *   <li>或者如实说明"我给不出"（现场另一位用户的会话就是这样，用户反问
 *       「为什么不能从三个维度找出主要变化」）。</li>
 * </ul>
 *
 * <p>而这三个维度的分布数据**系统里本来就有**：数据概览页的
 * {@code /api/analysis/order-region}、{@code order-institution}、{@code order-insurance}
 * 就是它（{@link OrderAnalysisService} + {@code OrderAnalysisMapper}）。
 * 本工具只是把它接到助手侧，让同一个口径也能被问答消费——不新增 SQL、不新增口径。</p>
 *
 * <p><b>分层约束</b>：与 {@link OrderSummaryTool} 一致，只依赖业务 Service，不注入 Mapper、不写 SQL。</p>
 */
@Component
public class OrderDistributionTool {

    private static final Logger log = LoggerFactory.getLogger(OrderDistributionTool.class);

    /** 默认条数：够看出"主要变化"，又不至于挤掉推理用的上下文。 */
    static final int DEFAULT_LIMIT = 10;

    /**
     * 条数上限。
     *
     * <p>刻意比 Service 的 200 小：工具返回值有 16KB 上限（SYS-Q-10），
     * 而且"找出主要变化"只需要头部几名——给 200 条反而会把真正要对比的数字淹掉。</p>
     */
    static final int MAX_LIMIT = 50;

    private final OrderAnalysisService orderAnalysisService;

    public OrderDistributionTool(OrderAnalysisService orderAnalysisService) {
        this.orderAnalysisService = orderAnalysisService;
    }

    /** 维度取值：大小写不敏感，并兼容中文别名（模型偶尔会直接给"区域"）。 */
    enum Dimension {
        REGION("区域"),
        ORG("机构"),
        INSURANCE("险种");

        private final String label;

        Dimension(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }

        static Dimension normalize(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("dimension 不能为空：REGION=区域，ORG=机构，INSURANCE=险种");
            }
            String value = raw.trim().toUpperCase(Locale.ROOT);
            return switch (value) {
                case "REGION", "AREA", "区域", "地区" -> REGION;
                case "ORG", "ORGANIZATION", "INSTITUTION", "机构" -> ORG;
                case "INSURANCE", "INSURANCE_TYPE", "险种", "险种类别" -> INSURANCE;
                default -> throw new IllegalArgumentException(
                        "dimension 只能是 REGION（区域）/ ORG（机构）/ INSURANCE（险种），实际收到: " + raw);
            };
        }
    }

    @Tool(name = "queryOrderDistribution",
            description = """
                    按**单个维度**统计订单分布：区域（REGION）/ 机构（ORG）/ 险种（INSURANCE），
                    返回该维度下按订单量倒序的明细（订单量、保函金额合计、保费合计、去重企业数）。
                    当用户问“哪些区域/机构/险种贡献最大”“某维度有什么变化/排名”时使用本工具。
                    比较两个时间区间（例如两个季度）时，对同一维度**各调用一次**再对比即可。
                    需要总计（不分组）时用 queryOrderSummary；不要为了找变化逐个区域去调 queryOrderSummary。
                    日期必须使用 yyyy-MM-dd 的明确格式，禁止传入“本季度”“上个月”这类相对表述。""")
    public OrderDistributionToolResult queryOrderDistribution(
            @ToolParam(description = "统计维度：REGION=区域，ORG=机构，INSURANCE=险种", required = true)
            String dimension,
            @ToolParam(description = "订单类型：TENDER=投标订单，PERFORMANCE=履约订单，ALL=全部。默认 ALL", required = false)
            String orderType,
            @ToolParam(description = "申请起始日期，格式 yyyy-MM-dd（含当天）。不传表示不限", required = false)
            String startDate,
            @ToolParam(description = "申请结束日期，格式 yyyy-MM-dd（含当天）。不传表示不限", required = false)
            String endDate,
            @ToolParam(description = "返回条数，默认 10，上限 50。只影响明细条数，不影响口径", required = false)
            Integer limit) {

        Dimension dim = Dimension.normalize(dimension);
        LocalDate start = parseDate(startDate, "startDate");
        LocalDate end = parseDate(endDate, "endDate");
        if (start != null && end != null && start.isAfter(end)) {
            throw new IllegalArgumentException("startDate(" + startDate + ") 不能晚于 endDate(" + endDate + ")");
        }
        int size = normalizeLimit(limit);

        AnalysisCriteria criteria = new AnalysisCriteria();
        criteria.setOrderType(orderType);
        criteria.setStartDate(start);
        criteria.setEndDate(end);

        List<OrderDistributionToolResult.DistributionItem> items = new ArrayList<>();
        boolean capped;
        switch (dim) {
            case REGION -> {
                List<OrderRegionVO> rows = orderAnalysisService.regionDistribution(criteria, size);
                capped = rows.size() >= size;
                addRegionItems(items, rows);
            }
            case ORG -> {
                List<OrderInstitutionVO> rows = orderAnalysisService.institutionDistribution(criteria, size);
                capped = rows.size() >= size;
                addInstitutionItems(items, rows);
            }
            default -> {
                // 险种维度在 Service 侧不带 limit（维度本身很短），这里按 limit 截断并如实标记
                List<OrderInsuranceVO> rows = orderAnalysisService.insuranceDistribution(criteria);
                capped = rows.size() > size;
                addInsuranceItems(items, rows, size);
            }
        }

        String type = criteria.normalizedOrderType();
        String dataSource = buildDataSource(dim, type, start, end, size);
        log.info("Tool queryOrderDistribution 执行完成 dimension={} orderType={} start={} end={} limit={} 返回={} 条",
                dim, type, start, end, size, items.size());

        ToolResultMeta meta = capped
                ? ToolResultMeta.truncated(dataSource,
                        "只返回订单量前 " + size + " 条，可能还有更多；需要更多时提高 limit（上限 " + MAX_LIMIT + "）")
                : ToolResultMeta.ok(dataSource);
        return new OrderDistributionToolResult(type, dim.name(),
                start == null ? null : start.toString(), end == null ? null : end.toString(), items, meta);
    }

    private static void addRegionItems(List<OrderDistributionToolResult.DistributionItem> items,
                                       List<OrderRegionVO> rows) {
        int rank = 1;
        for (OrderRegionVO row : rows) {
            items.add(new OrderDistributionToolResult.DistributionItem(rank++, row.getRegionCode(),
                    row.getRegionName(), row.getOrderCount(), row.getGuaranteeAmount(),
                    row.getPremiumAmount(), row.getEnterpriseCount()));
        }
    }

    private static void addInstitutionItems(List<OrderDistributionToolResult.DistributionItem> items,
                                            List<OrderInstitutionVO> rows) {
        int rank = 1;
        for (OrderInstitutionVO row : rows) {
            items.add(new OrderDistributionToolResult.DistributionItem(rank++, row.getOrgCode(),
                    row.getOrgName(), row.getOrderCount(), row.getGuaranteeAmount(),
                    row.getPremiumAmount(), row.getEnterpriseCount()));
        }
    }

    private static void addInsuranceItems(List<OrderDistributionToolResult.DistributionItem> items,
                                          List<OrderInsuranceVO> rows, int size) {
        int rank = 1;
        for (OrderInsuranceVO row : rows) {
            if (rank > size) {
                break;
            }
            items.add(new OrderDistributionToolResult.DistributionItem(rank++, row.getTypeCode(),
                    row.getTypeName(), row.getOrderCount(), row.getGuaranteeAmount(),
                    row.getPremiumAmount(), null));
        }
    }

    /** 订单类型编码 -> 业务名称，让口径行讲人话（与 {@code OrderSummaryTool} 同一张表）。 */
    private static final Map<String, String> ORDER_TYPE_NAMES = Map.of(
            "TENDER", "投标保函",
            "PERFORMANCE", "履约保函",
            "ALL", "全部险种");

    private static String buildDataSource(Dimension dim, String orderType, LocalDate start,
                                          LocalDate end, int size) {
        return DataSourceText.of("订单分布", DataSourceText.parts(
                "维度", dim.label(),
                "orderType", ORDER_TYPE_NAMES.getOrDefault(orderType, orderType),
                "时间区间", (start == null ? "不限" : start) + " ~ " + (end == null ? "不限" : end),
                "条数", "前 " + size + " 条"));
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
