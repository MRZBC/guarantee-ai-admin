package com.guarantee.ai.tool;

import com.guarantee.ai.entity.AiOperationAudit;
import com.guarantee.ai.dto.OperationAuditQuery;
import com.guarantee.ai.service.OperationAuditService;
import com.guarantee.common.security.Permissions;
import com.guarantee.common.security.Roles;
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
 * {@code queryOperationAudit}（SYS-Q-06）：全局操作审计查询，**仅 ADMIN**（D-1a）。
 *
 * <p>三点约束必须同时成立：</p>
 * <ol>
 *   <li>无 {@code system:audit:view} 的用户**本工具不注册**（SYS-P-12a），
 *       模型不会尝试调用，也不会看到"存在但没权限"的信号；</li>
 *   <li>时间条件必填且 ≤90 天（SYS-A-17），超出时返回参数错误并提示收窄；</li>
 *   <li>数据范围规则在 Service 中显式实现：USER/ORG/DEPT 按机构过滤，
 *       ROLE/PERMISSION 类无机构归属（SYS-A-10）。</li>
 * </ol>
 */
@Component
public class OperationAuditQueryTool {

    private static final Logger log = LoggerFactory.getLogger(OperationAuditQueryTool.class);

    private final OperationAuditService auditService;
    private final AiDataScopeResolver scopeResolver;

    public OperationAuditQueryTool(OperationAuditService auditService, AiDataScopeResolver scopeResolver) {
        this.auditService = auditService;
        this.scopeResolver = scopeResolver;
    }

    @Tool(name = "queryOperationAudit",
            description = """
                    查询全局操作审计：谁在什么时间、通过什么渠道（AI 助手确认 / WEB 页面）、把哪个对象的什么字段改成了什么值、结果如何。
                    当用户询问"最近有哪些配置被改动过""谁停用了某个用户""某个变更的前后值"时使用本工具。
                    时间区间必填，格式 yyyy-MM-dd，且**跨度最大 90 天**；超限会返回参数错误，请收窄区间后重试。
                    注意：审计中敏感字段（手机号、邮箱等）只记录"是否变更"，不记录具体值，这是系统设计而非数据缺失。
                    安全提示：全局审计仅超级管理员可见；若你的工具列表中没有本工具，说明你的账号未开通该能力。""")
    public OperationAuditQueryToolResult queryOperationAudit(
            @ToolParam(description = "起始日期，格式 yyyy-MM-dd（含当天）。必填", required = false)
            String startDate,
            @ToolParam(description = "结束日期，格式 yyyy-MM-dd（含当天）。必填", required = false)
            String endDate,
            @ToolParam(description = "操作人账号模糊词。不传表示不限", required = false)
            String operatorUsername,
            @ToolParam(description = "目标类型：USER / ORG / DEPT / ROLE / PERMISSION / INSURANCE_TYPE。不传表示不限", required = false)
            String targetType,
            @ToolParam(description = "动作：CREATE / UPDATE / ENABLE / DISABLE / ASSIGN_ROLES / ASSIGN_PERMISSIONS。不传表示不限", required = false)
            String action,
            @ToolParam(description = "执行结果：SUCCESS / FAILED / REJECTED / EXPIRED / PARTIAL。不传表示不限", required = false)
            String result,
            @ToolParam(description = "渠道：AI（助手确认）/ WEB（页面直连）。不传表示不限", required = false)
            String source,
            @ToolParam(description = "返回条数上限，默认 50，最大 200", required = false)
            Integer limit,
            ToolContext toolContext) {

        if (!AiPermissionGuard.allowed(toolContext, Permissions.AUDIT_VIEW)) {
            return OperationAuditQueryToolResult.denied(
                    AiPermissionGuard.deniedReason(Permissions.AUDIT_VIEW));
        }

        LocalDateTime start;
        LocalDateTime end;
        try {
            start = parseStart(startDate);
            end = parseEnd(endDate);
        } catch (IllegalArgumentException ex) {
            return OperationAuditQueryToolResult.invalid(ex.getMessage());
        }
        if (start == null || end == null) {
            return OperationAuditQueryToolResult.invalid(
                    "查询操作审计必须提供 startDate 与 endDate（yyyy-MM-dd），请先确定明确的时间区间");
        }
        // 跨度护栏：在调用 Service 之前先给出可读提示，让模型能自行收窄而不是抛异常
        long days = java.time.Duration.between(start, end).toDays();
        if (days > OperationAuditService.MAX_RANGE_DAYS) {
            return OperationAuditQueryToolResult.invalid(
                    "时间跨度 " + days + " 天超过上限 " + OperationAuditService.MAX_RANGE_DAYS
                            + " 天，请收窄区间（例如最近 30 天）后重试");
        }

        OperationAuditQuery query = new OperationAuditQuery();
        query.setStartDate(start);
        query.setEndDate(end);
        query.setOperatorUsername(operatorUsername);
        query.setTargetType(targetType);
        query.setAction(action);
        query.setResult(result);
        query.setSource(source);
        query.setLimit(limit);

        var scope = scopeResolver.resolve(toolContext);
        boolean admin = AiPermissionGuard.roles(toolContext).contains(Roles.ADMIN);

        OperationAuditService.AuditPage page;
        try {
            page = auditService.query(query, admin, scope);
        } catch (com.guarantee.common.exception.BizException ex) {
            // 护栏拒绝（时间条件/权限）统一转成可读提示，让模型如实告知用户
            return OperationAuditQueryToolResult.invalid(ex.getMessage());
        }

        List<OperationAuditQueryToolResult.AuditItem> items = new ArrayList<>();
        for (AiOperationAudit audit : page.items()) {
            items.add(toItem(audit));
        }

        Map<String, Object> parts = new LinkedHashMap<>();
        parts.put("startDate", startDate);
        parts.put("endDate", endDate);
        parts.put("operatorUsername", operatorUsername == null ? "不限" : operatorUsername);
        parts.put("targetType", targetType == null ? "不限" : targetType);
        parts.put("action", action == null ? "不限" : action);
        parts.put("result", result == null ? "不限" : result);
        parts.put("source", source == null ? "不限" : source);
        parts.put("limit", OperationAuditService.clampLimit(limit));
        parts.put("数据范围", scope.description());
        String dataSource = DataSourceText.of("操作审计", parts);
        log.info("Tool queryOperationAudit 执行完成 {}~{} 命中={} total={}",
                startDate, endDate, items.size(), page.total());
        return new OperationAuditQueryToolResult(page.total(), items, null, ToolResultMeta.ok(dataSource));
    }

