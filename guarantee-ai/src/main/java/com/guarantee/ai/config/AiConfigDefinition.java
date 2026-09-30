package com.guarantee.ai.config;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一个配置项的元数据（REQ-CFG-01）。
 *
 * <p><b>唯一真源</b>：配置项清单只在 {@link AiConfigCatalog} 里定义一次。数据库
 * {@code ai_config_item} 上的 {@code value_type}/{@code default_value}/… 列是**由本记录
 * 生成的投影**（写库时填充、应用从不读回），用于运维直接读表时能看懂一行配置；
 * 不构成第二份需要同步的常量定义（项目真实事故：同值常量两处定义必然漂移）。</p>
 *
 * @param key          配置键（唯一，点号分层，例如 {@code budget.max-rounds}）
 * @param type         值类型
 * @param defaultValue 默认值；{@code null} 表示"未设置"（沿用框架/starter 默认，运行期取不到值）
 * @param minValue     允许范围下界（含）；{@code null} 表示不限，仅对 INT/DECIMAL 生效
 * @param maxValue     允许范围上界（含）；{@code null} 表示不限，仅对 INT/DECIMAL 生效
 * @param enumOptions  ENUM 的可选值（其它类型为空列表）
 * @param category     分类（页面分组用）
 * @param dangerous    危险配置：页面二次确认，且纳入 {@code CONFIG_UPDATE} 审计的重点提示
 * @param maxLength    STRING 的最大长度；{@code <=0} 表示不限
 * @param description  影响面说明（页面/接口原样展示）
 * @param wired        运行期是否真的消费该键。{@code false} = 本期未接线（仅展示）：
 *                     页面据此标注并禁用编辑，服务端也拒绝写入。它替代"前端硬编码未接线清单"
 *                     的做法——两处清单必然漂移，而"是否被运行期消费"是代码事实，只应声明一次
 */
public record AiConfigDefinition(
        String key,
        AiConfigType type,
        String defaultValue,
        BigDecimal minValue,
        BigDecimal maxValue,
        List<String> enumOptions,
        AiConfigCategory category,
        boolean dangerous,
        int maxLength,
        String description,
        boolean wired) {

    public AiConfigDefinition {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("配置键不能为空");
        }
        if (type == null) {
            throw new IllegalArgumentException("配置类型不能为空：" + key);
        }
        if (category == null) {
            throw new IllegalArgumentException("配置分类不能为空：" + key);
        }
        enumOptions = enumOptions == null ? List.of() : List.copyOf(enumOptions);
        if (type == AiConfigType.ENUM && enumOptions.isEmpty()) {
            throw new IllegalArgumentException("ENUM 类型必须声明可选值：" + key);
        }
    }

    /** 是否有默认值（{@code false} 表示未设置，运行期必须显式配置才有值）。 */
    public boolean hasDefaultValue() {
        return defaultValue != null;
    }

    /** 该类型是否受 min/max 数值范围约束。 */
    public boolean rangeBounded() {
        return type == AiConfigType.INT || type == AiConfigType.DECIMAL;
    }
}
