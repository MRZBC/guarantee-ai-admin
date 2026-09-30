package com.guarantee.ai.tool;

import com.guarantee.analysis.dto.AnalysisCriteria;
import com.guarantee.analysis.service.OrderAnalysisService;
import com.guarantee.analysis.service.OrderAnalysisService.EnterpriseDimension;
import com.guarantee.analysis.service.OrderAnalysisService.EnterpriseOrderBy;
import com.guarantee.analysis.vo.EnterpriseGroupVO;
import com.guarantee.analysis.vo.EnterpriseRankVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 企业维度分析 AI Tool（只读，REQ-BA-03 / AC-BA-03）。
 *
 * <p><b>它补的是什么</b>：订单分布工具能回答"哪个区域/机构/险种贡献最大"，
 * 但答不了"**企业**这一层"——按行业、按企业等级、按企业所在地区的企业数与订单结构，
 * 以及"哪些企业下单最多/保额最高"。这类问题原先只能靠模型逐个企业调
 * {@code queryOrderSummary} 去枚举，既撞轮次预算，也必然枚举不全。</p>
 *
 * <p><b>口径（两条，改前必读）</b>：</p>
 * <ol>
 *   <li><b>企业名历史保留</b>：join {@code enterprise} 刻意不带 {@code is_deleted}/{@code status}
 *       ——已停用/已删除企业的历史订单仍要显示企业名，与订单筛选下拉一致
 *       （{@code docs/DEC-订单筛选下拉的选项口径.md} §2.4）；</li>
 *   <li><b>不新增口径</b>：企业与项目两条 SQL 都建立在 {@code OrderAnalysisMapper} 的
 *       {@code orderSource} + {@code criteriaFilter} 片段之上，订单侧过滤（类型/日期/区域/机构）
 *       与数据概览页完全同一套。</li>
 * </ol>
 *
 * <p><b>分层约束</b>：与 {@link OrderDistributionTool} 一致，只依赖业务 Service，
 * 不注入 Mapper、不写 SQL。权限沿用订单分析族（登录 + {@code ai:chat}，不额外要求权限码；
 * REQ §5.1.6 的 Q-BA-03 仍待定）。</p>
 */
@Component
public class EnterpriseAnalysisTool {

    private static final Logger log = LoggerFactory.getLogger(EnterpriseAnalysisTool.class);

    /** 模式：分组分布 / 企业排行。 */
    enum Mode {
        DISTRIBUTION, TOP;

        static Mode normalize(String raw) {
            String text = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
            return switch (text) {
                case "", "DISTRIBUTION", "分布", "分组" -> DISTRIBUTION;
                case "TOP", "RANK", "RANKING", "排行", "排名" -> TOP;
                default -> throw new IllegalArgumentException(
                        "查询模式只能是 DISTRIBUTION（分组分布）或 TOP（企业排行），实际收到: " + raw);
            };
        }
    }

    /** 默认条数：够看出头部，不挤占推理上下文。 */
    static final int DEFAULT_LIMIT = 10;

    /** 条数上限：与 {@link OrderDistributionTool} 对齐（工具返回值有 16KB 上限）。 */
    static final int MAX_LIMIT = 50;

    /** 险种口径名（与其它工具刻意同源，见 {@link OrderDistributionTool} 的说明）。 */
    private static final Map<String, String> ORDER_TYPE_NAMES = Map.of(
            "TENDER", "投标保函",
            "PERFORMANCE", "履约保函",
            "ALL", "全部险种");

    private static final Map<String, String> DIMENSION_NAMES = Map.of(
            "INDUSTRY", "行业",
            "LEVEL", "企业等级",
            "REGION", "地区");

    private static final Map<String, String> ORDER_BY_NAMES = Map.of(
            "ORDER_COUNT", "订单量",
            "GUARANTEE_AMOUNT", "保额");

    private final OrderAnalysisService orderAnalysisService;

    public EnterpriseAnalysisTool(OrderAnalysisService orderAnalysisService) {
        this.orderAnalysisService = orderAnalysisService;
    }

