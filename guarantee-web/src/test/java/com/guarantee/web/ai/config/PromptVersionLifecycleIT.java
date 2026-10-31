package com.guarantee.web.ai.config;

import com.guarantee.ai.config.AiConfigCatalog;
import com.guarantee.ai.config.AiPromptVersion;
import com.guarantee.ai.config.PromptVersionService;
import com.guarantee.ai.service.BusinessAssistantPrompt;
import com.guarantee.common.security.CurrentUser;
import com.guarantee.common.security.Roles;
import com.guarantee.web.GuaranteeAiAdminApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 版本切换与审计的数据库集成测试；候选评测另由专门测试覆盖。 */
@SpringBootTest(
        classes = GuaranteeAiAdminApplication.class,
        properties = {
                "guarantee.data-init.enabled=false"
        })
@org.springframework.transaction.annotation.Transactional
class PromptVersionLifecycleIT {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.guarantee.ai.config.PromptEvaluationService evaluations;


    @Autowired
    private PromptVersionService promptVersionService;

    @Autowired
    private BusinessAssistantPrompt businessAssistantPrompt;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private com.guarantee.ai.config.mapper.PromptEvaluationMapper evaluationMapper;
    @Autowired
    private com.guarantee.ai.config.mapper.AiPromptVersionMapper versionMapper;

    @Test
    void evaluationSnapshotAndTerminalStateArePersisted() {
        var row = new com.guarantee.ai.config.PromptEvaluation();
        row.setId(java.util.UUID.randomUUID().toString()); row.setVersionNo(1000000);
        row.setContent("待评测正文"); row.setBaselineContent("当前生效正文");
        row.setContextSnapshot("{}"); row.setContentHash("hash"); row.setContextHash("context"); row.setSuiteVersion("test-suite");
        row.setStatus("RUNNING"); row.setSummary("运行中"); row.setCreatedBy("1");
        evaluationMapper.insert(row);
        var loaded = evaluationMapper.select(row.getId());
        assertThat(loaded.getContent()).isEqualTo("待评测正文");
        assertThat(loaded.getBaselineContent()).isEqualTo("当前生效正文");
        assertThat(loaded.getCreatedAt()).isNotNull();
        row.setStatus("PASSED"); row.setSummary("1/1"); row.setResults("[]");
        assertThat(evaluationMapper.finish(row)).isEqualTo(1);
        assertThat(evaluationMapper.finish(row)).as("终态不可覆盖").isZero();
        assertThat(evaluationMapper.select(row.getId()).getFinishedAt()).isNotNull();
    }

