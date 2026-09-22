package com.guarantee.ai.tool;

import com.guarantee.common.security.Permissions;
import com.guarantee.common.security.Roles;
import com.guarantee.system.dto.RoleDto;
import com.guarantee.system.entity.SysPermission;
import com.guarantee.system.entity.SysRole;
import com.guarantee.system.service.RoleService;
import com.guarantee.system.vo.RoleVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code queryRole}（SYS-Q-04）：角色与权限查询。
 *
 * <p>三种模式：查角色 / 查权限主数据 / 查"某角色有哪些权限"。</p>
 *
 * <p><b>ADMIN 角色的明细查询需要 {@code system:role:view}</b>；无权限时返回明确的
 * 无权限说明，而不是空结果。</p>
 */
@Component
public class RoleQueryTool {

    private static final Logger log = LoggerFactory.getLogger(RoleQueryTool.class);

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final RoleService roleService;
    private final AiDataScopeResolver scopeResolver;

    public RoleQueryTool(RoleService roleService, AiDataScopeResolver scopeResolver) {
        this.roleService = roleService;
        this.scopeResolver = scopeResolver;
    }

    @Tool(name = "queryRole",
            description = """
                    查询角色与权限配置，支持三种 mode：
                    - ROLE：查角色列表（编码、名称、描述、状态、权限数、用户数）。用户问"有哪些角色""某角色下有多少人"时用。
                    - PERMISSION：查权限主数据（权限编码、名称、类型 MENU/BUTTON/API、路由）。用户问"系统有哪些权限"时用。
                    - ROLE_PERMISSION：查某角色具体有哪些权限，需要传 roleCode。用户问"analyst 角色有哪些权限"时用。
                    权限主数据只读，不存在新增/修改权限的能力（也不存在删除权限的入口）。
                    ROLE 模式下默认**不包含已删除角色**（逻辑删除：删除后默认不可见）。用户要看已删除数据
                    （「显示已删除」）时传 includeDeleted=true；该参数需要 system:role:delete 权限，
                    无权限时工具会返回明确的无权限说明，此时不得猜测或编造已删除数据。
                    返回值中的 isDeleted/deletedAt 用于区分"已删除"与"未删除"：
                    对 isDeleted=true 的角色**不要**再提出删除或授权提案。""")
    public RoleQueryToolResult queryRole(
            @ToolParam(description = "查询模式：ROLE / PERMISSION / ROLE_PERMISSION，默认 ROLE", required = false)
            String mode,
            @ToolParam(description = "关键字：角色编码或名称、权限编码或名称的模糊词。不传表示不限", required = false)
            String keyword,
            @ToolParam(description = "角色编码，例如 ANALYST。ROLE_PERMISSION 模式必填", required = false)
            String roleCode,
            @ToolParam(description = "状态：1=启用，0=停用。不传表示不限", required = false)
            Integer status,
            @ToolParam(description = "是否包含已删除角色（「显示已删除」），仅 ROLE 模式生效。默认 false；"
                    + "需要 system:role:delete 权限，无权限时会被明确拒绝", required = false)
            Boolean includeDeleted,
            @ToolParam(description = "返回条数上限，默认 20，最大 50", required = false)
            Integer limit,
            ToolContext toolContext) {

        if (!AiPermissionGuard.allowed(toolContext, Permissions.ROLE_VIEW)) {
            return RoleQueryToolResult.denied(mode, AiPermissionGuard.deniedReason(Permissions.ROLE_VIEW));
        }
        // 「显示已删除」的参数级权限判定（设计 §7.4 / §7.1）：在 mode 分派**之前**判定，
        // 避免"换个 mode 就能绕过权限校验"；无权限时明确报错而不是静默忽略
        if (Boolean.TRUE.equals(includeDeleted)
                && !AiPermissionGuard.allowed(toolContext, Permissions.ROLE_DELETE)) {
            return RoleQueryToolResult.denied(mode,
                    AiPermissionGuard.includeDeletedDeniedReason(Permissions.ROLE_DELETE));
        }

        String normalizedMode = mode == null || mode.isBlank()
                ? "ROLE" : mode.trim().toUpperCase(Locale.ROOT);
        int effectiveLimit = clampLimit(limit);
        var scope = scopeResolver.resolve(toolContext);

        return switch (normalizedMode) {
            case "PERMISSION" -> queryPermissions(keyword, effectiveLimit, scope.description());
            case "ROLE_PERMISSION" -> queryRolePermissions(roleCode, toolContext, scope.description());
            default -> queryRoles(keyword, roleCode, status, includeDeleted, effectiveLimit, scope);
        };
    }