    @Tool(name = "queryEnterpriseAnalysis",
            description = """
                    企业维度的分析工具，两种模式：
                    ① mode=DISTRIBUTION（默认）：按 dimension 分组统计**企业数**与订单指标
                       ——dimension=INDUSTRY（行业）/ LEVEL（企业等级）/ REGION（企业所在地区），
                       返回每组的企业数、订单量、保函金额合计、保费合计，按订单量倒序；
                    ② mode=TOP：企业排行，返回企业名 + 编码 + 订单量 + 保函金额 + 保费，
                       orderBy=ORDER_COUNT（默认，按订单量）或 GUARANTEE_AMOUNT（按保额）。
                    当用户问“哪些行业/等级/地区的企业下单最多”“企业结构如何”“哪些企业单量或保额最高”时用它。
                    可先用 regionCode / 日期 / orderType 限定范围：例如「浙江省 2026 年二季度下单最多的企业」
                    = mode=TOP + regionCode=330000 + 2026-04-01~2026-06-30 **一次调用**即可。
                    区域编码是层级前缀匹配（选省含其全部市/区县）。
                    只想知道订单总量/分区域分布时用 queryOrderSummary / queryOrderDistribution，不要用本工具凑。
                    日期必须 yyyy-MM-dd（禁止“本季度”这类相对表述）。""")
    public EnterpriseAnalysisToolResult queryEnterpriseAnalysis(
            @ToolParam(description = "模式：DISTRIBUTION=分组分布（默认），TOP=企业排行", required = false)
            String mode,
            @ToolParam(description = "分组维度：INDUSTRY=行业，LEVEL=企业等级，REGION=企业地区。默认 INDUSTRY", required = false)
            String dimension,
            @ToolParam(description = "排行依据（仅 TOP）：ORDER_COUNT=订单量（默认），GUARANTEE_AMOUNT=保额", required = false)
            String orderBy,
            @ToolParam(description = "订单类型：TENDER=投标订单，PERFORMANCE=履约订单，ALL=全部。默认 ALL", required = false)
            String orderType,
            @ToolParam(description = "申请起始日期，格式 yyyy-MM-dd（含当天）。不传表示不限", required = false)
            String startDate,
            @ToolParam(description = "申请结束日期，格式 yyyy-MM-dd（含当天）。不传表示不限", required = false)
            String endDate,
            @ToolParam(description = "行政区划编码，例如 330000 表示浙江省；层级前缀匹配。不传表示不限", required = false)
            String regionCode,
            @ToolParam(description = "返回条数，默认 10，上限 50。只影响明细条数，不影响口径", required = false)
            Integer limit) {

        Mode normalizedMode = Mode.normalize(mode);
        // 维度/排序依据的归一化在 Service 的枚举里（非法值给可读中文错误，不抛堆栈给模型）
        EnterpriseDimension dim = EnterpriseDimension.normalize(dimension);
        EnterpriseOrderBy sortedBy = EnterpriseOrderBy.normalize(orderBy);
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
        criteria.setRegionCode(regionCode);

        List<EnterpriseAnalysisToolResult.EnterpriseItem> items = new ArrayList<>();
        boolean capped;
        if (normalizedMode == Mode.DISTRIBUTION) {
            List<EnterpriseGroupVO> rows =
                    orderAnalysisService.enterpriseDistribution(criteria, dim.name(), size);
            capped = rows.size() >= size;
            for (EnterpriseGroupVO row : rows) {
                items.add(new EnterpriseAnalysisToolResult.EnterpriseItem(
                        row.getGroupCode(), row.getGroupName(), row.getEnterpriseCount(),
                        row.getOrderCount(), zeroIfNull(row.getGuaranteeAmount()),
                        zeroIfNull(row.getPremiumAmount()), null, null));
            }
        } else {
            List<EnterpriseRankVO> rows =
                    orderAnalysisService.enterpriseTop(criteria, sortedBy.name(), size);
            capped = rows.size() >= size;
            for (EnterpriseRankVO row : rows) {
                items.add(new EnterpriseAnalysisToolResult.EnterpriseItem(
                        row.getEntCode(), row.getEntName(), 0L,
                        row.getOrderCount(), zeroIfNull(row.getGuaranteeAmount()),
                        zeroIfNull(row.getPremiumAmount()), row.getIndustry(), row.getEntLevel()));
            }
        }

        String type = criteria.normalizedOrderType();
        String dataSource = buildDataSource(normalizedMode, dim, sortedBy, type, start, end, regionCode);
        log.info("Tool queryEnterpriseAnalysis 执行完成 mode={} dimension={} orderBy={} orderType={} {}~{} region={} limit={} 返回={} 条",
                normalizedMode, dim, sortedBy, type, start, end, regionCode, size, items.size());

        ToolResultMeta meta = capped
                ? ToolResultMeta.truncated(dataSource,
                        "只返回了前 " + size + " 组/名，其余未返回（截断是显式的，不要把榜单说成完整清单）")
                : ToolResultMeta.ok(dataSource);

        return new EnterpriseAnalysisToolResult(normalizedMode.name(), dim.name(), sortedBy.name(),
                type,
                start == null ? null : start.toString(),
                end == null ? null : end.toString(),
                regionCode == null || regionCode.isBlank() ? null : regionCode.trim(),
                items, meta);
    }

    /** 口径文本：不含工具名与英文参数名（REQ-BA-05 第 2 条）。 */
    static String buildDataSource(Mode mode, EnterpriseDimension dim, EnterpriseOrderBy orderBy,
                                  String orderType, LocalDate start, LocalDate end, String regionCode) {
        Map<String, Object> parts = new LinkedHashMap<>();
        parts.put("mode", mode == Mode.DISTRIBUTION ? "分组分布" : "企业排行");
        parts.put("dimension", DIMENSION_NAMES.getOrDefault(dim.name(), dim.name()));
        if (mode == Mode.TOP) {
            parts.put("orderBy", ORDER_BY_NAMES.getOrDefault(orderBy.name(), orderBy.name()));
        }
        parts.put("orderType", ORDER_TYPE_NAMES.getOrDefault(orderType, orderType));
        parts.put("startDate", start == null ? null : start.toString());
        parts.put("endDate", end == null ? null : end.toString());
        parts.put("regionCode", formatRegion(regionCode));
        return DataSourceText.of("企业分析", parts);
    }

    private static String formatRegion(String regionCode) {
        if (regionCode == null || regionCode.isBlank()) {
            return null;
        }
        String code = regionCode.trim();
        return OrderDistributionTool.regionLabel(code);
    }

    static int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    static LocalDate parseDate(String raw, String field) {
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
