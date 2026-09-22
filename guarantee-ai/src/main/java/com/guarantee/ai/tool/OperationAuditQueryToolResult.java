package com.guarantee.ai.tool;

import java.util.List;

/**
 * {@code queryOperationAudit} 的工具返回值（SYS-Q-06 / SYS-Q-07）。
 *
 * <p>全局操作审计**仅 ADMIN 持有 {@code system:audit:view}**（D-1a）；无权限时本工具
 * 根本不会注册，因此 {@code denied} 只作为防御性分支存在（例如内部调用）。</p>
 */
public record OperationAuditQueryToolResult(
        long total,
        List<AuditItem> items,
        /** 参数护栏提示，例如"时间跨度超限，请收窄区间"。 */
        String hint,
        ToolResultMeta meta) {

    /** 单条操作审计。 */
    public record AuditItem(
            Long id,
            String operatedAt,
            String operatorUsername,
            String operatorRealName,
            String source,
            String sourceName,
            String action,
            String targetType,
            Long targetId,
            String targetName,
            String result,
            /** 面向人的摘要，由服务端拼装，避免模型自由解读。 */
            String summary,
            String traceId) {
    }

    public static OperationAuditQueryToolResult denied(String reason) {
        return new OperationAuditQueryToolResult(0L, List.of(), null, ToolResultMeta.denied(reason));
    }

    /** 参数错误（例如时间跨度超限）：返回明确提示而不是异常，让模型能自行收窄后重试。 */
    public static OperationAuditQueryToolResult invalid(String hint) {
        return new OperationAuditQueryToolResult(0L, List.of(), hint, ToolResultMeta.ok(null));
    }
}
