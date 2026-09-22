package com.guarantee.ai.tool;

import com.guarantee.common.security.Permissions;
import com.guarantee.common.security.SensitiveFieldMasker;
import com.guarantee.system.dto.UserDto;
import com.guarantee.system.service.UserService;
import com.guarantee.system.vo.UserVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code queryUser}（SYS-Q-03）：用户查询。
 *
 * <p><b>字段分级脱敏</b>（SYS-P-11）：ADMIN / OPERATOR 得到掩码后的 phone/email；
 * ANALYST / VIEWER **完全不含** phone / email / lastLoginAt。
 * 白名单式组装，禁止直接序列化 VO（RK-08）。</p>
 *
 * <p><b>禁止返回</b>：password（VO 本身就没有该字段）。</p>
 */
@Component
public class UserQueryTool {

    private static final Logger log = LoggerFactory.getLogger(UserQueryTool.class);

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final UserService userService;
    private final AiDataScopeResolver scopeResolver;

    public UserQueryTool(UserService userService, AiDataScopeResolver scopeResolver) {
        this.userService = userService;
        this.scopeResolver = scopeResolver;
    }

    @Tool(name = "queryUser",
            description = """
                    查询用户账号：账号、姓名、所属机构与部门、已分配角色、状态、创建时间。
                    当用户询问"有哪些用户""某机构/部门下有哪些人""某角色有哪些人""哪些用户很久没登录"时使用本工具。
                    可用 lastLoginBefore 找出在此之前未登录过（含从未登录）的账号，例如"三个月没登录"。
                    安全说明：手机号与邮箱在服务端已脱敏（形如 138****5678），部分账号角色只能看到账号/姓名/机构/部门/角色/状态。
                    默认**不包含已删除用户**（逻辑删除：删除后默认不可见）。用户要看已删除数据
                    （「显示已删除」）时传 includeDeleted=true；该参数需要 system:user:delete 权限，
                    无权限时工具会返回明确的无权限说明，此时不得猜测或编造已删除数据。
                    返回值中的 isDeleted/deletedAt 用于区分"已删除"与"未删除"：
                    对 isDeleted=true 的记录**不要**再提出删除或其他变更提案。
                    若命中多个用户，返回值会包含多个候选，请列给用户确认，不要猜测目标。""")
    public UserQueryToolResult queryUser(
            @ToolParam(description = "账号或姓名的模糊关键字，例如 user0123 或 张。不传表示不限", required = false)
            String keyword,
            @ToolParam(description = "机构 ID。不传表示不限", required = false)
            Long orgId,
            @ToolParam(description = "部门 ID。不传表示不限", required = false)
            Long deptId,
            @ToolParam(description = "角色编码，例如 ANALYST。不传表示不限", required = false)
            String roleCode,
            @ToolParam(description = "状态：1=启用，0=停用。不传表示不限", required = false)
            Integer status,
            @ToolParam(description = "最近登录早于该日期（yyyy-MM-dd，含从未登录的账号）。不传表示不限", required = false)
            String lastLoginBefore,
            @ToolParam(description = "仅查从未登录过的账号。默认 false", required = false)
            Boolean neverLoggedIn,
            @ToolParam(description = "是否包含已删除用户（「显示已删除」）。默认 false；"
                    + "需要 system:user:delete 权限，无权限时会被明确拒绝", required = false)
            Boolean includeDeleted,
            @ToolParam(description = "返回条数上限，默认 20，最大 50", required = false)
            Integer limit,
            ToolContext toolContext) {

        if (!AiPermissionGuard.allowed(toolContext, Permissions.USER_VIEW)) {
            return UserQueryToolResult.denied(AiPermissionGuard.deniedReason(Permissions.USER_VIEW));
        }
        // 「显示已删除」的参数级权限判定（设计 §7.4 / §7.1）：无 :delete 权限时明确报错，
        // 绝不静默忽略 true（SYS-Q-11：无权限不得伪装成空结果）
        if (Boolean.TRUE.equals(includeDeleted)
                && !AiPermissionGuard.allowed(toolContext, Permissions.USER_DELETE)) {
            return UserQueryToolResult.denied(
                    AiPermissionGuard.includeDeletedDeniedReason(Permissions.USER_DELETE));
        }

        LocalDateTime loginBefore = parseDate(lastLoginBefore);
        int effectiveLimit = clampLimit(limit);
        var scope = scopeResolver.resolve(toolContext);
        boolean canSeeContact = ToolFieldPolicy.canSeeContact(AiPermissionGuard.roles(toolContext));

        UserDto.Query query = new UserDto.Query();
        query.setKeyword(keyword);
        query.setOrgId(orgId);
        query.setDeptId(deptId);
        query.setRoleCode(roleCode);
        query.setStatus(status);
        query.setLastLoginBefore(loginBefore);
        query.setNeverLoggedIn(neverLoggedIn);
        query.setIncludeDeleted(Boolean.TRUE.equals(includeDeleted));
        query.setPageNum(1);
        query.setPageSize(effectiveLimit);

        var page = userService.page(query, scope);
        List<UserQueryToolResult.UserItem> items = new ArrayList<>();
        for (UserVO vo : page.list()) {
            items.add(toItem(vo, canSeeContact));
        }

        boolean ambiguous = items.size() > 1 && keyword != null && !keyword.isBlank();
        String dataSource = buildDataSource(keyword, orgId, deptId, roleCode, status, lastLoginBefore,
                neverLoggedIn, includeDeleted, effectiveLimit, scope.description(), canSeeContact);
        log.info("Tool queryUser 执行完成 keyword={} 命中={} total={} canSeeContact={} scope={}",
                keyword, items.size(), page.total(), canSeeContact, scope.description());

        return new UserQueryToolResult(page.total(), items, ambiguous,
                ambiguous ? "关键字命中多个用户，请向用户列出候选并确认目标，不要自行选择" : null,
                ToolResultMeta.ok(dataSource));
    }

