package com.guarantee.ai.tool;

import com.guarantee.common.security.Permissions;
import com.guarantee.system.dto.OrgDto;
import com.guarantee.system.service.OrgService;
import com.guarantee.system.vo.OrgVO;
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
 * {@code queryOrg}（SYS-Q-01）：机构查询。
 *
 * <p><b>分层约束</b>（SYS-Q-08）：只依赖 {@code OrgService}，严禁注入 Mapper、严禁拼 SQL。</p>
 *
 * <p><b>歧义处理</b>（SYS-Q-01 特别要求）：keyword 命中多个机构时返回**全量候选**，
 * 由模型向用户澄清，不得只取第一条。</p>
 */
@Component
public class OrgQueryTool {

    private static final Logger log = LoggerFactory.getLogger(OrgQueryTool.class);

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final OrgService orgService;
    private final AiDataScopeResolver scopeResolver;

    public OrgQueryTool(OrgService orgService, AiDataScopeResolver scopeResolver) {
        this.orgService = orgService;
        this.scopeResolver = scopeResolver;
    }

    @Tool(name = "queryOrg",
            description = """
                    查询保函运营机构的配置信息：机构编码、名称、行政区划、层级（1总部/2省级/3市级）、上级机构、状态。
                    当用户询问"有哪些机构""某省有几个机构""XX 机构下面有哪些下级机构"时使用本工具。
                    结果已按当前用户的数据范围过滤：省级用户只能看到本省机构，市级用户只能看到本市机构。
                    默认**不包含已删除机构**（逻辑删除：删除后默认不可见）。用户要看已删除数据
                    （「显示已删除」）时传 includeDeleted=true；该参数需要 system:org:delete 权限，
                    无权限时工具会返回明确的无权限说明，此时不得猜测或编造已删除数据。
                    返回值中的 isDeleted/deletedAt 用于区分"已删除"与"未删除"：
                    对 isDeleted=true 的记录**不要**再提出删除或其他变更提案。
                    若 keyword 命中多个机构，返回值会包含全部候选，请把它们列给用户确认，不要猜测。""")
    public OrgQueryToolResult queryOrg(
            @ToolParam(description = "机构名称或编码的模糊关键字，例如 浙江省 或 ORG3301。不传表示不限", required = false)
            String keyword,
            @ToolParam(description = "行政区划编码，例如 330000 表示浙江省。不传表示不限", required = false)
            String regionCode,
            @ToolParam(description = "机构层级：1=总部，2=省级，3=市级。不传表示不限", required = false)
            Integer orgLevel,
            @ToolParam(description = "状态：1=启用，0=停用。不传表示不限", required = false)
            Integer status,
            @ToolParam(description = "是否包含已删除机构（「显示已删除」）。默认 false；"
                    + "需要 system:org:delete 权限，无权限时会被明确拒绝", required = false)
            Boolean includeDeleted,
            @ToolParam(description = "返回条数上限，默认 20，最大 50", required = false)
            Integer limit,
            ToolContext toolContext) {

        if (!AiPermissionGuard.allowed(toolContext, Permissions.ORG_VIEW)) {
            return OrgQueryToolResult.denied(AiPermissionGuard.deniedReason(Permissions.ORG_VIEW));
        }
        // 「显示已删除」的参数级权限判定（设计 §7.4 / §7.1）：无 :delete 权限时**明确报错**，
        // 绝不静默把 true 当 false——静默降级会让用户以为"没有已删除数据"，实际是"看不到"
        if (Boolean.TRUE.equals(includeDeleted)
                && !AiPermissionGuard.allowed(toolContext, Permissions.ORG_DELETE)) {
            return OrgQueryToolResult.denied(
                    AiPermissionGuard.includeDeletedDeniedReason(Permissions.ORG_DELETE));
        }

        int effectiveLimit = clampLimit(limit);
        OrgDto.Query query = new OrgDto.Query();
        query.setOrgName(keyword);
        query.setOrgCode(keyword);
        query.setRegionCode(regionCode);
        query.setOrgLevel(orgLevel);
        query.setStatus(status);
        query.setIncludeDeleted(Boolean.TRUE.equals(includeDeleted));
        query.setPageNum(1);
        query.setPageSize(effectiveLimit);

        var scope = scopeResolver.resolve(toolContext);
        long total = orgService.page(query, scope).total();
        // 本工具需要 parentName，且"命中多个要返回全量候选"，
        // 因此用 Service 的查询方法而不是分页结果（口径由 Service 统一）。
        List<OrgVO> list = orgService.listForQuery(query, scope);

        List<OrgQueryToolResult.OrgItem> items = new ArrayList<>();
        for (OrgVO vo : list) {
            items.add(toItem(vo));
        }
        boolean ambiguous = items.size() > 1 && keyword != null && !keyword.isBlank();
        String dataSource = buildDataSource(keyword, regionCode, orgLevel, status, includeDeleted,
                effectiveLimit, scope.description());
        log.info("Tool queryOrg 执行完成 keyword={} 命中={} total={} scope={}",
                keyword, items.size(), total, scope.description());

        return new OrgQueryToolResult(total, items, ambiguous,
                ambiguous ? "关键字命中多个机构，请向用户列出候选并确认目标，不要自行选择" : null,
                ToolResultMeta.ok(dataSource));
    }

    private static OrgQueryToolResult.OrgItem toItem(OrgVO vo) {
        return new OrgQueryToolResult.OrgItem(
                vo.getId(), vo.getOrgCode(), vo.getOrgName(),
                vo.getRegionCode(), vo.getRegionName(),
                vo.getOrgLevel(), OrgService.levelName(vo.getOrgLevel()),
                vo.getParentId(), vo.getParentName(),
                vo.getStatus(), OrgService.statusName(vo.getStatus()),
                vo.getIsDeleted(), vo.getDeletedAt() == null ? null : vo.getDeletedAt().toString());
    }

    static int clampLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static String buildDataSource(String keyword, String regionCode, Integer orgLevel,
                                          Integer status, Boolean includeDeleted, int limit, String scope) {
        Map<String, Object> parts = new LinkedHashMap<>();
        parts.put("keyword", keyword == null ? "不限" : keyword);
        parts.put("regionCode", regionCode == null ? "不限" : regionCode);
        parts.put("orgLevel", orgLevel == null ? "不限" : orgLevel);
        parts.put("status", status == null ? "不限" : status);
        parts.put("includeDeleted", Boolean.TRUE.equals(includeDeleted));
        parts.put("limit", limit);
        parts.put("数据范围", scope);
        return DataSourceText.of("机构配置", parts);
    }
}
