package com.guarantee.ai.tool;

import com.guarantee.analysis.dto.AnalysisCriteria;
import com.guarantee.analysis.service.OrderAnalysisService;
import com.guarantee.analysis.service.OrderAnalysisService.ProjectDimension;
import com.guarantee.analysis.vo.ProjectGroupVO;
import com.guarantee.analysis.vo.ProjectRankVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 项目维度分析 AI Tool（只读，REQ-BA-04 / AC-BA-04）。
 *
 * <p><b>它补的是什么</b>：订单分析工具回答"订单从哪来、谁贡献大"，但答不了
 * "**项目**这一层"——按项目类型（房建/市政/交通/水利/其他）或地区看项目数与订单指标，
 * 以及"哪些项目担保金额最高"。项目类型在库里就是**中文枚举**，本工具直接透传中文，
 * 不让模型翻译（翻译一层就多一次编造的机会，与项目管理页同一口径）。</p>
 *
 * <p><b>口径</b>：项目名与类型"历史保留"——join {@code project} 刻意不带
 * {@code is_deleted}/{@code status}，见 {@code docs/DEC-订单筛选下拉的选项口径.md} §2.4。</p>
 *
 * <p><b>分层约束</b>：只依赖业务 Service，不注入 Mapper、不写 SQL。</p>
 */
@Component
public class ProjectAnalysisTool {

    private static final Logger log = LoggerFactory.getLogger(ProjectAnalysisTool.class);

    /** 模式：分组分布 / 项目排行。 */
    enum Mode {
        DISTRIBUTION, TOP;

        static Mode normalize(String raw) {
            String text = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
            return switch (text) {
                case "", "DISTRIBUTION", "分布", "分组" -> DISTRIBUTION;
                case "TOP", "RANK", "RANKING", "排行", "排名" -> TOP;
                default -> throw new IllegalArgumentException(
                        "查询模式只能是 DISTRIBUTION（分组分布）或 TOP（项目排行），实际收到: " + raw);
            };
        }
    }

    static final int DEFAULT_LIMIT = 10;

    static final int MAX_LIMIT = 50;

    private static final Map<String, String> ORDER_TYPE_NAMES = Map.of(
            "TENDER", "投标保函",
            "PERFORMANCE", "履约保函",
            "ALL", "全部险种");

    private static final Map<String, String> DIMENSION_NAMES = Map.of(
            "PROJECT_TYPE", "项目类型",
            "REGION", "地区");

    private final OrderAnalysisService orderAnalysisService;

    public ProjectAnalysisTool(OrderAnalysisService orderAnalysisService) {
        this.orderAnalysisService = orderAnalysisService;
    }

