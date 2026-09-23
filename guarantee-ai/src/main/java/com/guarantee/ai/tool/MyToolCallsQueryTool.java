package com.guarantee.ai.tool;

import com.guarantee.ai.service.AiConversationService;
import com.guarantee.common.security.Permissions;
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
 * {@code queryMyToolCalls}（SYS-Q-06a）：**自己的** AI 工具调用记录，D-1a 的替代能力。
 *
 * <p><b>为什么需要它</b>：D-1a 回收了 ANALYST 的 {@code system:audit:view}，
 * 但"我最近用过哪些查询工具、有没有失败"这个诉求是合理的。全局审计与自查是
 * **两个不同工具**，不可用前者加参数模拟后者。</p>
 *
 * <p><b>强制范围</b>：数据范围固定为当前用户（服务端从 ToolContext 取 USER_ID），
 * **不接受任何用户维度入参**——即使模型传了别的用户 id，也只会被忽略（见
 * {@code AiToolCallMapper.selectMine} 的 SQL 强制收敛）。</p>
 *
 * <p><b>权限</b>：仅需 {@code ai:chat} + {@code ai:system:query}，
 * **不依赖** {@code system:audit:view}。</p>
 */
@Component
public class MyToolCallsQueryTool {

    private static final Logger log = LoggerFactory.getLogger(MyToolCallsQueryTool.class);

    private static final int DEFAULT_DAYS = 7;
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final AiConversationService conversationService;

    public MyToolCallsQueryTool(AiConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @Tool(name = "queryMyToolCalls",
            description = """
                    查询**你自己**最近使用过的 AI 工具调用记录：工具名、类型（READ/WRITE）、状态（SUCCESS/FAILED）、耗时、结果摘要。
                    当用户询问"我最近用过哪些工具""我之前的查询有没有失败"时使用本工具。
                    重要限制：只能查询当前登录用户自己的记录，不接受任何"查哪个用户"的参数。
                    返回结果不含工具入参原文，只有结果摘要（例如"命中 12 条记录"），这是为了保护敏感字段。
                    如果你需要查看**全局**操作审计（谁改了系统配置），那需要超级管理员权限，本工具无法替代。""")
    public MyToolCallsToolResult queryMyToolCalls(
            @ToolParam(description = "起始日期，格式 yyyy-MM-dd。不传表示最近 7 天", required = false)
            String startDate,
            @ToolParam(description = "结束日期，格式 yyyy-MM-dd。不传表示到今天", required = false)
            String endDate,
            @ToolParam(description = "工具名模糊词，例如 queryOrg。不传表示不限", required = false)
            String toolName,
            @ToolParam(description = "状态：SUCCESS 或 FAILED。不传表示不限", required = false)
            String status,
            @ToolParam(description = "返回条数上限，默认 20，最大 50", required = false)
            Integer limit,
            ToolContext toolContext) {

        // 只需 ai:system:query（不依赖 system:audit:view）
        if (!AiPermissionGuard.allowed(toolContext, Permissions.AI_SYSTEM_QUERY)) {
            return MyToolCallsToolResult.denied(
                    AiPermissionGuard.deniedReason(Permissions.AI_SYSTEM_QUERY));
        }
        Long userId = AiPermissionGuard.userId(toolContext);
        if (userId == null) {
            return MyToolCallsToolResult.denied("无法确定当前登录用户，请重新登录后再试");
        }

        LocalDateTime start;
        LocalDateTime end;
        try {
            end = endDate == null || endDate.isBlank()
                    ? LocalDate.now().atTime(23, 59, 59)
                    : LocalDate.parse(endDate.trim()).atTime(23, 59, 59);
            start = startDate == null || startDate.isBlank()
                    ? end.toLocalDate().minusDays(DEFAULT_DAYS - 1L).atStartOfDay()
                    : LocalDate.parse(startDate.trim()).atStartOfDay();
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(
                    "日期必须是 yyyy-MM-dd 格式的明确日期，实际收到: "
                            + (ex.getParsedString() == null ? "" : ex.getParsedString()));
        }
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("startDate 不能晚于 endDate");
        }

        int effectiveLimit = clampLimit(limit);
        var page = conversationService.listMyToolCalls(userId, start, end, toolName, status, effectiveLimit);

        List<MyToolCallsToolResult.MyToolCallItem> items = new ArrayList<>();
        for (AiConversationService.MyToolCallItem item : page.items()) {
            items.add(new MyToolCallsToolResult.MyToolCallItem(
                    item.id(), item.conversationId(), item.toolName(), item.toolType(),
                    item.status(), item.durationMs(),
                    item.createdAt() == null ? null : item.createdAt().toString(),
                    item.resultSummary()));
        }

        Map<String, Object> parts = new LinkedHashMap<>();
        parts.put("userId", userId);
        parts.put("startDate", start.toLocalDate().toString());
        parts.put("endDate", end.toLocalDate().toString());
        parts.put("toolName", toolName == null ? "不限" : toolName);
        parts.put("status", status == null ? "不限" : status);
        parts.put("limit", effectiveLimit);
        parts.put("数据范围", "仅本人");
        String dataSource = DataSourceText.of("我的工具调用记录", parts);
        log.info("Tool queryMyToolCalls 执行完成 userId={} 命中={} total={}", userId, items.size(), page.total());
        return new MyToolCallsToolResult(page.total(), items, ToolResultMeta.ok(dataSource));
    }

    private static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
