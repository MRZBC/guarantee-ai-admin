package com.guarantee.ai.tool;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具返回值里**可渲染指标**的提取与渲染（数值溯源的服务端出口）。
 *
 * <p><b>解决什么问题</b>：数字是编造里危害最大的一类——用户会把"本季度 12 笔订单"当成
 * 有依据的统计。口径行只证明了"数据从哪来"，证明不了"正文里那个数字就是工具返回的数字"。
 * 本类把工具返回值里的**已知指标**渲染成一段服务端生成的内容，直接接在回答末尾：
 * 用户看到的权威数值不再经过模型的手，模型正文只负责解读。</p>
 *
 * <p><b>只渲染"已登记"的字段，其余一律不猜</b>：字段名 → 中文标签 + 单位写死在
 * {@link #SCALARS} / {@link #ITEM_COLUMNS} 里。没有登记过的字段（哪怕是数字）不渲染，
 * 因为"这个字段该用什么中文名、什么单位"是无从推断的——猜错比不显示更糟
 * （例如把费率当成金额）。新增工具若带了新指标，在这里登记一行即可。</p>
 *
 * <p><b>只取顶层标量 + 一张维度明细表</b>：标量取顶层字段，因此不会把"列表里每条明细的
 * 订单量"与"汇总的订单量"混在一起（同名不同义的两个数字同时出现会让用户更糊涂）；
 * 明细表只认形状明确的数组（元素是对象、含 {@code name} 与至少一个已知数值列），
 * 且最多渲染 {@link #MAX_ROWS} 行。</p>
 *
 * <p><b>金额用两位小数</b>：JSON 反序列化后浮点可能是 {@code Double}（可能带二进制误差）
 * 或 {@code BigDecimal}（精确）。两者都按 {@code HALF_UP} 保留两位，量级远小于
 * double 的精确整数范围，不会出现"多一分钱"的问题。</p>
 */
public final class DataMetrics {

    /** 明细表最多渲染多少行，超出只提示总数。 */
    public static final int MAX_ROWS = 20;

    /** 服务端指标块的固定标题。 */
    public static final String HEADER = "数据摘要（服务端生成）";

    private record Scalar(String label, String unit, boolean integer) {
    }

    /** 已知标量指标（有序：渲染顺序稳定，便于测试与人工核对）。 */
    private static final Map<String, Scalar> SCALARS = new LinkedHashMap<>();

    static {
        SCALARS.put("orderCount", new Scalar("订单量", "笔", true));
        SCALARS.put("guaranteeAmount", new Scalar("担保金额", "元", false));
        SCALARS.put("premiumAmount", new Scalar("保费", "元", false));
        SCALARS.put("enterpriseCount", new Scalar("企业数", "家", true));
        SCALARS.put("projectCount", new Scalar("项目数", "个", true));
        SCALARS.put("total", new Scalar("记录数", "条", true));
    }

    /** 明细表列（有序）；{@code nameLabel} 为空表示列名随维度变化。 */
    private record Column(String key, String label, String nameLabel, boolean money) {
    }

    private static final List<Column> ITEM_COLUMNS = List.of(
            new Column("rank", "排名", null, false),
            new Column("name", null, "名称", false),
            new Column("orderCount", "订单量", null, false),
            new Column("guaranteeAmount", "担保金额（元）", null, true),
            new Column("premiumAmount", "保费（元）", null, true),
            new Column("enterpriseCount", "企业数", null, false));

    /** 维度码 → 明细第一列的列名。 */
    private static final Map<String, String> DIMENSION_LABELS =
            Map.of("REGION", "区域", "ORG", "机构", "INSURANCE", "险种");

    private DataMetrics() {
    }

    /**
     * 渲染一次工具返回值的指标块。
     *
     * @param root 已解析的工具返回值 JSON 树
     * @return 指标块（多行 markdown）；没有已登记指标时返回空
     */
    public static Optional<String> blockOf(Object root) {
        if (!(root instanceof Map<?, ?> map)) {
            return Optional.empty();
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Scalar> entry : SCALARS.entrySet()) {
            Object value = map.get(entry.getKey());
            if (value instanceof Number number) {
                Scalar spec = entry.getValue();
                lines.add("- " + spec.label() + "：" + formatNumber(number, spec.integer()) + " " + spec.unit());
            }
        }
        String table = renderTable(map).orElse(null);
        boolean hasMetrics = !lines.isEmpty() || table != null;
        if (table != null) {
            if (!lines.isEmpty()) {
                lines.add("");
            }
            lines.add(table);
        }
        if (!hasMetrics) {
            return Optional.empty();
        }
        // 截断标注挂在**整块**上：结果被截断这件事与"有没有已登记指标"无关，
        // 只显示一半数据却不说，比不显示更容易让人误判。
        if (Boolean.TRUE.equals(metaField(map, "truncated"))) {
            lines.add("（该结果超出单次返回上限已被截断，以上仅为部分数据）");
        }
        Object dataSource = map.get("dataSource");
        if (!(dataSource instanceof String text) || text.isBlank()) {
            // 口径不在顶层时（多数工具放在 meta 里），回退到 meta.dataSource
            dataSource = metaField(map, "dataSource");
        }
        String heading = dataSource instanceof String text && !text.isBlank() ? text : null;
        StringBuilder block = new StringBuilder();
        if (heading != null) {
            block.append(heading).append('\n');
        }
        block.append(String.join("\n", lines));
        return Optional.of(block.toString());
    }

    /** 把多个工具返回值的指标块拼成最终要追加的页脚（无内容时返回空串）。 */
    public static String renderAll(List<String> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return "";
        }
        return "\n\n" + HEADER + "\n" + String.join("\n\n", blocks);
    }

    /**
     * 明细表：只认"元素是对象、含 {@code name} 与至少一个已知数值列"的顶层数组，
     * 取第一个匹配的数组（工具返回值里这样的数组最多一个）。
     */
    private static Optional<String> renderTable(Map<?, ?> map) {
        for (Object value : map.values()) {
            if (!(value instanceof List<?> list) || list.isEmpty()) {
                continue;
            }
            if (!(list.get(0) instanceof Map<?, ?> first)) {
                continue;
            }
            if (!hasUsableColumns(first)) {
                continue;
            }
            return Optional.of(renderRows(map, list));
        }
        return Optional.empty();
    }

    private static boolean hasUsableColumns(Map<?, ?> item) {
        if (!item.containsKey("name")) {
            return false;
        }
        return ITEM_COLUMNS.stream()
                .filter(column -> !"name".equals(column.key()))
                .anyMatch(column -> item.containsKey(column.key()));
    }

    private static String renderRows(Map<?, ?> map, List<?> items) {
        String nameLabel = DIMENSION_LABELS.getOrDefault(String.valueOf(map.get("dimension")), "名称");
        List<Column> columns = ITEM_COLUMNS.stream()
                .filter(column -> items.stream().anyMatch(item ->
                        item instanceof Map<?, ?> row && row.containsKey(column.key())))
                .toList();
        StringBuilder table = new StringBuilder();
        table.append(row(columns.stream().map(column -> labelOf(column, nameLabel)).toList())).append('\n');
        table.append(row(columns.stream().map(column -> "---").toList())).append('\n');
        int rows = Math.min(items.size(), MAX_ROWS);
        for (int i = 0; i < rows; i++) {
            Map<?, ?> row = (Map<?, ?>) items.get(i);
            List<String> cells = new ArrayList<>(columns.size());
            for (Column column : columns) {
                cells.add(cell(row.get(column.key()), column));
            }
            table.append(row(cells)).append('\n');
        }
        StringBuilder out = new StringBuilder(table.toString().stripTrailing());
        if (items.size() > rows) {
            out.append("\n（仅显示前 ").append(rows).append(" 条，共 ").append(items.size()).append(" 条）");
        }
        return out.toString();
    }

    /** 一行 markdown 表格：{@code | a | b |}（两侧各一个空格，不再额外补尾部空格）。 */
    private static String row(List<String> cells) {
        return "| " + String.join(" | ", cells) + " |";
    }

    private static String labelOf(Column column, String nameLabel) {
        return column.label() != null ? column.label() : nameLabel;
    }

    private static String cell(Object value, Column column) {
        if (value == null) {
            return "—";
        }
        if (value instanceof Number number) {
            return formatNumber(number, !column.money());
        }
        return String.valueOf(value);
    }

    private static String formatNumber(Number number, boolean integer) {
        if (integer) {
            return String.valueOf(number.longValue());
        }
        BigDecimal decimal = number instanceof BigDecimal big
                ? big
                : BigDecimal.valueOf(number.doubleValue());
        return decimal.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** 读 {@code meta.<field>}（工具返回值的通用元信息块）。 */
    private static Object metaField(Map<?, ?> map, String field) {
        return map.get("meta") instanceof Map<?, ?> meta ? meta.get(field) : null;
    }
}