    @Tool(name = "queryProjectAnalysis",
            description = """
                    项目维度的分析工具，两种模式：
                    ① mode=DISTRIBUTION（默认）：按 dimension 分组统计**项目数**与订单指标
                       ——dimension=PROJECT_TYPE（项目类型：房建/市政/交通/水利/其他）或 REGION（地区），
                       返回每组的项目数、订单量、保函金额合计、保费合计，按订单量倒序；
                    ② mode=TOP：项目排行，按**担保金额倒序**返回项目名 + 编码 + 类型 + 地区 + 订单量 + 保函金额 + 保费。
                    当用户问“哪些类型的项目最多/占比如何”“哪些地区项目多”“担保金额最高的项目”时用它。
                    项目类型返回值就是中文（房建/市政/交通/水利/其他），**原样使用，不要翻译或改写**。
                    可先用 regionCode / 日期 / orderType 限定范围：例如「交通类项目 2026 年二季度担保额占比」
                    = mode=DISTRIBUTION + dimension=PROJECT_TYPE + 2026-04-01~2026-06-30，再按返回的项目数/金额算占比。
                    区域编码是层级前缀匹配（选省含其全部市/区县）。
                    日期必须 yyyy-MM-dd（禁止“本季度”这类相对表述）。""")
    public ProjectAnalysisToolResult queryProjectAnalysis(
            @ToolParam(description = "模式：DISTRIBUTION=分组分布（默认），TOP=项目排行（按担保金额倒序）", required = false)
            String mode,
            @ToolParam(description = "分组维度：PROJECT_TYPE=项目类型（默认），REGION=地区", required = false)
            String dimension,
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
        ProjectDimension dim = ProjectDimension.normalize(dimension);
        LocalDate start = EnterpriseAnalysisTool.parseDate(startDate, "startDate");
        LocalDate end = EnterpriseAnalysisTool.parseDate(endDate, "endDate");
        if (start != null && end != null && start.isAfter(end)) {
            throw new IllegalArgumentException("startDate(" + startDate + ") 不能晚于 endDate(" + endDate + ")");
        }
        int size = normalizeLimit(limit);

        AnalysisCriteria criteria = new AnalysisCriteria();
        criteria.setOrderType(orderType);
        criteria.setStartDate(start);
        criteria.setEndDate(end);
        criteria.setRegionCode(regionCode);

        List<ProjectAnalysisToolResult.ProjectItem> items = new ArrayList<>();
        boolean capped;
        if (normalizedMode == Mode.DISTRIBUTION) {
            List<ProjectGroupVO> rows =
                    orderAnalysisService.projectDistribution(criteria, dim.name(), size);
            capped = rows.size() >= size;
            for (ProjectGroupVO row : rows) {
                items.add(new ProjectAnalysisToolResult.ProjectItem(
                        row.getGroupCode(), row.getGroupName(), row.getProjectCount(),
                        row.getOrderCount(), zeroIfNull(row.getGuaranteeAmount()),
                        zeroIfNull(row.getPremiumAmount()), zeroIfNull(row.getShare()), row.getGroupCode(), row.getGroupName()));
            }
        } else {
            List<ProjectRankVO> rows = orderAnalysisService.projectTop(criteria, size);
            capped = rows.size() >= size;
            for (ProjectRankVO row : rows) {
                items.add(new ProjectAnalysisToolResult.ProjectItem(
                        row.getProjectCode(), row.getProjectName(), 0L,
                        row.getOrderCount(), zeroIfNull(row.getGuaranteeAmount()),
                        zeroIfNull(row.getPremiumAmount()), BigDecimal.ZERO, row.getProjectType(), row.getRegionName()));
            }
        }

        String type = criteria.normalizedOrderType();
        String dataSource = buildDataSource(normalizedMode, dim, type, start, end, regionCode);
        log.info("Tool queryProjectAnalysis 执行完成 mode={} dimension={} orderType={} {}~{} region={} limit={} 返回={} 条",
                normalizedMode, dim, type, start, end, regionCode, size, items.size());

        ToolResultMeta meta = capped
                ? ToolResultMeta.truncated(dataSource,
                        "只返回了前 " + size + " 组/个项目，其余未返回（截断是显式的，不要把榜单说成完整清单）")
                : ToolResultMeta.ok(dataSource);

        return new ProjectAnalysisToolResult(normalizedMode.name(), dim.name(), type,
                start == null ? null : start.toString(),
                end == null ? null : end.toString(),
                regionCode == null || regionCode.isBlank() ? null : regionCode.trim(),
                "GUARANTEE_AMOUNT", items, meta);
    }

    /** 口径文本：不含工具名与英文参数名（REQ-BA-05 第 2 条）。 */
    static String buildDataSource(Mode mode, ProjectDimension dim, String orderType,
                                  LocalDate start, LocalDate end, String regionCode) {
        Map<String, Object> parts = new LinkedHashMap<>();
        parts.put("mode", mode == Mode.DISTRIBUTION ? "分组分布" : "项目排行（按担保金额）");
        parts.put("dimension", DIMENSION_NAMES.getOrDefault(dim.name(), dim.name()));
        if (mode == Mode.DISTRIBUTION) {
            parts.put("占比口径", "按担保金额");
        }
        parts.put("orderType", ORDER_TYPE_NAMES.getOrDefault(orderType, orderType));
        parts.put("startDate", start == null ? null : start.toString());
        parts.put("endDate", end == null ? null : end.toString());
        parts.put("regionCode", regionCode == null || regionCode.isBlank()
                ? null : OrderDistributionTool.regionLabel(regionCode.trim()));
        return DataSourceText.of("项目分析", parts);
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
}
