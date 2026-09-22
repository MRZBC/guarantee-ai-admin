package com.guarantee.ai.tool;

import com.guarantee.common.security.Permissions;
import com.guarantee.system.dto.DepartmentDto;
import com.guarantee.system.entity.SysOrg;
import com.guarantee.system.service.DepartmentService;
import com.guarantee.system.service.OrgService;
import com.guarantee.system.vo.DepartmentVO;
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
 * {@code queryDepartment}（SYS-Q-02）：部门查询。
 *
 * <p><b>歧义处理</b>：{@code orgName} 模糊匹配到多个机构时返回 {@code ambiguousOrgs}
 * 并提示模型向用户确认，绝不猜测（SYS-Q-02 / SYS-N-04）。</p>
 */
@Component
public class DepartmentQueryTool {

    private static final Logger log = LoggerFactory.getLogger(DepartmentQueryTool.class);

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final DepartmentService departmentService;
    private final OrgService orgService;
    private final AiDataScopeResolver scopeResolver;

    public DepartmentQueryTool(DepartmentService departmentService,
                               OrgService orgService,
                               AiDataScopeResolver scopeResolver) {
        this.departmentService = departmentService;
        this.orgService = orgService;
        this.scopeResolver = scopeResolver;
    }

    @Tool(name = "queryDepartment",
            description = """
                    查询部门配置：部门编码、名称、所属机构、上级部门、状态、部门下用户数。
                    当用户询问"某机构下有哪些部门""业务受理部在哪些机构下有""某部门有多少人"时使用本工具。
                    机构可以用 orgId 精确指定，也可以用 orgName 模糊指定；若 orgName 命中多个机构，
                    返回值会包含 ambiguousOrgs 候选列表，此时必须先向用户确认是哪个机构，不要自行选择。
                    默认**不包含已删除部门**（逻辑删除：删除后默认不可见）。用户要看已删除数据
                    （「显示已删除」）时传 includeDeleted=true；该参数需要 system:dept:delete 权限，
                    无权限时工具会返回明确的无权限说明，此时不得猜测或编造已删除数据。
                    返回值中的 isDeleted/deletedAt 用于区分"已删除"与"未删除"：
                    对 isDeleted=true 的记录**不要**再提出删除或其他变更提案。""")
    public DepartmentQueryToolResult queryDepartment(
            @ToolParam(description = "部门名称或编码的模糊关键字，例如 业务受理部 或 DEPT0001。不传表示不限", required = false)
            String keyword,
            @ToolParam(description = "机构 ID（精确）。不确定时改用 orgName", required = false)
            Long orgId,
            @ToolParam(description = "机构名称模糊词，例如 浙江省第1保函运营机构。不传表示不限", required = false)
            String orgName,
            @ToolParam(description = "状态：1=启用，0=停用。不传表示不限", required = false)
            Integer status,
            @ToolParam(description = "是否包含已删除部门（「显示已删除」）。默认 false；"
                    + "需要 system:dept:delete 权限，无权限时会被明确拒绝", required = false)
            Boolean includeDeleted,
            @ToolParam(description = "返回条数上限，默认 20，最大 50", required = false)
            Integer limit,
            ToolContext toolContext) {

        if (!AiPermissionGuard.allowed(toolContext, Permissions.DEPT_VIEW)) {
            return DepartmentQueryToolResult.denied(AiPermissionGuard.deniedReason(Permissions.DEPT_VIEW));
        }
        // 「显示已删除」的参数级权限判定（设计 §7.4 / §7.1）：无 :delete 权限时明确报错，
        // 绝不静默忽略 true（SYS-Q-11：无权限不得伪装成空结果）
        if (Boolean.TRUE.equals(includeDeleted)
                && !AiPermissionGuard.allowed(toolContext, Permissions.DEPT_DELETE)) {
            return DepartmentQueryToolResult.denied(
                    AiPermissionGuard.includeDeletedDeniedReason(Permissions.DEPT_DELETE));
        }

        int effectiveLimit = clampLimit(limit);
        var scope = scopeResolver.resolve(toolContext);

        // 机构名歧义处理：命中多个时返回候选并要求澄清，不猜测
        List<DepartmentQueryToolResult.OrgCandidate> ambiguousOrgs = List.of();
        Long resolvedOrgId = orgId;
        if (resolvedOrgId == null && orgName != null && !orgName.isBlank()) {
            List<SysOrg> candidates = orgService.findCandidates(orgName, scope, OrgService.CANDIDATE_LIMIT);
            if (candidates.isEmpty()) {
                return new DepartmentQueryToolResult(0L, List.of(), List.of(), null,
                        ToolResultMeta.ok(buildDataSource(keyword, null, orgName, status, includeDeleted,
                                effectiveLimit, scope.description()) + "；机构名未匹配到任何机构"));
            }
            if (candidates.size() > 1) {
                ambiguousOrgs = candidates.stream().map(DepartmentQueryTool::toCandidate).toList();
                log.info("Tool queryDepartment 机构名歧义 orgName={} 候选={}", orgName, candidates.size());
                return new DepartmentQueryToolResult(0L, List.of(), ambiguousOrgs,
                        "机构名 \"" + orgName + "\" 命中多个机构，请向用户列出候选并确认，不要自行选择",
                        ToolResultMeta.ok(buildDataSource(keyword, null, orgName, status, includeDeleted,
                                effectiveLimit, scope.description())));
            }
            resolvedOrgId = candidates.get(0).getId();
        }

        DepartmentDto.Query query = new DepartmentDto.Query();
        query.setKeyword(keyword);
        query.setDeptName(keyword);
        query.setDeptCode(keyword);
        query.setOrgId(resolvedOrgId);
        query.setStatus(status);
        query.setIncludeDeleted(Boolean.TRUE.equals(includeDeleted));
        query.setPageNum(1);
        query.setPageSize(effectiveLimit);

        var page = departmentService.page(query, scope);
        List<DepartmentQueryToolResult.DeptItem> items = new ArrayList<>();
        for (DepartmentVO vo : page.list()) {
            items.add(new DepartmentQueryToolResult.DeptItem(
                    vo.getId(), vo.getDeptCode(), vo.getDeptName(),
                    vo.getOrgId(), vo.getOrgName(), vo.getParentId(),
                    vo.getStatus(), statusName(vo.getStatus()),
                    departmentService.countEnabledUsers(vo.getId()),
                    vo.getIsDeleted(), vo.getDeletedAt() == null ? null : vo.getDeletedAt().toString()));
        }

        String dataSource = buildDataSource(keyword, resolvedOrgId, orgName, status, includeDeleted,
                effectiveLimit, scope.description());
        log.info("Tool queryDepartment 执行完成 keyword={} orgId={} 命中={} total={}",
                keyword, resolvedOrgId, items.size(), page.total());
        return new DepartmentQueryToolResult(page.total(), items, List.of(), null, ToolResultMeta.ok(dataSource));
    }

    private static DepartmentQueryToolResult.OrgCandidate toCandidate(SysOrg org) {
        return new DepartmentQueryToolResult.OrgCandidate(
                org.getId(), org.getOrgCode(), org.getOrgName(), org.getRegionName(),
                org.getOrgLevel(), OrgService.levelName(org.getOrgLevel()));
    }

    private static String statusName(Integer status) {
        return OrgService.statusName(status);
    }

    private static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static String buildDataSource(String keyword, Long orgId, String orgName,
                                          Integer status, Boolean includeDeleted, int limit, String scope) {
        Map<String, Object> parts = new LinkedHashMap<>();
        parts.put("keyword", keyword == null ? "不限" : keyword);
        parts.put("orgId", orgId == null ? "不限" : orgId);
        parts.put("orgName", orgName == null ? "不限" : orgName);
        parts.put("status", status == null ? "不限" : status);
        parts.put("includeDeleted", Boolean.TRUE.equals(includeDeleted));
        parts.put("limit", limit);
        parts.put("数据范围", scope);
        return "queryDepartment(" + OrgQueryTool.render(parts) + ")";
    }
}
