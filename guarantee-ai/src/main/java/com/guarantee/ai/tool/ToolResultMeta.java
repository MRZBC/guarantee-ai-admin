package com.guarantee.ai.tool;

/**
 * 工具返回值的通用元信息（SYS-Q-07 / SYS-Q-10 / SYS-Q-11）。
 *
 * <p>三个字段回答三个必须区分的问题：</p>
 * <ul>
 *   <li>{@code denied}：是"无权限"还是"无数据"？——绝不允许把无权限伪装成 0 条；</li>
 *   <li>{@code truncated}：结果是否被截断？——截断必须显式告知，否则模型会基于残缺数据下结论；</li>
 *   <li>{@code dataSource}：本次数据实际来自哪个工具的哪些条件？——服务端收尾时据此生成
 *       正文末尾的口径行（模型自写的口径行会被剥离，见 {@code DataSourceClaimGuard}）。</li>
 * </ul>
 *
 * <p>设计成独立 record 而不是继承体系，是因为工具返回值必须是 record（SYS-Q-07），
 * 而 Java 的 record 不支持继承；组合比继承更适配这个约束。</p>
 */
public record ToolResultMeta(
        boolean denied,
        String deniedReason,
        boolean truncated,
        String truncatedHint,
        String dataSource) {

    /** 正常返回。 */
    public static ToolResultMeta ok(String dataSource) {
        return new ToolResultMeta(false, null, false, null, dataSource);
    }

    /** 无权限：必须给出可读原因，且不得携带任何业务数据。 */
    public static ToolResultMeta denied(String reason) {
        return new ToolResultMeta(true, reason, false, null, null);
    }

    /** 正常返回但结果被截断。 */
    public static ToolResultMeta truncated(String dataSource, String hint) {
        return new ToolResultMeta(false, null, true, hint, dataSource);
    }
}