    private final List<Integer> createdVersions = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // 事务内隔离既有版本，方法结束全部回滚，不发布或覆盖用户真实版本。
        jdbcTemplate.update("UPDATE ai_prompt_version SET status='ARCHIVED' WHERE is_deleted=0");
        // 直接调用 Service（不走 HTTP）时没有 JWT 线程上下文，必须显式建立身份：
        // 审计的可信度来自"每条记录都能定位到人"，OperationAuditPortAdapter 对空身份是拒绝的。
        CurrentUser.set(new CurrentUser.Principal(1L, "admin", "超级管理员",
                List.of(Roles.ADMIN), List.of()));
        jdbcTemplate.update("DELETE FROM ai_config_item WHERE config_key = ?",
                AiConfigCatalog.PROMPT_ACTIVE_VERSION);
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
        for (Integer versionNo : createdVersions) {
            jdbcTemplate.update("DELETE FROM ai_prompt_version WHERE version_no = ?", versionNo);
        }
        jdbcTemplate.update("DELETE FROM ai_config_item WHERE config_key = ?",
                AiConfigCatalog.PROMPT_ACTIVE_VERSION);
    }

    private String bodyWithMarkers(String marker) {
        return String.join("\n", PromptVersionService.PROTECTED_MARKERS)
                + "\n" + marker;
    }

    @Test
    @DisplayName("发布后同一进程的下一个请求读到 DB 正文；回滚后又读回旧正文；两次都写审计")
    void publishTakesEffectAndRollbackRestoresPreviousVersion() {
        // 0) 起点：库里没有发布版 → 落到 classpath 内置提示词
        assertThat(businessAssistantPrompt.loadTemplate())
                .as("没有任何发布版时必须回落 classpath（冷启动可用）")
                .contains("# 铁律：数据必须来自 Tool");

        // 1) 草稿 A → 发布
        AiPromptVersion draftA = saveBusiness("IT-BODY-A", "第一版");
        createdVersions.add(draftA.getVersionNo());
        assertThat(draftA.getStatus()).isEqualTo(AiPromptVersion.STATUS_DRAFT);
        assertThat(versionContent(draftA.getVersionNo())).contains("IT-BODY-A");

        AiPromptVersion publishedA = promptVersionService.publish(draftA.getVersionNo(), "1");
        assertThat(publishedA.getStatus()).isEqualTo(AiPromptVersion.STATUS_PUBLISHED);

        // AC-CFG-02：发布后，运行期读到的是 DB 正文（不重启）
        assertThat(businessAssistantPrompt.loadTemplate())
                .as("发布后下一个请求必须用新正文").contains("IT-BODY-A");
        assertThat(businessAssistantPrompt.activeVersionNo()).isEqualTo(draftA.getVersionNo());
        assertThat(activeConfigVersion()).as("配置投影指向当前生效版本")
                .isEqualTo(String.valueOf(draftA.getVersionNo()));

        // 2) 草稿 B → 发布：同一时刻只有一个 PUBLISHED
        AiPromptVersion draftB = saveBusiness("IT-BODY-B", "第二版");
        createdVersions.add(draftB.getVersionNo());
        promptVersionService.publish(draftB.getVersionNo(), "1");

        assertThat(businessAssistantPrompt.loadTemplate()).contains("IT-BODY-B");
        assertThat(publishedCount()).as("同一时刻只能有一个 PUBLISHED").isEqualTo(1);
        assertThat(statusOf(draftA.getVersionNo())).as("旧发布版被归档而不是删除")
                .isEqualTo(AiPromptVersion.STATUS_ARCHIVED);

        // 3) 回滚到 A：下个请求读回旧正文，且产生审计（AC-CFG-03）
        promptVersionService.rollback(draftA.getVersionNo(), "1");

        assertThat(businessAssistantPrompt.loadTemplate())
                .as("回滚后下一个请求回到旧版本正文").contains("IT-BODY-A");
        assertThat(statusOf(draftA.getVersionNo())).isEqualTo(AiPromptVersion.STATUS_PUBLISHED);
        assertThat(statusOf(draftB.getVersionNo())).isEqualTo(AiPromptVersion.STATUS_ARCHIVED);
        assertThat(publishedCount()).isEqualTo(1);

        // 4) 审计：发布与回滚各留一条 CONFIG_UPDATE / AI_CONFIG，target_name 是提示词版本号
        List<Map<String, Object>> audits = jdbcTemplate.queryForList("""
                SELECT action, target_type, target_id, target_name, result
                FROM ai_operation_audit
                WHERE target_type = 'AI_CONFIG' AND target_name = ?
                """, PromptVersionService.TARGET_NAME_PREFIX + draftA.getVersionNo());
        assertThat(audits).as("发布与回滚都必须可追溯到具体版本号").isNotEmpty();
        assertThat(audits).allSatisfy(audit -> {
            assertThat(audit.get("action")).isEqualTo("CONFIG_UPDATE");
            assertThat(audit.get("target_type")).isEqualTo("AI_CONFIG");
            assertThat(((Number) audit.get("target_id")).intValue()).isEqualTo(draftA.getVersionNo());
        });
    }

    @Test
    @DisplayName("保护标记缺失的正文：即使门禁通过也拒绝发布（服务端校验，不靠前端警告）")
    void publishWithoutProtectedMarkersIsRejectedEvenWhenGatePasses() {
        AiPromptVersion draft = promptVersionService.saveDraft("只有一段普通正文，没有红线小标题", "坏草稿", "1");
        createdVersions.add(draft.getVersionNo());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> promptVersionService.publish(draft.getVersionNo(), "1"))
                .isInstanceOf(com.guarantee.common.exception.BizException.class)
                .hasMessageContaining("人工核对迁移");

        assertThat(statusOf(draft.getVersionNo())).as("被拒后状态不得变化")
                .isEqualTo(AiPromptVersion.STATUS_DRAFT);
        assertThat(publishedCount()).isZero();
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    @Test
    void legacyMigrationPersistsSourceAndDoesNotResurrectOldDraft() {
        var legacy = promptVersionService.saveDraft(bodyWithMarkers("LEGACY-CUSTOM"), "旧完整草稿", "1");
        var base = com.guarantee.ai.config.PromptComposition.base();
        var migrated = promptVersionService.saveBusinessDraft("保留业务补充", "人工迁移", base.version(),
                base.contentHash(), legacy.getVersionNo(), legacy.getContentHash(), true, "1");
        assertThat(migrated.getVersionNo()).isGreaterThan(legacy.getVersionNo());
        assertThat(migrated.getMigrationSourceHash()).isEqualTo(legacy.getContentHash());
        assertThat(migrated.getBaseContent()).isEqualTo(base.content());
        assertThat(migrated.getBusinessContent()).isEqualTo("保留业务补充");
        assertThat(versionContent(legacy.getVersionNo())).isEqualTo(legacy.getContent());
        promptVersionService.publish(migrated.getVersionNo(), "1");
        assertThat(promptVersionService.draft()).isNull();
        migrated.setBusinessContent("试图修改已发布快照");
        assertThat(versionMapper.saveComposition(migrated)).isZero();
        assertThat(promptVersionService.requireVersion(migrated.getVersionNo()).getBusinessContent()).isEqualTo("保留业务补充");
        assertThat(businessAssistantPrompt.loadTemplate()).isEqualTo(migrated.getContent());
        assertThat(promptVersionService.requireVersion(migrated.getVersionNo()).getBaseHash()).isEqualTo(base.contentHash());
    }

    private AiPromptVersion saveBusiness(String body, String note) {
        var base = com.guarantee.ai.config.PromptComposition.base();
        return promptVersionService.saveBusinessDraft(body, note, base.version(), base.contentHash(),
                null, null, false, "1");
    }

    private String versionContent(int versionNo) {
        return jdbcTemplate.queryForObject(
                "SELECT content FROM ai_prompt_version WHERE version_no = ?", String.class, versionNo);
    }

    private String statusOf(int versionNo) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM ai_prompt_version WHERE version_no = ?", String.class, versionNo);
    }

    private int publishedCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_prompt_version WHERE status = 'PUBLISHED'", Integer.class);
        return count == null ? 0 : count;
    }

    private String activeConfigVersion() {
        List<String> values = jdbcTemplate.queryForList(
                "SELECT config_value FROM ai_config_item WHERE config_key = ?",
                String.class, AiConfigCatalog.PROMPT_ACTIVE_VERSION);
        return values.isEmpty() ? null : values.get(0);
    }
}