    private static OperationAuditQueryToolResult.AuditItem toItem(AiOperationAudit audit) {
        return new OperationAuditQueryToolResult.AuditItem(
                audit.getId(),
                audit.getOperatedAt() == null ? null : audit.getOperatedAt().toString(),
                audit.getOperatorUsername(),
                audit.getOperatorRealName(),
                audit.getSource(),
                "AI".equals(audit.getSource()) ? "助手确认" : "页面直连",
                audit.getAction(),
                audit.getTargetType(),
                audit.getTargetId(),
                audit.getTargetName(),
                audit.getResult(),
                buildSummary(audit),
                audit.getTraceId());
    }

    /**
     * 面向人的摘要。
     *
     * <p>刻意由服务端拼装而不是让模型自由解读原始字段：审计是"谁改了什么"的事实记录，
     * 摘要措辞不一致会让人误以为记录本身不一致。</p>
     */
    private static String buildSummary(AiOperationAudit audit) {
        StringBuilder sb = new StringBuilder();
        sb.append(audit.getOperatorRealName() == null ? audit.getOperatorUsername() : audit.getOperatorRealName())
                .append(" 通过 ").append("AI".equals(audit.getSource()) ? "助手" : "页面")
                .append(" 对 ").append(targetTypeName(audit.getTargetType()))
                .append('(').append(audit.getTargetName() == null ? audit.getTargetId() : audit.getTargetName())
                .append(") 执行 ").append(actionName(audit.getAction()))
                .append("，结果 ").append(resultName(audit.getResult()));
        if (audit.getChangedFields() != null && !audit.getChangedFields().isBlank()) {
            sb.append("，变更字段：").append(audit.getChangedFields());
        }
        if (audit.getErrorMessage() != null && !audit.getErrorMessage().isBlank()) {
            sb.append("，原因：").append(audit.getErrorMessage());
        }
        return sb.toString();
    }

    private static String targetTypeName(String type) {
        if (type == null) {
            return "未知对象";
        }
        return switch (type) {
            case "USER" -> "用户";
            case "ORG" -> "机构";
            case "DEPT" -> "部门";
            case "ROLE" -> "角色";
            case "PERMISSION" -> "权限";
            case "INSURANCE_TYPE" -> "险种";
            default -> type;
        };
    }

    private static String actionName(String action) {
        if (action == null) {
            return "未知动作";
        }
        return switch (action) {
            case "CREATE" -> "新增";
            case "UPDATE" -> "修改";
            case "ENABLE" -> "启用";
            case "DISABLE" -> "停用";
            case "ASSIGN_ROLES" -> "角色分配";
            case "ASSIGN_PERMISSIONS" -> "权限授权";
            default -> action;
        };
    }

    private static String resultName(String result) {
        if (result == null) {
            return "未知";
        }
        return switch (result) {
            case "SUCCESS" -> "成功";
            case "FAILED" -> "失败";
            case "REJECTED" -> "已拒绝";
            case "EXPIRED" -> "已过期";
            case "PARTIAL" -> "部分成功";
            default -> result;
        };
    }

    private static LocalDateTime parseStart(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim()).atStartOfDay();
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("startDate 必须是 yyyy-MM-dd 格式，实际收到: " + raw);
        }
    }

    private static LocalDateTime parseEnd(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim()).atTime(23, 59, 59);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("endDate 必须是 yyyy-MM-dd 格式，实际收到: " + raw);
        }
    }
}
