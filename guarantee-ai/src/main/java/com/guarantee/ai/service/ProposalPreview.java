package com.guarantee.ai.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 提案的预览载荷（落库于 {@code ai_operation_proposal.preview_payload}）。
 *
 * <p>与 {@link ProposalPayload} 的区别：本类只承载"变更内容与影响面"，
 * 不含提案状态、时间戳等运行期字段，因此可以原样持久化并在任意时刻还原确认卡
 * （SYS-C-14：刷新页面后必须能恢复渲染）。</p>
 */
public record ProposalPreview(
        String summary,
        List<ChangeItem> changes,
        List<String> impact,
        List<String> warnings,
        boolean dangerous,
        /** 恢复确认卡所需的附加上下文（例如目标展示名）。 */
        Map<String, Object> extra) {

    /** 单个字段的变更明细。 */
    public record ChangeItem(String field, String label, String before, String after) {

        /** 新增动作：只有新值。 */
        public static ChangeItem created(String field, String label, String after) {
            return new ChangeItem(field, label, null, after);
        }

        /** 删除/清空动作：只有原值。 */
        public static ChangeItem removed(String field, String label, String before) {
            return new ChangeItem(field, label, before, null);
        }
    }

    public static ProposalPreview of(String summary, List<ChangeItem> changes,
                                     List<String> impact, List<String> warnings, boolean dangerous) {
        return new ProposalPreview(summary, changes, impact, warnings, dangerous, Map.of());
    }

    /**
     * 把「影响面」Map 渲染成可以直接给用户看的一行文案。
     *
     * <p><b>为什么不能直接做字符串拼接</b>：{@code "影响面：" + impactMap} 会隐式调用
     * {@link Object#toString()}，也就是 {@link Map#toString()}，于是确认卡上会原样出现
     * 「影响面：{引用订单数=44064}」这种 Java 集合的内部表示——花括号和等号是
     * {@code AbstractMap} 的实现细节，不是业务文案，暴露给用户是缺陷（真机截图确认过）。
     * 本方法把每个条目渲染成「键 + 空格 + 值」，从而彻底去掉这对括号。</p>
     *
     * <p>渲染规则：</p>
     * <ul>
     *   <li>{@code null} 或空 Map → {@code "无"}；</li>
     *   <li>单个条目 → 「键 值」，例如内容为 引用订单数=44064 的 Map → {@code 引用订单数 44064}；</li>
     *   <li>多个条目按 Map 的迭代顺序（调用方多为 {@link java.util.LinkedHashMap}，即业务定义顺序）
     *       用「；」连接：{@code DepartmentService#stopImpact} 依次放入「下级部门数」「部门下启用用户数」，
     *       故渲染为 {@code 下级部门数 2；部门下启用用户数 3}；</li>
     *   <li>值本身为 {@code null} → 渲染成 {@code "-"}，不把字面量 {@code null} 暴露给用户。</li>
     * </ul>
     *
     * <p>注意：本方法只渲染 Map 的内容，<b>不含</b> {@code "影响面："} 前缀。前缀由调用方显式保留，
     * 因为前端确认卡正文直接渲染这些字符串并依赖该前缀做展示。</p>
     *
     * @param impact 影响面条目（键是面向用户的中文名，值是计数/名称等）；允许为 {@code null}
     * @return 可直接放进 {@link #impact()} 的用户可读文案，永不为 {@code null}
     */
    public static String formatImpact(Map<String, ?> impact) {
        if (impact == null || impact.isEmpty()) {
            return "无";
        }
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, ?> entry : impact.entrySet()) {
            if (text.length() > 0) {
                text.append("；");
            }
            text.append(entry.getKey()).append(' ').append(formatImpactValue(entry.getValue()));
        }
        return text.toString();
    }

    /**
     * 渲染单个影响面取值。
     *
     * <p><b>集合必须展开</b>：{@code List} 直接拼接会漏出 {@code [ADMIN, USER]}，
     * 与 {@code {k=v}} 是同一类"Java 内部表示出现在用户界面"的缺陷
     * （{@code UserService.stopImpact}/{@code assignRolesImpact} 的
     * "持有角色 / 当前角色 / 变更后角色"值都是 {@code List<String>}）。
     * 这里统一渲染成 {@code ADMIN、USER}。</p>
     */
    public static String formatImpactValue(Object value) {
        if (value == null) {
            return "-";
        }
        if (value instanceof Collection<?> items) {
            if (items.isEmpty()) {
                return "-";
            }
            return items.stream()
                    .map(item -> item == null ? "-" : String.valueOf(item))
                    .collect(Collectors.joining("、"));
        }
        return String.valueOf(value);
    }
}