    /**
     * 白名单式组装（RK-08）。
     *
     * <p>字段是**逐个挑出来**的，而不是把 VO 序列化后再删。这样即使将来 VO 新增了
     * 敏感字段，也不会自动泄漏给模型。</p>
     */
    private static UserQueryToolResult.UserItem toItem(UserVO vo, boolean canSeeContact) {
        return new UserQueryToolResult.UserItem(
                vo.getId(),
                vo.getUsername(),
                vo.getRealName(),
                vo.getOrgId(),
                vo.getOrgName(),
                vo.getDeptId(),
                vo.getDeptName(),
                vo.getRoleCodes(),
                vo.getRoleNames(),
                vo.getStatus(),
                vo.getStatus() == null ? "未知" : (vo.getStatus() == 1 ? "启用" : "停用"),
                // ADMIN / OPERATOR：服务端掩码后的手机号；其余角色恒为 null
                canSeeContact ? SensitiveFieldMasker.maskValue("phone", vo.getPhone()) : null,
                canSeeContact ? SensitiveFieldMasker.maskValue("email", vo.getEmail()) : null,
                canSeeContact && vo.getLastLoginAt() != null ? vo.getLastLoginAt().toString() : null,
                vo.getCreatedAt() == null ? null : vo.getCreatedAt().toLocalDate().toString(),
                vo.getIsDeleted(), vo.getDeletedAt() == null ? null : vo.getDeletedAt().toString());
    }

    private static LocalDateTime parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim()).atStartOfDay();
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(
                    "lastLoginBefore 必须是 yyyy-MM-dd 格式的明确日期，实际收到: " + raw);
        }
    }

    private static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static String buildDataSource(String keyword, Long orgId, Long deptId, String roleCode,
                                          Integer status, String lastLoginBefore, Boolean neverLoggedIn,
                                          Boolean includeDeleted, int limit, String scope,
                                          boolean canSeeContact) {
        Map<String, Object> parts = new LinkedHashMap<>();
        parts.put("keyword", keyword == null ? "不限" : keyword);
        parts.put("orgId", orgId == null ? "不限" : orgId);
        parts.put("deptId", deptId == null ? "不限" : deptId);
        parts.put("roleCode", roleCode == null ? "不限" : roleCode);
        parts.put("status", status == null ? "不限" : status);
        parts.put("lastLoginBefore", lastLoginBefore == null ? "不限" : lastLoginBefore);
        parts.put("neverLoggedIn", Boolean.TRUE.equals(neverLoggedIn));
        parts.put("includeDeleted", Boolean.TRUE.equals(includeDeleted));
        parts.put("limit", limit);
        parts.put("字段集", canSeeContact ? "含脱敏手机号/邮箱" : "仅账号/姓名/机构/部门/角色/状态");
        parts.put("数据范围", scope);
        return "queryUser(" + OrgQueryTool.render(parts) + ")";
    }
}
