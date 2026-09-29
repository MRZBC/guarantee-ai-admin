package com.guarantee.ai.config;

import com.guarantee.ai.config.mapper.AiConfigItemMapper;
import com.guarantee.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI 配置服务（REQ-CFG-01 / REQ-CFG-06 / REQ-CFG-07）。
 *
 * <p><b>读取路径不查库</b>：{@link #snapshot()} 返回进程内缓存的不可变快照，配置读取是纯内存操作。
 * 只有在首次加载、{@link #refreshIfStale()} 发现 DB 版本号变化、或显式 {@link #refresh()} 时才访问数据库。</p>
 *
 * <p><b>降级不静默</b>：DB 读取失败 → 沿用上一份快照（从未成功加载过则用目录默认值）+ ERROR 日志；
 * DB 中的非法值 → 回落默认值 + ERROR 日志（记入快照的 {@code invalidKeys}）。任何情况下
 * 都不会因为"配置读不到"让整个助手不可用（AC-CFG-07）。</p>
 *
 * <p><b>写路径只做"值 + 版本号"</b>：权限校验、二次确认与审计由 T4-02 的接口层承载；
 * 本服务是执行原语，不在自身做权限判断（否则会出现两处权限口径）。</p>
 */
@Service
public class AiConfigService {

    private static final Logger log = LoggerFactory.getLogger(AiConfigService.class);

    private final AiConfigItemMapper mapper;
    private final AiConfigCatalog catalog;
    private final AtomicReference<AiConfigSnapshot> cache = new AtomicReference<>();
    private final Object refreshLock = new Object();

    public AiConfigService(AiConfigItemMapper mapper, AiConfigCatalog catalog) {
        this.mapper = mapper;
        this.catalog = catalog;
    }

    /**
     * 当前快照（首次调用会触发一次 DB 加载）。
     *
     * <p>除首次加载外不查库；DB 不可用也不会抛异常，而是返回上一份快照/默认值。</p>
     */
    public AiConfigSnapshot snapshot() {
        // 快路径：已加载则纯内存返回
        AiConfigSnapshot current = cache.get();
        if (current != null) {
            return current;
        }
        // 冷启动并发：只在锁内再判一次，保证 N 个并发首读只触发 1 次 DB 加载
        synchronized (refreshLock) {
            current = cache.get();
            return current != null ? current : loadAndSwap();
        }
    }

    /** 强制从 DB 重载快照（DB 不可用时保留上一份并告警）。 */
    public AiConfigSnapshot refresh() {
        synchronized (refreshLock) {
            return loadAndSwap();
        }
    }

    /** 调用方必须持有 {@link #refreshLock}。 */
    private AiConfigSnapshot loadAndSwap() {
        AiConfigSnapshot previous = cache.get();
        try {
            AiConfigSnapshot loaded = build(mapper.selectAll());
            cache.set(loaded);
            warnOnInvalid(loaded);
            return loaded;
        } catch (RuntimeException ex) {
            log.error("读取 AI 配置失败：沿用上一份快照；若从未成功加载则使用内置默认值。助手仍可用。"
                    + "若为升级后的首次部署，请确认已执行 db/migration/V8__ai_config.sql"
                    + "（或由 schema.sql 自举建表）。", ex);
            if (previous != null) {
                return previous;
            }
            AiConfigSnapshot fallback = build(List.of());
            cache.set(fallback);
            return fallback;
        }
    }

    /**
     * 请求开始前的版本比对（REQ-CFG-06）：DB 版本号与快照不一致才重载。
     *
     * <p>一次轻量 {@code SELECT MAX(version)}（走主键/唯一键，&lt;1ms）换取"改配置不重启"；
     * DB 不可用时沿用当前快照，不让助手整体失败。这是"一轮对话使用同一份快照"的入口：
     * 调用一次拿到快照后，整轮都使用该实例。</p>
     */
    public AiConfigSnapshot refreshIfStale() {
        AiConfigSnapshot current = snapshot();
        try {
            Long dbVersion = mapper.maxVersion();
            long version = dbVersion == null ? 0L : dbVersion;
            if (version == current.version()) {
                return current;
            }
            log.info("检测到 AI 配置版本变化：{} → {}，重载快照", current.version(), version);
            return refresh();
        } catch (RuntimeException ex) {
            log.warn("比对 AI 配置版本失败，沿用当前快照（version={}）：{}", current.version(), ex.getMessage());
            return current;
        }
    }

    /** 当前快照版本号（不查库）。 */
    public long version() {
        return snapshot().version();
    }

    /** 读取单个配置项的生效值（不查库；未知键给可读错误）。 */
    public String get(String key) {
        return snapshot().get(key);
    }

    /**
     * 写入一个配置值（校验 → 版本号 +1 → 落库 → 刷新快照）。
     *
     * <p>非法值在触库前就被拒绝，错误信息带键名与原因（AC-CFG-07）。</p>
     *
     * @param operator 操作人标识（sys_user.id 字符串），落库到 {@code updated_by}
     */
    public AiConfigSnapshot update(String key, String rawValue, String operator) {
        AiConfigDefinition def = catalog.require(key);
        if (rawValue == null) {
            throw BizException.badRequest("配置项 " + key + " 的值不能为空；如需恢复默认值请使用 reset");
        }
        String normalized = AiConfigCatalog.validate(def, rawValue);
        write(def, normalized, operator);
        return refresh();
    }

    /**
     * 恢复默认值（清空显式值）。
     *
     * <p>行仍保留、版本号继续递增：版本号只增不减，否则"版本号变化 → 重载快照"的判据会失效。</p>
     */
    public AiConfigSnapshot reset(String key, String operator) {
        AiConfigDefinition def = catalog.require(key);
        write(def, null, operator);
        return refresh();
    }

    /** 目录（页面展示元数据用）。 */
    public AiConfigCatalog catalog() {
        return catalog;
    }

    /**
     * 启动预热：把"库中非法值"的 ERROR 告警提前到启动期（AC-CFG-07）。
     *
     * <p><b>由 T4-01 在启动期显式调用</b>（本方法不自己挂 {@code @EventListener}）：
     * T4-00 只交付底座时 {@code ai_config_item} 尚未进 schema.sql，若在这里自动监听
     * {@code ApplicationReadyEvent}，所有既有集成测试启动时都会打一条"读配置失败"的 ERROR——
     * 那是"表还没建"的正常过渡态，不该伪装成故障。等 T4-01 把 DDL 接进 schema.sql 后由它接线。</p>
     *
     * <p>不做失败即启动失败的处理：DB 不可用时 {@link #refresh()} 已降级为默认值 + 告警。</p>
     */
    public void warmUp() {
        AiConfigSnapshot snapshot = refresh();
        log.info("AI 配置快照已加载：version={}，配置项 {} 项，显式值 {} 项",
                snapshot.version(), snapshot.definitions().size(), snapshot.overriddenKeys().size());
    }

    private void warnOnInvalid(AiConfigSnapshot snapshot) {
        if (snapshot.invalidKeys().isEmpty()) {
            return;
        }
        for (String key : snapshot.invalidKeys()) {
            log.error("配置项 {} 的库中值非法，已回落默认值；请在「系统管理 → AI 配置」页面修正。"
                    + "助手继续使用默认值运行，不会因此不可用。", key);
        }
    }

    private void write(AiConfigDefinition def, String value, String operator) {
        Long maxVersion = mapper.maxVersion();
        long nextVersion = (maxVersion == null ? 0L : maxVersion) + 1;

        AiConfigItem item = new AiConfigItem();
        item.setConfigKey(def.key());
        item.setConfigValue(value);
        item.setVersion(nextVersion);
        item.setUpdatedBy(operator);
        applyDefinition(item, def);

        int updated = mapper.updateValue(item);
        if (updated == 0) {
            mapper.insert(item);
        }
    }

    private AiConfigSnapshot build(List<AiConfigItem> rows) {
        long maxVersion = 0L;
        Map<String, String> effective = new LinkedHashMap<>();
        Set<String> overridden = new LinkedHashSet<>();
        Set<String> invalid = new LinkedHashSet<>();

        // 先铺默认值：缺省（空表/未配置）时行为必须与改造前逐项一致（AC-CFG-08）
        for (AiConfigDefinition def : catalog.all()) {
            if (def.hasDefaultValue()) {
                effective.put(def.key(), def.defaultValue());
            }
        }

        if (rows != null) {
            for (AiConfigItem row : rows) {
                if (row == null) {
                    continue;
                }
                if (row.getVersion() != null) {
                    maxVersion = Math.max(maxVersion, row.getVersion());
                }
                String key = row.getConfigKey();
                Optional<AiConfigDefinition> found = catalog.find(key);
                if (found.isEmpty()) {
                    log.warn("忽略库中未知配置项（不在 AiConfigCatalog 中）：{}", key);
                    continue;
                }
                AiConfigDefinition def = found.get();
                String raw = row.getConfigValue();
                if (raw == null) {
                    continue; // 无显式值：使用默认值
                }
                try {
                    effective.put(key, AiConfigCatalog.validate(def, raw));
                    overridden.add(key);
                } catch (BizException ex) {
                    invalid.add(key);
                    if (!def.hasDefaultValue()) {
                        effective.remove(key);
                    }
                }
            }
        }
        return new AiConfigSnapshot(maxVersion, LocalDateTime.now(), catalog.all(),
                effective, overridden, invalid);
    }

    /** 把目录元数据投影到行上（仅供运维直接读表；应用读回时不依赖）。 */
    private static void applyDefinition(AiConfigItem item, AiConfigDefinition def) {
        item.setValueType(def.type().name());
        item.setDefaultValue(def.defaultValue());
        item.setMinValue(def.minValue());
        item.setMaxValue(def.maxValue());
        item.setEnumOptions(def.enumOptions().isEmpty() ? null : String.join(",", def.enumOptions()));
        item.setCategory(def.category().name());
        item.setDangerous(def.dangerous() ? 1 : 0);
        item.setDescription(def.description());
    }
}