    private RoleQueryToolResult queryRoles(String keyword, String roleCode, Integer status,
                                           Boolean includeDeleted, int limit,
                                           com.guarantee.system.scope.DataScope scope) {
        RoleDto.Query query = new RoleDto.Query();
        query.setKeyword(keyword);
        query.setRoleCode(roleCode != null && !roleCode.isBlank() ? roleCode : keyword);
        query.setRoleName(keyword);
        query.setStatus(status);
        query.setIncludeDeleted(Boolean.TRUE.equals(includeDeleted));
        query.setPageNum(1);
        query.setPageSize(limit);

        var page = roleService.page(query, scope);
        List<RoleQueryToolResult.RoleItem> items = new ArrayList<>();
        for (RoleVO vo : page.list()) {
            items.add(new RoleQueryToolResult.RoleItem(
                    vo.getId(), vo.getRoleCode(), vo.getRoleName(), vo.getDescription(),
                    vo.getStatus(), vo.getStatus() == null ? "未知" : (vo.getStatus() == 1 ? "启用" : "停用"),
                    vo.getPermissionCount(), vo.getUserCount(),
                    vo.getIsDeleted(), vo.getDeletedAt() == null ? null : vo.getDeletedAt().toString()));
        }
        Map<String, Object> parts = new LinkedHashMap<>();
        parts.put("mode", "ROLE");
        parts.put("keyword", keyword == null ? "不限" : keyword);
        parts.put("roleCode", roleCode == null ? "不限" : roleCode);
        parts.put("status", status == null ? "不限" : status);
        parts.put("includeDeleted", Boolean.TRUE.equals(includeDeleted));
        parts.put("limit", limit);
        parts.put("数据范围", scope.description());
        log.info("Tool queryRole(ROLE) 执行完成 keyword={} 命中={} total={}", keyword, items.size(), page.total());
        return new RoleQueryToolResult("ROLE", page.total(), items, List.of(), null,
                ToolResultMeta.ok("queryRole(" + OrgQueryTool.render(parts) + ")"));
    }

    private RoleQueryToolResult queryPermissions(String keyword, int limit, String scope) {
        List<SysPermission> all = roleService.listPermissionEntities();
        List<RoleQueryToolResult.PermissionItem> items = new ArrayList<>();
        for (SysPermission p : all) {
            if (!matches(keyword, p.getPermCode(), p.getPermName())) {
                continue;
            }
            items.add(toPermission(p));
            if (items.size() >= limit) {
                break;
            }
        }
        Map<String, Object> parts = new LinkedHashMap<>();
        parts.put("mode", "PERMISSION");
        parts.put("keyword", keyword == null ? "不限" : keyword);
        parts.put("limit", limit);
        parts.put("数据范围", scope);
        log.info("Tool queryRole(PERMISSION) 执行完成 keyword={} 命中={}", keyword, items.size());
        boolean truncated = items.size() >= limit && all.size() > limit;
        return new RoleQueryToolResult("PERMISSION", items.size(), List.of(), items, null,
                truncated
                        ? ToolResultMeta.truncated("queryRole(" + OrgQueryTool.render(parts) + ")",
                        "权限主数据超过 " + limit + " 条已截断，请缩小关键字范围")
                        : ToolResultMeta.ok("queryRole(" + OrgQueryTool.render(parts) + ")"));
    }

    private RoleQueryToolResult queryRolePermissions(String roleCode, ToolContext toolContext, String scope) {
        if (roleCode == null || roleCode.isBlank()) {
            return new RoleQueryToolResult("ROLE_PERMISSION", 0L, List.of(), List.of(), null,
                    ToolResultMeta.denied("ROLE_PERMISSION 模式必须提供 roleCode（角色编码）"));
        }
        String code = roleCode.trim();
        // ADMIN 角色的明细查询需要 system:role:view；无权限时返回明确的无权限说明而非空结果
        if (Roles.ADMIN.equalsIgnoreCase(code)
                && !AiPermissionGuard.allowed(toolContext, Permissions.ROLE_VIEW)) {
            return RoleQueryToolResult.denied("ROLE_PERMISSION",
                    AiPermissionGuard.deniedReason(Permissions.ROLE_VIEW));
        }
        SysRole role = roleService.findEntityByCode(code);
        if (role == null) {
            Map<String, Object> parts = new LinkedHashMap<>();
            parts.put("mode", "ROLE_PERMISSION");
            parts.put("roleCode", code);
            parts.put("数据范围", scope);
            return new RoleQueryToolResult("ROLE_PERMISSION", 0L, List.of(), List.of(), null,
                    ToolResultMeta.ok("queryRole(" + OrgQueryTool.render(parts) + ")；角色不存在"));
        }
        RoleVO detail = roleService.getById(role.getId());
        List<RoleQueryToolResult.PermissionItem> permissions = new ArrayList<>();
        if (detail.getPermissionIds() != null) {
            for (SysPermission p : roleService.listPermissionsByIds(detail.getPermissionIds())) {
                permissions.add(toPermission(p));
            }
        }
        Map<String, Object> parts = new LinkedHashMap<>();
        parts.put("mode", "ROLE_PERMISSION");
        parts.put("roleCode", code);
        parts.put("数据范围", scope);
        log.info("Tool queryRole(ROLE_PERMISSION) 执行完成 roleCode={} 权限数={}", code, permissions.size());
        return new RoleQueryToolResult("ROLE_PERMISSION", permissions.size(), List.of(), List.of(),
                new RoleQueryToolResult.RolePermissionMapping(detail.getRoleCode(), detail.getRoleName(), permissions),
                ToolResultMeta.ok("queryRole(" + OrgQueryTool.render(parts) + ")"));
    }

    private static RoleQueryToolResult.PermissionItem toPermission(SysPermission p) {
        return new RoleQueryToolResult.PermissionItem(
                p.getId(), p.getPermCode(), p.getPermName(), p.getPermType(), p.getPath(), p.getParentId());
    }

    private static boolean matches(String keyword, String... fields) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String needle = keyword.trim().toLowerCase(Locale.ROOT);
        for (String field : fields) {
            if (field != null && field.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
