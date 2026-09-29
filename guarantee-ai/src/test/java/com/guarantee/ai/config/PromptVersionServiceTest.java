package com.guarantee.ai.config;

import com.guarantee.ai.config.mapper.AiPromptVersionMapper;
import com.guarantee.ai.service.BusinessAssistantPrompt;
import com.guarantee.ai.service.OperationAuditService;
import com.guarantee.common.exception.BizException;
import com.guarantee.system.service.WebAuditor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 提示词版本机与发布门禁（TEST-CFG-04 / AC-CFG-02/03/09/10）。
 *
 * <p>不需要数据库、也不会真的去起 Node：{@link AiPromptVersionMapper} 用 Mockito 打桩，
 * 门禁用可替换的 {@link PromptVersionService.PromptGate} 打桩。重点守住五条：</p>
 * <ol>
 *   <li><b>草稿不可运行</b>：没有 PUBLISHED 版本时 {@code currentContent()} 为空，
 *       助手回落到 classpath 内置提示词（冷启动必须可用）；</li>
 *   <li><b>发布后生效</b>：发布把目标版本置为 PUBLISHED，运行期读到的是 DB 正文而不是 classpath；</li>
 *   <li><b>已发布不可改</b>：对非草稿执行发布会拒绝，且不会调用发布 SQL；</li>
 *   <li><b>回滚产生审计</b>：回滚把历史版本重新发布，并写 {@code CONFIG_UPDATE}/{@code AI_CONFIG} 审计；</li>
 *   <li><b>保护标记缺失 / 门禁未跑 → 拒绝发布</b>（门禁"未跑"绝不默认放行）。</li>
 * </ol>
 */
class PromptVersionServiceTest {

    private AiPromptVersionMapper mapper;
    private AiConfigService configService;
    private WebAuditor webAuditor;
    private PromptVersionService.PromptGate gate;
    private Environment environment;
    private PromptVersionService service;

    /** 合法正文：包含全部受保护标记。 */
    private static final String VALID_CONTENT =
            String.join("\n", PromptVersionService.PROTECTED_MARKERS) + "\n正文……";

    @BeforeEach
    void setUp() {
        mapper = mock(AiPromptVersionMapper.class);
        configService = mock(AiConfigService.class);
        webAuditor = mock(WebAuditor.class);
        gate = mock(PromptVersionService.PromptGate.class);
        environment = mock(Environment.class);
        // 报告目录给显式默认值：Mockito 的 mock 不会回落到方法签名的 default 参数
        when(environment.getProperty("guarantee.ai.eval.report-dir", "reports")).thenReturn("reports");
        service = new PromptVersionService(mapper, configService, webAuditor, gate,
                environment, new ObjectMapper(), noopTransactionManager());
    }

    /** 用指定报告目录重建服务（真机集状态测试用）。 */
    private PromptVersionService serviceWithReportDir(String reportDir) {
        Environment env = mock(Environment.class);
        when(env.getProperty("guarantee.ai.eval.report-dir", "reports")).thenReturn(reportDir);
        return new PromptVersionService(mapper, configService, webAuditor, gate,
                env, new ObjectMapper(), noopTransactionManager());
    }

    /**
     * 空事务管理器：{@code TransactionTemplate} 需要一个真实的 {@code PlatformTransactionManager}，
     * 这里用最小实现让它"原样同步执行回调"——单测要验证的是"门禁先评估、切版本在短事务里"，
     * 不需要真的数据库事务（那由集成测试覆盖）。
     */
    private static org.springframework.transaction.PlatformTransactionManager noopTransactionManager() {
        return new org.springframework.transaction.PlatformTransactionManager() {
            @Override
            public org.springframework.transaction.TransactionStatus getTransaction(
                    org.springframework.transaction.TransactionDefinition definition) {
                return new org.springframework.transaction.support.SimpleTransactionStatus();
            }

            @Override
            public void commit(org.springframework.transaction.TransactionStatus status) {
                // no-op
            }

            @Override
            public void rollback(org.springframework.transaction.TransactionStatus status) {
                // no-op
            }
        };
    }

