package com.guarantee.web.ai.config;

import com.guarantee.ai.config.AiConfigCatalog;
import com.guarantee.ai.config.AiConfigItem;
import com.guarantee.ai.config.AiConfigService;
import com.guarantee.ai.config.mapper.AiConfigItemMapper;
import com.guarantee.ai.controller.AiConfigController;
import com.guarantee.ai.service.OperationAuditService;
import com.guarantee.common.api.Result;
import com.guarantee.common.exception.BizException;
import com.guarantee.common.security.CurrentUser;
import com.guarantee.common.security.Roles;
import com.guarantee.common.security.SensitiveFieldMasker;
import com.guarantee.system.service.WebAuditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 配置变更的审计口径（TEST-CFG-05 的单元部分 / REQ-CFG-05）。
 *
 * <p>不需要数据库：{@link AiConfigItemMapper} 用 Mockito 假实现，{@link WebAuditor} 用 mock
 * 捕获审计入参。这里守住三条最容易做错的规则：</p>
 * <ol>
 *   <li><b>密钥类只记"是否变化"</b>：{@code model.api-key-ref} 的 before/after 只能是
 *       {@code <changed>}，引用名本身不得进审计（它是密钥来源线索）；</li>
 *   <li><b>值没变就不写、不审计</b>：不能产生"改了但没变"的审计垃圾与版本号空转；</li>
 *   <li><b>目标口径</b>：{@code target_type=AI_CONFIG} + {@code target_id=null}
 *       + {@code target_name=配置键}（target_id 是 BIGINT，放不下配置键）。</li>
 * </ol>
 */
class AiConfigChangeAuditTest {

    private static final String ADMIN_KEY = AiConfigCatalog.MODEL_API_KEY_REF;
    private static final String TEMPERATURE_KEY = AiConfigCatalog.MODEL_TEMPERATURE;

    private AiConfigItemMapper mapper;
    private WebAuditor webAuditor;
    private AiConfigController controller;

