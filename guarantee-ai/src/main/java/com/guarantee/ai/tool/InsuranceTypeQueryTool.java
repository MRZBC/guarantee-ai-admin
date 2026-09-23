package com.guarantee.ai.tool;

import com.guarantee.common.security.Permissions;
import com.guarantee.system.dto.InsuranceTypeDto;
import com.guarantee.system.service.InsuranceTypeService;
import com.guarantee.system.vo.InsuranceTypeVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code queryInsuranceType}（SYS-Q-05）：险种配置查询。
 *
 * <p>险种是全局配置（不带机构归属），因此**不做机构范围过滤**——
 * 费率口径对所有运营人员一致，这也是"现在的投标保函费率是多少"能直接回答的原因。</p>
 */
@Component
public class InsuranceTypeQueryTool {

    private static final Logger log = LoggerFactory.getLogger(InsuranceTypeQueryTool.class);

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final InsuranceTypeService insuranceTypeService;

    public InsuranceTypeQueryTool(InsuranceTypeService insuranceTypeService) {
        this.insuranceTypeService = insuranceTypeService;
    }

    @Tool(name = "queryInsuranceType",
            description = """
                    查询险种配置：险种编码、名称、分类（TENDER 投标 / PERFORMANCE 履约 / OTHER）、基准费率、保额区间、状态。
                    当用户询问"有哪些险种""投标保函费率是多少""履约保函的保额范围"时使用本工具。
                    费率同时给出小数（0.008000）与百分比（0.8，单位 %）两种表示，请直接引用，不要自行换算。
                    默认**不包含已删除险种**（逻辑删除：删除后默认不可见）。用户要看已删除数据
                    （「显示已删除」）时传 includeDeleted=true；该参数需要 system:insurance:delete 权限，
                    无权限时工具会返回明确的无权限说明，此时不得猜测或编造已删除数据。
                    返回值中的 isDeleted/deletedAt 用于区分"已删除"与"未删除"：
                    对 isDeleted=true 的险种**不要**再提出删除或其他变更提案。""")
    public InsuranceTypeQueryToolResult queryInsuranceType(
            @ToolParam(description = "险种名称或编码的模糊关键字，例如 履约保函 或 PERF_STD。不传表示不限", required = false)
            String keyword,
            @ToolParam(description = "险种分类：TENDER=投标保函，PERFORMANCE=履约保函，OTHER=其他。不传表示不限", required = false)
            String category,
            @ToolParam(description = "状态：1=启用，0=停用。不传表示不限", required = false)
            Integer status,
            @ToolParam(description = "是否包含已删除险种（「显示已删除」）。默认 false；"
                    + "需要 system:insurance:delete 权限，无权限时会被明确拒绝", required = false)
            Boolean includeDeleted,
            @ToolParam(description = "返回条数上限，默认 20，最大 50", required = false)
            Integer limit,
            ToolContext toolContext) {

        if (!AiPermissionGuard.allowed(toolContext, Permissions.INSURANCE_VIEW)) {
            return InsuranceTypeQueryToolResult.denied(
                    AiPermissionGuard.deniedReason(Permissions.INSURANCE_VIEW));
        }
        // 「显示已删除」的参数级权限判定（设计 §7.4 / §7.1）：无 :delete 权限时明确报错，
        // 绝不静默忽略 true（SYS-Q-11：无权限不得伪装成空结果）
        if (Boolean.TRUE.equals(includeDeleted)
                && !AiPermissionGuard.allowed(toolContext, Permissions.INSURANCE_DELETE)) {
            return InsuranceTypeQueryToolResult.denied(
                    AiPermissionGuard.includeDeletedDeniedReason(Permissions.INSURANCE_DELETE));
        }

        int effectiveLimit = clampLimit(limit);
        InsuranceTypeDto.Query query = new InsuranceTypeDto.Query();
        query.setKeyword(keyword);
        query.setTypeName(keyword);
        query.setTypeCode(keyword);
        query.setCategory(category);
        query.setStatus(status);
        query.setIncludeDeleted(Boolean.TRUE.equals(includeDeleted));
        query.setPageNum(1);
        query.setPageSize(effectiveLimit);

        var page = insuranceTypeService.page(query);
        List<InsuranceTypeQueryToolResult.InsuranceItem> items = new ArrayList<>();
        for (InsuranceTypeVO vo : page.list()) {
            items.add(new InsuranceTypeQueryToolResult.InsuranceItem(
                    vo.id(), vo.typeCode(), vo.typeName(), vo.category(), vo.categoryName(),
                    vo.baseRate() == null ? null : vo.baseRate().toPlainString(),
                    vo.baseRatePercent() == null ? null : vo.baseRatePercent().toPlainString(),
                    vo.minAmount() == null ? null : vo.minAmount().toPlainString(),
                    vo.maxAmount() == null ? null : vo.maxAmount().toPlainString(),
                    vo.status(),
                    vo.status() == null ? "未知" : (vo.status() == 1 ? "启用" : "停用"),
                    vo.description(),
                    vo.isDeleted(), vo.deletedAt() == null ? null : vo.deletedAt().toString()));
        }

        Map<String, Object> parts = new LinkedHashMap<>();
        parts.put("keyword", keyword == null ? "不限" : keyword);
        parts.put("category", category == null ? "不限" : category);
        parts.put("status", status == null ? "不限" : status);
        parts.put("includeDeleted", Boolean.TRUE.equals(includeDeleted));
        parts.put("limit", effectiveLimit);
        String dataSource = DataSourceText.of("险种配置", parts);
        log.info("Tool queryInsuranceType 执行完成 keyword={} category={} 命中={} total={}",
                keyword, category, items.size(), page.total());
        return new InsuranceTypeQueryToolResult(page.total(), items, ToolResultMeta.ok(dataSource));
    }

    private static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