    private static AiPromptVersion version(int no, String status, String content) {
        AiPromptVersion v = new AiPromptVersion();
        v.setVersionNo(no);
        v.setStatus(status);
        v.setContent(content);
        v.setContentHash(PromptVersionService.sha256(content));
        v.setNote("note-" + no);
        v.setCreatedBy("7");
        return v;
    }

    // ==================================================================
    // 草稿不可运行 + classpath 兜底（AC-CFG-02 前半 / 冷启动）
    // ==================================================================

    @Test
    @DisplayName("没有 PUBLISHED 版本时：currentContent 为空，助手回落到 classpath 内置提示词（冷启动可用）")
    void draftIsNotRunnableAndClasspathIsTheFallback() {
        when(mapper.selectPublished()).thenReturn(null);

        assertThat(service.currentContent()).as("草稿不是运行期内容").isNull();

        BusinessAssistantPrompt prompt = new BusinessAssistantPrompt(
                new ClassPathResource("prompts/business-assistant.st"), service);
        String template = prompt.loadTemplate();

        assertThat(template).as("必须回落到 jar 内提示词，而不是空串或异常")
                .isNotBlank()
                .contains("# 铁律：数据必须来自 Tool");
    }

    @Test
    @DisplayName("有 PUBLISHED 版本时：运行期读 DB 正文（下一个请求生效）")
    void publishedVersionIsUsedAtRuntime() {
        when(mapper.selectPublished()).thenReturn(version(3, AiPromptVersion.STATUS_PUBLISHED, "DB-PUBLISHED-BODY"));
        BusinessAssistantPrompt prompt = new BusinessAssistantPrompt(
                new ClassPathResource("prompts/business-assistant.st"), service);

        assertThat(prompt.loadTemplate()).isEqualTo("DB-PUBLISHED-BODY");
    }

    @Test
    @DisplayName("读库失败时也回落 classpath（DB 抖动不能让助手整体不可用）")
    void databaseFailureFallsBackToClasspath() {
        when(mapper.selectPublished()).thenThrow(new IllegalStateException("table not exists"));

        assertThat(service.currentContent()).isNull();
    }

    // ==================================================================
    // 发布
    // ==================================================================

    @Test
    @DisplayName("发布草稿：归档旧发布版 → 置为 PUBLISHED → 同步配置投影 → 写审计")
    void publishArchivesPreviousAndSwitchesActiveVersion() {
        AiPromptVersion draft = version(4, AiPromptVersion.STATUS_DRAFT, VALID_CONTENT);
        when(mapper.selectByVersionNo(4)).thenReturn(draft, version(4, AiPromptVersion.STATUS_PUBLISHED, VALID_CONTENT));
        when(mapper.publish(eq(4), anyString())).thenReturn(1);
        when(gate.evaluate()).thenReturn(PromptVersionService.GateResult.passed("全绿"));

        AiPromptVersion published = service.publish(4, "7");

        assertThat(published.getStatus()).isEqualTo(AiPromptVersion.STATUS_PUBLISHED);
        verify(mapper).archivePublishedExcept(4);
        verify(mapper).publish(4, "7");
        verify(configService).update(eq(AiConfigCatalog.PROMPT_ACTIVE_VERSION), eq("4"), eq("7"));
        verify(webAuditor).success(
                eq(OperationAuditService.ACTION_CONFIG_UPDATE),
                eq(OperationAuditService.TARGET_TYPE_AI_CONFIG),
                eq(4L),
                eq("prompt.v4"),
                any(), any(), eq(Set.of("status", "contentHash")));
    }