    @BeforeEach
    void setUp() {
        mapper = mock(AiConfigItemMapper.class);
        webAuditor = mock(WebAuditor.class);
        Environment environment = mock(Environment.class);
        AiConfigCatalog catalog = new AiConfigCatalog();
        AiConfigService configService = new AiConfigService(mapper, catalog);
        // 提示词版本服务不参与本测试的配置变更路径，用 mock 占位（T4-03 扩了控制器构造器）
        com.guarantee.ai.config.PromptVersionService promptVersionService =
                mock(com.guarantee.ai.config.PromptVersionService.class);
        controller = new AiConfigController(configService, catalog, webAuditor, environment,
                promptVersionService);
        CurrentUser.set(new CurrentUser.Principal(7L, "admin", "超级管理员",
                List.of(Roles.ADMIN), List.of()));
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    private static AiConfigItem row(String key, String value, long version) {
        AiConfigItem item = new AiConfigItem();
        item.setConfigKey(key);
        item.setConfigValue(value);
        item.setVersion(version);
        return item;
    }

    // ==================================================================
    // 密钥类：只记"是否变化"
    // ==================================================================

    @Test
    @DisplayName("密钥类配置变更：审计只写 <changed>，新旧引用名都不得出现在审计里")
    void secretClassAuditOnlyRecordsChanged() {
        when(mapper.selectAll())
                .thenReturn(List.of(row(ADMIN_KEY, "DEEPSEEK_API_KEY", 1)))
                .thenReturn(List.of(row(ADMIN_KEY, "INTERNAL_KEY_REF", 2)));
        when(mapper.maxVersion()).thenReturn(1L);
        when(mapper.updateValue(any())).thenReturn(1);

        Result<AiConfigController.AiConfigChangeResult> result = controller.change(
                new AiConfigController.AiConfigChangeRequest(ADMIN_KEY, "INTERNAL_KEY_REF"));

        assertThat(result.data().changed()).isTrue();
        assertThat(result.data().value()).isEqualTo("INTERNAL_KEY_REF");

        ArgumentCaptor<Map<String, Object>> beforeCaptor = mapCaptor();
        ArgumentCaptor<Map<String, Object>> afterCaptor = mapCaptor();
        verify(webAuditor).success(
                eq(OperationAuditService.ACTION_CONFIG_UPDATE),
                eq(OperationAuditService.TARGET_TYPE_AI_CONFIG),
                isNull(),
                eq(ADMIN_KEY),
                beforeCaptor.capture(),
                afterCaptor.capture(),
                eq(Set.of(ADMIN_KEY)));

        assertThat(beforeCaptor.getValue()).containsEntry(ADMIN_KEY, SensitiveFieldMasker.CHANGED_PLACEHOLDER);
        assertThat(afterCaptor.getValue()).containsEntry(ADMIN_KEY, SensitiveFieldMasker.CHANGED_PLACEHOLDER);
        assertThat(beforeCaptor.getValue().toString())
                .as("引用名是密钥来源线索，绝不进审计")
                .doesNotContain("DEEPSEEK_API_KEY");
        assertThat(afterCaptor.getValue().toString()).doesNotContain("INTERNAL_KEY_REF");
    }

    @Test
    @DisplayName("密钥类恢复默认：生效值回落默认引用名，但审计仍只记 <changed>，默认引用名也不落库")
    void secretClassResetStillMasksReferenceName() {
        when(mapper.selectAll())
                .thenReturn(List.of(row(ADMIN_KEY, "INTERNAL_KEY_REF", 3)))
                .thenReturn(List.of(row(ADMIN_KEY, null, 4)));
        when(mapper.maxVersion()).thenReturn(3L);
        when(mapper.updateValue(any())).thenReturn(1);

        Result<AiConfigController.AiConfigChangeResult> result = controller.change(
                new AiConfigController.AiConfigChangeRequest(ADMIN_KEY, null));

        assertThat(result.data().changed()).isTrue();
        assertThat(result.data().value())
                .as("清空显式值 = 回落到目录默认引用名（页面显示『已配置/未配置 + 引用名』）")
                .isEqualTo("DEEPSEEK_API_KEY");

        ArgumentCaptor<Map<String, Object>> beforeCaptor = mapCaptor();
        ArgumentCaptor<Map<String, Object>> afterCaptor = mapCaptor();
        verify(webAuditor).success(any(), any(), any(), eq(ADMIN_KEY),
                beforeCaptor.capture(), afterCaptor.capture(), any());

        assertThat(beforeCaptor.getValue()).containsEntry(ADMIN_KEY, SensitiveFieldMasker.CHANGED_PLACEHOLDER);
        assertThat(afterCaptor.getValue()).containsEntry(ADMIN_KEY, SensitiveFieldMasker.CHANGED_PLACEHOLDER);
        assertThat(beforeCaptor.getValue().toString()).doesNotContain("INTERNAL_KEY_REF");
        assertThat(afterCaptor.getValue().toString())
                .as("回落后的默认引用名同样不得进审计").doesNotContain("DEEPSEEK_API_KEY");
    }

    // ==================================================================
    // 普通配置：before → after 逐项可读
    // ==================================================================

    @Test
    @DisplayName("普通配置变更：审计 before/after 是「配置键 → 值」的结构化快照")
    void normalConfigAuditCarriesBeforeAndAfter() {
        when(mapper.selectAll())
                .thenReturn(List.of(row(TEMPERATURE_KEY, "0.2", 1)))
                .thenReturn(List.of(row(TEMPERATURE_KEY, "0.55", 2)));
        when(mapper.maxVersion()).thenReturn(1L);
        when(mapper.updateValue(any())).thenReturn(1);

        Result<AiConfigController.AiConfigChangeResult> result = controller.change(
                new AiConfigController.AiConfigChangeRequest(TEMPERATURE_KEY, "0.55"));

        assertThat(result.data().changed()).isTrue();
        assertThat(result.data().version()).isEqualTo(2);

        verify(webAuditor).success(
                eq(OperationAuditService.ACTION_CONFIG_UPDATE),
                eq(OperationAuditService.TARGET_TYPE_AI_CONFIG),
                isNull(),
                eq(TEMPERATURE_KEY),
                eq(Map.of(TEMPERATURE_KEY, "0.2")),
                eq(Map.of(TEMPERATURE_KEY, "0.55")),
                eq(Set.of(TEMPERATURE_KEY)));
    }

    @Test
    @DisplayName("恢复默认值：after 是目录默认值，不是空")
    void resetFallsBackToCatalogDefaultInAudit() {
        when(mapper.selectAll())
                .thenReturn(List.of(row(TEMPERATURE_KEY, "0.9", 5)))
                .thenReturn(List.of(row(TEMPERATURE_KEY, null, 6)));
        when(mapper.maxVersion()).thenReturn(5L);
        when(mapper.updateValue(any())).thenReturn(1);

        Result<AiConfigController.AiConfigChangeResult> result = controller.change(
                new AiConfigController.AiConfigChangeRequest(TEMPERATURE_KEY, null));

        assertThat(result.data().value()).as("reset 后生效值是默认值").isEqualTo("0.2");
        verify(webAuditor).success(any(), any(), any(), eq(TEMPERATURE_KEY),
                eq(Map.of(TEMPERATURE_KEY, "0.9")), eq(Map.of(TEMPERATURE_KEY, "0.2")), any());
    }

    // ==================================================================
    // 值未变化：不写、不审计
    // ==================================================================

    @Test
    @DisplayName("值未变化（含归一化后相同）：不落库、不写审计、版本号不动")
    void unchangedValueIsNoOp() {
        when(mapper.selectAll()).thenReturn(List.of(row(TEMPERATURE_KEY, "0.2", 1)));

        Result<AiConfigController.AiConfigChangeResult> result = controller.change(
                new AiConfigController.AiConfigChangeRequest(TEMPERATURE_KEY, "0.20"));

        assertThat(result.data().changed()).as("0.20 归一化后就是 0.2").isFalse();
        assertThat(result.data().version()).isEqualTo(1);
        verifyNoInteractions(webAuditor);
        verify(mapper, never()).updateValue(any());
        verify(mapper, never()).insert(any());
    }

    // ==================================================================
    // 非法值与未知键
    // ==================================================================

    @Test
    @DisplayName("非法值/未知键：触库前拒绝且不写审计（AC-CFG-07）")
    void invalidValueOrUnknownKeyIsRejectedBeforeAnyWrite() {
        assertThatThrownBy(() -> controller.change(
                new AiConfigController.AiConfigChangeRequest(TEMPERATURE_KEY, "9")))
                .isInstanceOf(BizException.class).hasMessageContaining("超出允许范围");

        assertThatThrownBy(() -> controller.change(
                new AiConfigController.AiConfigChangeRequest("not.exists", "1")))
                .isInstanceOf(BizException.class).hasMessageContaining("未知的配置项");

        verifyNoInteractions(webAuditor);
        verifyNoInteractions(mapper);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }
}
