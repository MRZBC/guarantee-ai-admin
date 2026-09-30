package com.guarantee.ai.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 工具返回值的「业务口径」文本（{@code ToolResultMeta.dataSource}）。
 *
 * <h3>为什么要有这个类</h3>
 * <p>这个串最终是**给业务用户看的正文**（口径行），由服务端在收尾时直接追加，
 * 模型不再负责回显（见 {@code DataSourceClaimGuard.footer}：模型自写的口径行会被剥离，
 * 因为"照抄"与"编造"在文本上无法区分）。因此它必须是人话，不是日志。原先各工具各自拼
 * {@code "queryOrderSummary(orderType=TENDER, startDate=2026-01-01, ...)"} ——
 * 那是函数调用样式的内部语法：工具名对业务用户无意义，参数名是英文代码，值还是枚举码。
 * 用户看到的是"数据库字段说明书"而不是"这批数字是什么口径"。</p>
 *
 * <h3>渲染规则</h3>
 * <ol>
 *   <li>域名打头，条件用 {@code · } 分隔：{@code 订单统计 · 险种：投标保函 · 时间区间：…}；</li>
 *   <li><b>跳过"没有实际生效"的条件</b>（{@code 不限} / {@code null} / {@code false}）。
 *       这是把"参数转储"变成"口径说明"的关键一步——列一堆"不限"只是噪音；</li>
 *   <li>全部条件都未生效时显式写"未加过滤（全量）"，因为"什么都没过滤"本身就是
 *       重要口径（容易被误读为"已经按我的范围过滤了"）；</li>
 *   <li>{@code true} 的布尔量只输出标签本身（"含已删除"），不写 {@code includeDeleted=true}；</li>
 *   <li>{@code status} 的 1/0 译成"启用/停用"。</li>
 * </ol>
 *
 * <p><b>未登记的键不丢弃</b>：标签表里没有的键按原样输出。宁可偶尔露出一个英文键，
 * 也不要静默吞掉一个查询条件——那会让回显的口径与实际执行的查询不一致，
 * 比不好看严重得多。</p>
 */
public final class DataSourceText {

    /** 英文参数名 → 中文口径标签。 */
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("mode", "查询模式"),
            Map.entry("keyword", "关键字"),
            Map.entry("roleCode", "角色编码"),
            Map.entry("status", "状态"),
            Map.entry("includeDeleted", "含已删除"),
            Map.entry("userId", "用户"),
            Map.entry("conversationId", "会话"),
            Map.entry("startDate", "起始日期"),
            Map.entry("endDate", "截止日期"),
            Map.entry("operatorUsername", "操作人"),
            Map.entry("targetType", "目标类型"),
            Map.entry("action", "动作"),
            Map.entry("result", "结果"),
            Map.entry("source", "来源"),
            Map.entry("regionCode", "区域编码"),
            Map.entry("region", "区域"),
            Map.entry("orgLevel", "机构层级"),
            Map.entry("category", "险种类别"),
            Map.entry("deptId", "部门"),
            Map.entry("lastLoginBefore", "最近登录早于"),
            Map.entry("neverLoggedIn", "从未登录"),
            Map.entry("toolName", "工具"),
            Map.entry("orderType", "险种"),
            Map.entry("dimension", "维度"),
            Map.entry("orderBy", "排序依据"),
            Map.entry("orgId", "机构"));

    /** 传输细节，不属于业务口径，一律不展示（截断另由 {@code truncated} 标志表达）。 */
    private static final Set<String> TRANSPORT_KEYS = Set.of("limit");

    /** 表示"该条件没有生效"的占位值：不展示。 */
    private static final String UNAPPLIED = "不限";

    private DataSourceText() {
    }

    /**
     * 生成业务口径文本。
     *
     * @param domain 业务域名（人类可读，如"机构配置"）
     * @param parts  查询条件；键为参数名或中文标签，值为实际取值
     */
    public static String of(String domain, Map<String, Object> parts) {
        List<String> segments = new ArrayList<>();
        segments.add(domain);
        if (parts != null) {
            parts.forEach((key, value) -> {
                if (key == null || TRANSPORT_KEYS.contains(key) || isUnapplied(value)) {
                    return;
                }
                segments.add(describe(key, value));
            });
        }
        if (segments.size() == 1) {
            segments.add("未加过滤（全量）");
        }
        return String.join(" · ", segments);
    }

    /** 便捷构造：按顺序放入条件。 */
    public static Map<String, Object> parts(Object... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("键值必须成对出现");
        }
        Map<String, Object> parts = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            parts.put(String.valueOf(keyValuePairs[i]), keyValuePairs[i + 1]);
        }
        return parts;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static boolean isUnapplied(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof Boolean bool) {
            return !bool;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() || UNAPPLIED.equals(text);
    }

    private static String describe(String key, Object value) {
        String label = LABELS.getOrDefault(key, key);
        // 布尔量只在为 true 时走到这里，标签本身就是完整语义
        if (value instanceof Boolean) {
            return label;
        }
        return label + "：" + display(key, value);
    }

    private static String display(String key, Object value) {
        if ("status".equals(key)) {
            String text = String.valueOf(value).trim();
            if ("1".equals(text)) {
                return "启用";
            }
            if ("0".equals(text)) {
                return "停用";
            }
        }
        /*
          险种类别也要翻译：口径行与「数据摘要」的块标题都直接取自 dataSource，
          而它们都是**给业务用户看的正文**。原先这里原样输出 code，于是用户在口径里看到
          「险种类别：TENDER」——正是提示词第 42 条禁止的那种内部编码（2026-09-30 黄金问题集
          GQ-10 真机实测抓到）。
        */
        if ("category".equals(key)) {
            String text = String.valueOf(value).trim().toUpperCase(Locale.ROOT);
            return switch (text) {
                case "TENDER", "BID" -> "投标担保";
                case "PERFORMANCE", "CONTRACT" -> "履约担保";
                case "QUALITY" -> "质量保证";
                case "ADVANCE" -> "预付款担保";
                case "OWNER" -> "业主支付";
                case "OTHER" -> "其他";
                // 未登记的取值原样回显：宁可露出一个陌生码，也不要静默吞掉一个查询条件
                default -> String.valueOf(value);
            };
        }
        return String.valueOf(value);
    }
}