    @Test
    @DisplayName("已发布版本不可再发布（也不可原地修改草稿）")
    void publishedVersionIsImmutable() {
        when(mapper.selectByVersionNo(2)).thenReturn(version(2, AiPromptVersion.STATUS_PUBLISHED, VALID_CONTENT));

        assertThatThrownBy(() -> service.publish(2, "7"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只有草稿可以发布");

        verify(mapper, never()).publish(anyInt(), anyString());
        verify(mapper, never()).archivePublishedExcept(anyInt());
    }

    @Test
    @DisplayName("已有发布版但没有草稿时，保存草稿会新建版本而不是改发布版")
    void saveDraftCreatesNewVersionWhenNoDraftExists() {
        when(mapper.selectLatestDraft()).thenReturn(null);
        when(mapper.maxVersionNo()).thenReturn(5);
        when(mapper.selectByVersionNo(6)).thenReturn(version(6, AiPromptVersion.STATUS_DRAFT, VALID_CONTENT));

        service.saveDraft(VALID_CONTENT, "首次编辑", "7");

        ArgumentCaptor<AiPromptVersion> captor = ArgumentCaptor.forClass(AiPromptVersion.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getVersionNo()).isEqualTo(6);
        assertThat(captor.getValue().getStatus()).isEqualTo(AiPromptVersion.STATUS_DRAFT);
        assertThat(captor.getValue().getContentHash()).isEqualTo(PromptVersionService.sha256(VALID_CONTENT));
        verify(mapper, never()).updateDraft(anyInt(), anyString(), anyString(), anyString());
    }

    // ==================================================================
    // 保护标记
    // ==================================================================

    @Test
    @DisplayName("发布内容缺失受保护段落 → 拒绝发布，并列出缺了哪一条（服务端校验）")
    void publishRejectsContentMissingProtectedMarkers() {
        String missingOne = String.join("\n",
                PromptVersionService.PROTECTED_MARKERS.subList(1, PromptVersionService.PROTECTED_MARKERS.size()));
        when(mapper.selectByVersionNo(7)).thenReturn(version(7, AiPromptVersion.STATUS_DRAFT, missingOne));

        assertThatThrownBy(() -> service.publish(7, "7"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("缺少受保护段落")
                .hasMessageContaining(PromptVersionService.PROTECTED_MARKERS.get(0));

        verify(mapper, never()).publish(anyInt(), anyString());
        verifyNoInteractions(gate);
    }

    @Test
    @DisplayName("缺失标记可被检出（页面据此给出强警告）")
    void missingMarkersAreReported() {
        assertThat(PromptVersionService.missingProtectedMarkers(VALID_CONTENT)).isEmpty();
        assertThat(PromptVersionService.missingProtectedMarkers("只有正文"))
                .containsExactlyElementsOf(PromptVersionService.PROTECTED_MARKERS);
    }

    // ==================================================================
    // 发布门禁（AC-CFG-10）
    // ==================================================================

    @Test
    @DisplayName("门禁未跑（脚本不存在/参数未实现/执行失败）→ 拒绝发布，且不发布不放行")
    void gateNotRunRejectsPublish() {
        when(mapper.selectByVersionNo(8)).thenReturn(version(8, AiPromptVersion.STATUS_DRAFT, VALID_CONTENT));
        when(gate.evaluate()).thenReturn(PromptVersionService.GateResult.notRun("node 命令无法执行"));

        assertThatThrownBy(() -> service.publish(8, "7"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("门禁未跑")
                .hasMessageContaining("node 命令无法执行");

        verify(mapper, never()).publish(anyInt(), anyString());
        verify(mapper, never()).archivePublishedExcept(anyInt());
    }

    @Test
    @DisplayName("门禁跑了但未全绿 → 拒绝发布")
    void gateFailureRejectsPublish() {
        when(mapper.selectByVersionNo(9)).thenReturn(version(9, AiPromptVersion.STATUS_DRAFT, VALID_CONTENT));
        when(gate.evaluate()).thenReturn(PromptVersionService.GateResult.failed("3 条断言失败"));

        assertThatThrownBy(() -> service.publish(9, "7"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("门禁未通过")
                .hasMessageContaining("3 条断言失败");

        verify(mapper, never()).publish(anyInt(), anyString());
    }

    // ==================================================================
    // 回滚（AC-CFG-03 / AC-CFG-09）
    // ==================================================================

    @Test
    @DisplayName("回滚：把历史版本重新置为 PUBLISHED + 写审计；不重跑门禁（应急路径）")
    void rollbackRepublishesHistoryAndWritesAudit() {
        AiPromptVersion archived = version(2, AiPromptVersion.STATUS_ARCHIVED, VALID_CONTENT);
        when(mapper.selectByVersionNo(2))
                .thenReturn(archived, version(2, AiPromptVersion.STATUS_PUBLISHED, VALID_CONTENT));
        when(mapper.publish(eq(2), anyString())).thenReturn(1);

        AiPromptVersion rolledBack = service.rollback(2, "7");

        assertThat(rolledBack.getStatus()).isEqualTo(AiPromptVersion.STATUS_PUBLISHED);
        verify(mapper).archivePublishedExcept(2);
        verify(mapper).publish(2, "7");
        verify(webAuditor).success(
                eq(OperationAuditService.ACTION_CONFIG_UPDATE),
                eq(OperationAuditService.TARGET_TYPE_AI_CONFIG),
                eq(2L),
                eq("prompt.v2"),
                any(), any(), any());
        verifyNoInteractions(gate);
    }

    @Test
    @DisplayName("不能回滚到草稿；对已是生效的版本回滚给出可读提示")
    void rollbackRejectsDraftAndCurrentPublished() {
        when(mapper.selectByVersionNo(1)).thenReturn(version(1, AiPromptVersion.STATUS_DRAFT, VALID_CONTENT));
        assertThatThrownBy(() -> service.rollback(1, "7"))
                .isInstanceOf(BizException.class).hasMessageContaining("不能回滚到草稿");

        when(mapper.selectByVersionNo(3)).thenReturn(version(3, AiPromptVersion.STATUS_PUBLISHED, VALID_CONTENT));
        assertThatThrownBy(() -> service.rollback(3, "7"))
                .isInstanceOf(BizException.class).hasMessageContaining("已是生效版本");

        verify(mapper, never()).publish(anyInt(), anyString());
    }

    @Test
    @DisplayName("版本不存在 → 可读错误，不是 NullPointerException")
    void unknownVersionIsReadable() {
        when(mapper.selectByVersionNo(404)).thenReturn(null);
        assertThatThrownBy(() -> service.publish(404, "7"))
                .isInstanceOf(BizException.class).hasMessageContaining("提示词版本不存在：v404");
    }

    @Test
    @DisplayName("门禁结果如实透传：ran=false / passed=false 不会被包装成通过")
    void gateResultIsExposedFaithfully() {
        when(gate.evaluate()).thenReturn(PromptVersionService.GateResult.notRun("未实现 --suite"));
        PromptVersionService.GateResult result = service.evaluateGate();
        assertThat(result.ran()).isFalse();
        assertThat(result.passed()).isFalse();
        assertThat(result.summary()).contains("未实现 --suite");
    }

    @Test
    @DisplayName("草稿允许暂时缺保护标记（编辑中），但正文不能为空")
    void saveDraftAllowsIncompleteContentButNotBlank() {
        when(mapper.selectLatestDraft()).thenReturn(version(11, AiPromptVersion.STATUS_DRAFT, "旧草稿"));
        when(mapper.updateDraft(eq(11), anyString(), anyString(), isNull())).thenReturn(1);
        when(mapper.selectByVersionNo(11)).thenReturn(version(11, AiPromptVersion.STATUS_DRAFT, "半成品"));

        service.saveDraft("半成品", null, "7");

        verify(mapper).updateDraft(eq(11), eq("半成品"),
                eq(PromptVersionService.sha256("半成品")), isNull());
        assertThatThrownBy(() -> service.saveDraft("  ", null, "7"))
                .isInstanceOf(BizException.class).hasMessageContaining("不能为空");
    }

    @Test
    @DisplayName("并发发布把草稿改掉时，保存草稿明确报错而不是静默丢失编辑内容")
    void saveDraftFailsLoudlyWhenDraftWasPublishedConcurrently() {
        when(mapper.selectLatestDraft()).thenReturn(version(12, AiPromptVersion.STATUS_DRAFT, "旧草稿"));
        when(mapper.updateDraft(eq(12), anyString(), anyString(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.saveDraft("新内容", null, "7"))
                .isInstanceOf(BizException.class).hasMessageContaining("已不是草稿状态");
    }

    @Test
    @DisplayName("未被拒绝的发布路径：审计快照里带状态与内容哈希，且不含正文全文")
    void publishAuditCarriesHashNotWholeContent() {
        AiPromptVersion draft = version(13, AiPromptVersion.STATUS_DRAFT, VALID_CONTENT);
        when(mapper.selectByVersionNo(13)).thenReturn(draft, version(13, AiPromptVersion.STATUS_PUBLISHED, VALID_CONTENT));
        when(mapper.publish(eq(13), anyString())).thenReturn(1);
        when(gate.evaluate()).thenReturn(PromptVersionService.GateResult.passed("全绿"));

        service.publish(13, "7");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        verify(webAuditor).success(anyString(), anyString(), any(), anyString(),
                before.capture(), after.capture(), any());
        assertThat(after.getValue()).containsEntry("status", "PUBLISHED");
        assertThat(String.valueOf(after.getValue().get("contentHash")))
                .isEqualTo(PromptVersionService.sha256(VALID_CONTENT));
        assertThat(after.getValue().toString()).as("审计不落正文全文").doesNotContain("正文……");
        assertThat(before.getValue()).containsEntry("status", "DRAFT");
    }

    // ==================================================================
    // 真机集（--suite=live）状态：只读报告、绝不伪造（AC-CFG-10 子句②）
    // ==================================================================

    @Test
    @DisplayName("真机集：报告目录里没有 eval-live-*.json → NOT_RUN，且原因写清『未跑』")
    void liveGateIsNotRunWithoutReport(@TempDir Path emptyDir) {
        PromptVersionService.LiveGate live = serviceWithReportDir(emptyDir.toString()).liveGate();

        assertThat(live.status()).isEqualTo(PromptVersionService.LIVE_NOT_RUN);
        assertThat(live.at()).isNull();
        assertThat(live.reason()).contains("未跑");
    }

    @Test
    @DisplayName("真机集：报告全绿 → PASSED；有失败 → FAILED；有未跑 → NOT_RUN（读报告事实，不猜）")
    void liveGateReadsReportFacts(@TempDir Path dir) throws Exception {
        writeLiveReport(dir, "eval-live-2026-09-01.json", 25, 25, 0, 0);
        PromptVersionService.LiveGate passed = serviceWithReportDir(dir.toString()).liveGate();
        assertThat(passed.status()).isEqualTo(PromptVersionService.LIVE_PASSED);
        assertThat(passed.reason()).contains("25/25");

        writeLiveReport(dir, "eval-live-2026-09-02.json", 25, 20, 5, 0);
        PromptVersionService.LiveGate failed = serviceWithReportDir(dir.toString()).liveGate();
        assertThat(failed.status()).isEqualTo(PromptVersionService.LIVE_FAILED);
        assertThat(failed.reason()).contains("5 条失败");
        assertThat(failed.source()).isEqualTo("eval-live-2026-09-02.json");

        writeLiveReport(dir, "eval-live-2026-09-03.json", 25, 0, 0, 25);
        PromptVersionService.LiveGate notRun = serviceWithReportDir(dir.toString()).liveGate();
        assertThat(notRun.status()).isEqualTo(PromptVersionService.LIVE_NOT_RUN);
        assertThat(notRun.reason()).contains("未跑");
    }

    /** 写一份最小可解析的真机集报告（字段与 ai-golden-questions.mjs 输出同构）。 */
    private static void writeLiveReport(Path dir, String fileName, int total, int passed, int failed,
                                        int notRun) throws Exception {
        String json = """
                {
                  "generatedAt": "2026-09-30T00:00:00.000Z",
                  "suite": "live",
                  "status": { "live": %s },
                  "totals": { "total": %d, "passed": %d, "failed": %d, "notRun": %d }
                }
                """.formatted(notRun > 0 ? "\"environment\"" : "\"ok\"", total, passed, failed, notRun);
        Files.writeString(dir.resolve(fileName), json);
    }
}
