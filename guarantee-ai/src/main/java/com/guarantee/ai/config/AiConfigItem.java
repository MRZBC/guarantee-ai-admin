package com.guarantee.ai.config;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 配置项当前值（{@code ai_config_item}，REQ-CFG-01）。
 *
 * <p><b>应用只把这张表当"值的存储"</b>：键的元数据（类型/默认/范围/是否危险/说明）来自
 * {@link AiConfigCatalog}，本实体上的 {@code valueType}/{@code defaultValue}/… 字段是写库时
 * 由目录生成的**投影**，仅用于运维直接读表，应用读回时一律以目录为准。这样做是为了让
 * "配置项清单"只有一处真源，避免同值常量两处定义后必然发生的漂移。</p>
 *
 * <p>{@code configValue == null} 表示"无显式值"：生效值回落到目录里的默认值。</p>
 */
@Data
public class AiConfigItem {

    private Long id;
    /** 配置键（唯一）。 */
    private String configKey;
    /** 当前值；null 表示使用默认值。 */
    private String configValue;
    /** 值类型投影（STRING/INT/DECIMAL/BOOLEAN/ENUM）。 */
    private String valueType;
    /** 默认值投影（可为 null，表示未设置、沿用框架默认）。 */
    private String defaultValue;
    /** 允许范围下界投影。 */
    private java.math.BigDecimal minValue;
    /** 允许范围上界投影。 */
    private java.math.BigDecimal maxValue;
    /** ENUM 可选值投影（逗号分隔）。 */
    private String enumOptions;
    /** 分类投影（MODEL/SWITCH/BUDGET/PROMPT）。 */
    private String category;
    /** 是否危险配置投影（1 是 0 否）。 */
    private Integer dangerous;
    /** 影响面说明投影。 */
    private String description;
    /** 配置版本号；快照版本 = 全部行 version 的最大值。 */
    private Long version;
    /** 最后修改人（sys_user.id 字符串）。 */
    private String updatedBy;
    private LocalDateTime updatedAt;

    private Integer isDeleted;
    private LocalDateTime deletedAt;
    private String deletedBy;
}
