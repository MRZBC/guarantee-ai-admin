package com.guarantee.ai.config;

import com.guarantee.ai.config.mapper.AiConfigItemMapper;
import com.guarantee.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 配置快照、刷新与写入（TEST-CFG-01 后半部分 + TEST-CFG-02 前半部分）。
 *
 * <p>不需要数据库：{@link AiConfigItemMapper} 用 Mockito 假实现，重点验证四件事：</p>
 * <ol>
 *   <li><b>缺省一致</b>：空表时读到的值与改造前常量逐项相同（AC-CFG-08）；</li>
 *   <li><b>读配置不查库</b>：快照缓存后反复读取不再访问 Mapper；</li>
 *   <li><b>版本号驱动刷新</b>：DB 版本号变化才重载，版本比对失败/DB 不可用时沿用旧快照；</li>
 *   <li><b>非法值不落库、库中非法值不炸</b>：写前拒绝并给可读错误，读时回落默认并记录告警键。</li>
 * </ol>
 */
class AiConfigServiceTest {

    private AiConfigItemMapper mapper;
    private AiConfigService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AiConfigItemMapper.class);
        service = new AiConfigService(mapper, new AiConfigCatalog());
    }

    private static AiConfigItem row(String key, String value, long version) {
        AiConfigItem item = new AiConfigItem();
        item.setConfigKey(key);
        item.setConfigValue(value);
        item.setVersion(version);
        return item;
    }

    // ==================================================================
    // 缺省一致 + 读配置不查库（AC-CFG-08 / REQ-CFG-01）
    // ==================================================================

    @Test
    @DisplayName("空表：快照值 = 改造前常量（temperature 0.2 / 轮次 4 / 单轮 12 / 超时 60s / 历史 20）")
    void emptyTableFallsBackToCatalogDefaults() {
        when(mapper.selectAll()).thenReturn(List.of());

        AiConfigSnapshot snapshot = service.snapshot();

        assertThat(snapshot.version()).as("空表版本号为 0").isZero();
        assertThat(snapshot.getDecimal(AiConfigCatalog.MODEL_TEMPERATURE)).isEqualByComparingTo("0.2");
        assertThat(snapshot.getInt(AiConfigCatalog.BUDGET_HISTORY_LIMIT)).isEqualTo(20);
        assertThat(snapshot.getInt(AiConfigCatalog.BUDGET_MAX_ROUNDS)).isEqualTo(4);
        assertThat(snapshot.getInt(AiConfigCatalog.BUDGET_MAX_CALLS_PER_ROUND)).isEqualTo(12);
        assertThat(snapshot.getLong(AiConfigCatalog.BUDGET_SOFT_TIMEOUT_MS)).isEqualTo(60_000L);
        assertThat(snapshot.getInt(AiConfigCatalog.BUDGET_TOOL_RESULT_BYTES)).isEqualTo(16_384);
        assertThat(snapshot.getInt(AiConfigCatalog.BUDGET_TOOL_TIMEOUT_MS)).isEqualTo(10_000);
        assertThat(snapshot.get(AiConfigCatalog.MODEL_NAME)).isEqualTo("deepseek-chat");
        assertThat(snapshot.get(AiConfigCatalog.MODEL_API_KEY_REF)).isEqualTo("DEEPSEEK_API_KEY");
        assertThat(snapshot.getBoolean(AiConfigCatalog.TOOLS_PROPOSAL_ENABLED)).isTrue();
        assertThat(snapshot.isOverridden(AiConfigCatalog.MODEL_TEMPERATURE)).isFalse();
        assertThat(snapshot.invalidKeys()).isEmpty();
        // 未设置默认值的三项：取不到值，但也不抛异常
        assertThat(snapshot.find(AiConfigCatalog.MODEL_TIMEOUT)).isEmpty();
        assertThat(snapshot.find(AiConfigCatalog.MODEL_MAX_RETRIES)).isEmpty();
        assertThat(snapshot.find(AiConfigCatalog.PROMPT_ACTIVE_VERSION)).isEmpty();
    }

    @Test
    @DisplayName("读配置不查库：快照缓存后反复读取只访问一次 Mapper（REQ §7 性能）")
    void readsShouldNotHitDatabase() {
        when(mapper.selectAll()).thenReturn(List.of());

        service.snapshot();
        for (int i = 0; i < 50; i++) {
            service.snapshot();
            service.get(AiConfigCatalog.MODEL_NAME);
            service.version();
        }

        verify(mapper, times(1)).selectAll();
        verify(mapper, never()).maxVersion();
    }

    @Test
    @DisplayName("库中有显式值时覆盖默认值，版本号取全部行的最大值")
    void overridesWinAndVersionIsMax() {
        when(mapper.selectAll()).thenReturn(List.of(
                row(AiConfigCatalog.MODEL_TEMPERATURE, "0.7", 3),
                row(AiConfigCatalog.BUDGET_MAX_ROUNDS, "6", 5)));

        AiConfigSnapshot snapshot = service.snapshot();

        assertThat(snapshot.getDecimal(AiConfigCatalog.MODEL_TEMPERATURE)).isEqualByComparingTo("0.7");
        assertThat(snapshot.getInt(AiConfigCatalog.BUDGET_MAX_ROUNDS)).isEqualTo(6);
        assertThat(snapshot.getInt(AiConfigCatalog.BUDGET_HISTORY_LIMIT))
                .as("未覆盖的项继续用默认值").isEqualTo(20);
        assertThat(snapshot.version()).isEqualTo(5);
        assertThat(snapshot.isOverridden(AiConfigCatalog.MODEL_TEMPERATURE)).isTrue();
        assertThat(snapshot.isOverridden(AiConfigCatalog.BUDGET_HISTORY_LIMIT)).isFalse();
    }

    // ==================================================================
    // 非法值降级（AC-CFG-07）
    // ==================================================================

    @Test
    @DisplayName("库中非法值回落默认 + 记入 invalidKeys，不抛异常（助手仍可用）")
    void invalidDatabaseValueFallsBackToDefault() {
        when(mapper.selectAll()).thenReturn(List.of(
                row(AiConfigCatalog.MODEL_TEMPERATURE, "9", 2),
                row(AiConfigCatalog.TOOLS_PROPOSAL_ENABLED, "maybe", 2)));

        AiConfigSnapshot snapshot = service.snapshot();

        assertThat(snapshot.getDecimal(AiConfigCatalog.MODEL_TEMPERATURE)).isEqualByComparingTo("0.2");
        assertThat(snapshot.getBoolean(AiConfigCatalog.TOOLS_PROPOSAL_ENABLED)).isTrue();
        assertThat(snapshot.invalidKeys()).containsExactlyInAnyOrder(
                AiConfigCatalog.MODEL_TEMPERATURE, AiConfigCatalog.TOOLS_PROPOSAL_ENABLED);
        assertThat(snapshot.isOverridden(AiConfigCatalog.MODEL_TEMPERATURE)).isFalse();
        assertThat(snapshot.version()).as("非法行仍贡献版本号，重载判据不会被吃掉").isEqualTo(2);
    }

    @Test
    @DisplayName("库中未知键被忽略而不是让快照构建失败")
    void unknownDatabaseKeyIsIgnored() {
        when(mapper.selectAll()).thenReturn(List.of(
                row("ghost.key", "1", 4),
                row(AiConfigCatalog.MODEL_NAME, "deepseek-chat", 4)));

        AiConfigSnapshot snapshot = service.snapshot();

        assertThat(snapshot.effectiveValues()).doesNotContainKey("ghost.key");
        assertThat(snapshot.definitions()).doesNotContainKey("ghost.key");
        assertThat(snapshot.version()).isEqualTo(4);
    }

    // ==================================================================
    // 版本比对与降级（REQ-CFG-06 / REQ-CFG-07）
    // ==================================================================

    @Test
    @DisplayName("版本号未变化不重载；变化则重载并生效（不重启）")
    void refreshIfStaleReloadsOnlyOnVersionChange() {
        when(mapper.selectAll()).thenReturn(List.of());
        AiConfigSnapshot first = service.snapshot();

        when(mapper.maxVersion()).thenReturn(0L);
        assertThat(service.refreshIfStale()).isSameAs(first);
        verify(mapper, times(1)).selectAll();

        when(mapper.maxVersion()).thenReturn(7L);
        when(mapper.selectAll()).thenReturn(List.of(row(AiConfigCatalog.MODEL_TEMPERATURE, "0.9", 7)));

        AiConfigSnapshot second = service.refreshIfStale();

        assertThat(second).isNotSameAs(first);
        assertThat(second.version()).isEqualTo(7);
        assertThat(second.getDecimal(AiConfigCatalog.MODEL_TEMPERATURE)).isEqualByComparingTo("0.9");
        verify(mapper, times(2)).selectAll();
    }

    @Test
    @DisplayName("版本比对失败时沿用当前快照，不影响本轮对话")
    void versionCompareFailureKeepsCurrentSnapshot() {
        when(mapper.selectAll()).thenReturn(List.of(row(AiConfigCatalog.MODEL_NAME, "deepseek-chat", 2)));
        AiConfigSnapshot first = service.snapshot();

        when(mapper.maxVersion()).thenThrow(new IllegalStateException("db down"));

        assertThat(service.refreshIfStale()).isSameAs(first);
    }

    @Test
    @DisplayName("首次加载 DB 不可用：回落到内置默认值而不是让助手不可用")
    void firstLoadFailureFallsBackToDefaults() {
        when(mapper.selectAll()).thenThrow(new IllegalStateException("db down"));

        AiConfigSnapshot snapshot = service.snapshot();

        assertThat(snapshot.version()).isZero();
        assertThat(snapshot.getInt(AiConfigCatalog.BUDGET_MAX_ROUNDS)).isEqualTo(4);
        assertThat(snapshot.getDecimal(AiConfigCatalog.MODEL_TEMPERATURE)).isEqualByComparingTo("0.2");
    }

    @Test
    @DisplayName("重载失败保留上一份快照（不是退回默认值）")
    void refreshFailureKeepsPreviousSnapshot() {
        when(mapper.selectAll()).thenReturn(List.of(row(AiConfigCatalog.BUDGET_MAX_ROUNDS, "5", 3)));
        AiConfigSnapshot first = service.refresh();

        when(mapper.selectAll()).thenThrow(new IllegalStateException("db down"));

        assertThat(service.refresh()).isSameAs(first);
        assertThat(service.snapshot().getInt(AiConfigCatalog.BUDGET_MAX_ROUNDS)).isEqualTo(5);
    }

    // ==================================================================
    // 写入校验与版本递增（TEST-CFG-01）
    // ==================================================================

    @Test
    @DisplayName("非法值在触库前被拒绝，且给出可读错误（AC-CFG-07）")
    void updateRejectsInvalidValueBeforeTouchingDatabase() {
        assertThatThrownBy(() -> service.update(AiConfigCatalog.MODEL_TEMPERATURE, "9", "7"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining(AiConfigCatalog.MODEL_TEMPERATURE)
                .hasMessageContaining("超出允许范围");

        assertThatThrownBy(() -> service.update("not.exists.key", "1", "7"))
                .isInstanceOf(BizException.class).hasMessageContaining("未知的配置项");

        assertThatThrownBy(() -> service.update(AiConfigCatalog.MODEL_NAME, "  ", "7"))
                .isInstanceOf(BizException.class).hasMessageContaining("不能为空");

        assertThatThrownBy(() -> service.update(AiConfigCatalog.MODEL_NAME, null, "7"))
                .isInstanceOf(BizException.class).hasMessageContaining("reset");

        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("写入：值归一化、版本 = max+1、元数据投影与目录一致、快照立即生效")
    void updateNormalizesValueAndProjectsMetadata() {
        when(mapper.maxVersion()).thenReturn(3L);
        when(mapper.updateValue(any())).thenReturn(1);
        when(mapper.selectAll()).thenReturn(List.of(row(AiConfigCatalog.MODEL_TEMPERATURE, "0.4", 4)));

        AiConfigSnapshot snapshot = service.update(AiConfigCatalog.MODEL_TEMPERATURE, "0.40", "7");

        ArgumentCaptor<AiConfigItem> captor = ArgumentCaptor.forClass(AiConfigItem.class);
        verify(mapper).updateValue(captor.capture());
        AiConfigItem written = captor.getValue();

        assertThat(written.getConfigKey()).isEqualTo(AiConfigCatalog.MODEL_TEMPERATURE);
        assertThat(written.getConfigValue()).as("尾随零被归一化").isEqualTo("0.4");
        assertThat(written.getVersion()).isEqualTo(4);
        assertThat(written.getUpdatedBy()).isEqualTo("7");
        assertThat(written.getValueType()).isEqualTo("DECIMAL");
        assertThat(written.getCategory()).isEqualTo("MODEL");
        assertThat(written.getDangerous()).isEqualTo(1);
        assertThat(written.getDescription()).isNotBlank();
        assertThat(written.getMinValue()).isNotNull();
        assertThat(written.getMaxValue()).isNotNull();

        verify(mapper, never()).insert(any());
        assertThat(snapshot.version()).isEqualTo(4);
        assertThat(snapshot.getDecimal(AiConfigCatalog.MODEL_TEMPERATURE)).isEqualByComparingTo("0.4");
    }

    @Test
    @DisplayName("首次写入（无既有行）：updateValue 影响 0 行后走 insert，版本号从 1 起")
    void updateInsertsWhenRowMissing() {
        when(mapper.maxVersion()).thenReturn(null);
        when(mapper.updateValue(any())).thenReturn(0);
        when(mapper.selectAll()).thenReturn(List.of(row(AiConfigCatalog.BUDGET_MAX_ROUNDS, "6", 1)));

        service.update(AiConfigCatalog.BUDGET_MAX_ROUNDS, "6", "7");

        ArgumentCaptor<AiConfigItem> captor = ArgumentCaptor.forClass(AiConfigItem.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getVersion()).isEqualTo(1);
        assertThat(captor.getValue().getConfigValue()).isEqualTo("6");
    }

    @Test
    @DisplayName("恢复默认：显式值清空但版本号继续递增（重载判据不失效）")
    void resetClearsValueButBumpsVersion() {
        when(mapper.maxVersion()).thenReturn(8L);
        when(mapper.updateValue(any())).thenReturn(1);
        when(mapper.selectAll()).thenReturn(List.of(row(AiConfigCatalog.MODEL_TEMPERATURE, null, 9)));

        AiConfigSnapshot snapshot = service.reset(AiConfigCatalog.MODEL_TEMPERATURE, "7");

        ArgumentCaptor<AiConfigItem> captor = ArgumentCaptor.forClass(AiConfigItem.class);
        verify(mapper).updateValue(captor.capture());
        assertThat(captor.getValue().getConfigValue()).isNull();
        assertThat(captor.getValue().getVersion()).isEqualTo(9);

        assertThat(snapshot.version()).isEqualTo(9);
        assertThat(snapshot.getDecimal(AiConfigCatalog.MODEL_TEMPERATURE)).isEqualByComparingTo("0.2");
        assertThat(snapshot.isOverridden(AiConfigCatalog.MODEL_TEMPERATURE)).isFalse();
    }

    // ==================================================================
    // 并发安全（TEST-CFG-02）
    // ==================================================================

    @Test
    @DisplayName("并发读同一快照：结果一致且无异常")
    void concurrentReadsAreSafe() throws Exception {
        when(mapper.selectAll()).thenReturn(List.of(row(AiConfigCatalog.BUDGET_MAX_ROUNDS, "6", 2)));

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                futures.add(pool.submit(() -> service.snapshot().getInt(AiConfigCatalog.BUDGET_MAX_ROUNDS)));
            }
            for (Future<Integer> future : futures) {
                assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo(6);
            }
        } finally {
            pool.shutdownNow();
        }
        verify(mapper, times(1)).selectAll();
    }

    @Test
    @DisplayName("启动预热加载快照且失败不阻断启动（AC-CFG-07）")
    void warmUpLoadsSnapshotAndNeverThrows() {
        when(mapper.selectAll()).thenReturn(List.of(row(AiConfigCatalog.MODEL_NAME, "deepseek-chat", 1)));

        service.warmUp();

        assertThat(service.version()).isEqualTo(1);

        // DB 挂了也只告警，不抛异常
        when(mapper.selectAll()).thenThrow(new IllegalStateException("db down"));
        service.warmUp();
        assertThat(service.version()).as("预热失败保留上一份快照").isEqualTo(1);
    }
}
