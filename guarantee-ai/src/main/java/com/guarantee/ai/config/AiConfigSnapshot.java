package com.guarantee.ai.config;

import com.guarantee.common.exception.BizException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

/**
 * 不可变的配置快照（REQ-CFG-01 / REQ-CFG-06）。
 *
 * <p><b>为什么是快照</b>：一轮对话里配置被改到一半，会得到"前半轮用旧值、后半轮用新值"的
 * 不可解释行为。因此请求开始前取一份快照，整轮都用它；快照本身不可变，天然线程安全。</p>
 *
 * <p><b>版本号</b>：等于 {@code ai_config_item.version} 的最大值（{@code 0} 表示空表）。
 * 请求开始前比对数据库版本号即可判断"我这份快照是否过期"（REQ-CFG-06）。</p>
 *
 * <p><b>非法值不在这层爆</b>：DB 里已有的非法值在构建快照时已回落默认并记入
 * {@link #invalidKeys()}，启动期据此打 ERROR 告警（AC-CFG-07 的"助手仍可用"）。</p>
 */
public final class AiConfigSnapshot {

    private final long version;
    private final LocalDateTime loadedAt;
    private final Map<String, AiConfigDefinition> definitions;
    private final Map<String, String> effectiveValues;
    private final Set<String> overriddenKeys;
    private final Set<String> invalidKeys;

    AiConfigSnapshot(long version,
                     LocalDateTime loadedAt,
                     List<AiConfigDefinition> definitions,
                     Map<String, String> effectiveValues,
                     Set<String> overriddenKeys,
                     Set<String> invalidKeys) {
        this.version = version;
        this.loadedAt = loadedAt;
        Map<String, AiConfigDefinition> defs = new LinkedHashMap<>();
        definitions.forEach(def -> defs.put(def.key(), def));
        this.definitions = Collections.unmodifiableMap(defs);
        this.effectiveValues = Collections.unmodifiableMap(new LinkedHashMap<>(effectiveValues));
        this.overriddenKeys = Set.copyOf(overriddenKeys);
        this.invalidKeys = Set.copyOf(invalidKeys);
    }

    /** 快照版本号（= 配置项 version 最大值；空表为 0）。 */
    public long version() {
        return version;
    }

    /** 快照加载时间。 */
    public LocalDateTime loadedAt() {
        return loadedAt;
    }

    /** 全部配置项定义（声明顺序）。 */
    public Map<String, AiConfigDefinition> definitions() {
        return definitions;
    }

    /** 全部生效值（含默认值；未设置且无默认值的项不在其中）。 */
    public Map<String, String> effectiveValues() {
        return effectiveValues;
    }

    /** 有显式值的键集合（DB 里写了非 null 值）。 */
    public Set<String> overriddenKeys() {
        return overriddenKeys;
    }

    /** DB 中非法、已回落默认的键集合（非空即需要告警）。 */
    public Set<String> invalidKeys() {
        return invalidKeys;
    }

    /** 该键是否被显式配置过（相对"使用默认值"）。 */
    public boolean isOverridden(String key) {
        requireDefinition(key);
        return overriddenKeys.contains(key);
    }

    /** 目录中的默认值；未设置默认值时返回 null。 */
    public String defaultValue(String key) {
        return requireDefinition(key).defaultValue();
    }

    /** 生效值；未设置且无默认值时返回 null。未知键抛可读错误。 */
    public String get(String key) {
        requireDefinition(key);
        return effectiveValues.get(key);
    }

    /** 生效值；缺失时抛异常（调用方必须确保该键有默认值）。 */
    public String require(String key) {
        String value = get(key);
        if (value == null) {
            throw new IllegalStateException("配置项 " + key + " 没有生效值（无默认值且未显式配置）");
        }
        return value;
    }

    public Optional<String> find(String key) {
        return Optional.ofNullable(get(key));
    }

    public int getInt(String key) {
        return parseInt(key, require(key));
    }

    public long getLong(String key) {
        return getInt(key);
    }

    public double getDouble(String key) {
        return getDecimal(key).doubleValue();
    }

    public BigDecimal getDecimal(String key) {
        String raw = require(key);
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("配置项 " + key + " 的值不是合法数值：" + raw, ex);
        }
    }

    public boolean getBoolean(String key) {
        String raw = require(key);
        if ("true".equalsIgnoreCase(raw)) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return false;
        }
        throw new IllegalStateException("配置项 " + key + " 的值不是合法布尔值：" + raw);
    }

    public OptionalInt findInt(String key) {
        String raw = get(key);
        return raw == null ? OptionalInt.empty() : OptionalInt.of(parseInt(key, raw));
    }

    public OptionalLong findLong(String key) {
        OptionalInt value = findInt(key);
        return value.isEmpty() ? OptionalLong.empty() : OptionalLong.of(value.getAsInt());
    }

    public Optional<BigDecimal> findDecimal(String key) {
        String raw = get(key);
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(raw));
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("配置项 " + key + " 的值不是合法数值：" + raw, ex);
        }
    }

    public Optional<Boolean> findBoolean(String key) {
        String raw = get(key);
        if (raw == null) {
            return Optional.empty();
        }
        return Optional.of(getBoolean(key));
    }

    private AiConfigDefinition requireDefinition(String key) {
        AiConfigDefinition def = key == null ? null : definitions.get(key);
        if (def == null) {
            throw BizException.badRequest("未知的配置项：" + key);
        }
        return def;
    }

    private static int parseInt(String key, String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("配置项 " + key + " 的值不是合法整数：" + raw, ex);
        }
    }
}
