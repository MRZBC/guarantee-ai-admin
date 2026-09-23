package com.guarantee.ai.tool;

import com.guarantee.common.security.Permissions;
import com.guarantee.system.dto.DepartmentDto;
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
 * <p>部门是内部组织单元，服务于「人」；机构是外部出函机构、服务于订单，
 * 因此部门查询**不接受机构维度的筛选**，也不再返回机构字段。</p>
 */
@Component
public class DepartmentQueryTool {

    private static final Logger log = LoggerFactory.getLogger(DepartmentQueryTool.class);

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final DepartmentService departmentService;
    private final AiDataScopeResolver scopeResolver;

    public DepartmentQueryTool(DepartmentService departmentService,
                               AiDataScopeResolver scopeResolver) {
        this.departmentService = departmentService;
        this.scopeResolver = scopeResolver;
    }

    @Tool(name = "queryDepartment",
            description = """
                    查询部门配置：部门编码、名称、上级部门、状态、部门下用户数。
                    当用户询问"有哪些部门""业务受理部在哪""某部门有多少人"时使用本工具。
                    默认**不包含已删除部门**（逻辑删除：删除后默认不可见）。用户要看已删除数据
                    （「显示已删除」）时传 includeDeleted=true；该参数需要 system:dept:delete 权限，
                    无权限时工具会返回明确的无权限说明，此时不得猜测或编造已删除数据。
                    返回值中的 isDeleted/deletedAt 用于区分"已删除"与"未删除"：
                    对 isDeleted=true 的记录**不要**再提出删除或其他变更提案。""")
    public DepartmentQueryToolResult queryDepartment(
            @ToolParam(description = "部门名称或编码的模糊关键字，例如 业务受理部 或 DEPT0001。不传表示不限", required = false)
            String keyword,
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

        DepartmentDto.Query query = new DepartmentDto.Query();
        query.setKeyword(keyword);
        query.setDeptName(keyword);
        query.setDeptCode(keyword);
        query.setStatus(status);
        query.setIncludeDeleted(Boolean.TRUE.equals(includeDeleted));
        query.setPageNum(1);
        query.setPageSize(effectiveLimit);

        var page = departmentService.page(query, scope);
        List<DepartmentQueryToolResult.DeptItem> items = new ArrayList<>();
        for (DepartmentVO vo : page.list()) {
            items.add(new DepartmentQueryToolResult.DeptItem(
                    vo.getId(), vo.getDeptCode(), vo.getDeptName(), vo.getParentId(),
                    vo.getStatus(), statusName(vo.getStatus()),
                    departmentService.countEnabledUsers(vo.getId()),
                    vo.getIsDeleted(), vo.getDeletedAt() == null ? null : vo.getDeletedAt().toString()));
        }

        String dataSource = buildDataSource(keyword, status, includeDeleted,
                effectiveLimit, scope.description());
        log.info("Tool queryDepartment 执行完成 keyword={} 命中={} total={}",
                keyword, items.size(), page.total());
        return new DepartmentQueryToolResult(page.total(), items, ToolResultMeta.ok(dataSource));
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

    private static String buildDataSource(String keyword, Integer status, Boolean includeDeleted,
                                          int limit, String scope) {
        Map<String, Object> parts = new LinkedHashMap<>();
        parts.put("keyword", keyword == null ? "不限" : keyword);
        parts.put("status", status == null ? "不限" : status);
        parts.put("includeDeleted", Boolean.TRUE.equals(includeDeleted));
        parts.put("limit", limit);
        parts.put("数据范围", scope);
        return DataSourceText.of("部门配置", parts);
    }
}
