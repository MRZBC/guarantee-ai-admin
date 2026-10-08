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

/**
 * 提示词版本生命周期的真实库集成测试（AC-CFG-02 / AC-CFG-03）。
 *
 * <p><b>为什么要有真库 IT</b>：单测证明了版本机的分支逻辑，证明不了"发布之后**同一个进程
 * 的下一次请求**真的读到了新正文"——而那正是 AC-CFG-02 要判定的东西。这里走完整链路：
 * 保存草稿 → 发布 → {@link BusinessAssistantPrompt#loadTemplate()} 读到 DB 正文 →
 * 再发一版 → 回滚 → 读回旧正文 → 审计落库。</p>
 *
 * <p><b>门禁</b>：用一个"一定成功且跨平台"的命令（{@code java -version}）把门禁打桩掉——
 * 本 IT 验证的是**门禁通过之后**的发布/回滚/生效链路；
 * "门禁未跑 / 未全绿必须拒绝"由 {@code PromptVersionServiceTest} 用假门禁覆盖。</p>
 *
 * <p><b>桩命令为什么不能写 {@code cmd /c exit 0}</b>：那是 Windows 专有命令。CI（ubuntu runner）
 * 上 {@code CommandPromptGate} 会以 {@code Cannot run program "cmd": ... (No such file or directory)}
 * 判为"门禁未跑"，进而拒绝发布，本 IT 就以 {@code 发布门禁未跑} 报错
 * （2026-10-08 CI 实测）。同时门禁实现是 {@code new ProcessBuilder(command.trim().split("\\s+"))}，
 * **按空白切分、不支持引号**，所以桩命令必须是"无引号 + JDK 自带工具"的形态：
 * {@code java -version} 满足（Maven 能跑就一定有 java），退出码 0，任何平台都不会"命令不存在"。</p>
 *
 * <p><b>不污染共享开发库</b>：用例创建的所有版本行与 {@code prompt.active-version} 配置行
 * 在 {@code @AfterEach} 里按版本号精确删除（审计行按既有约定保留：审计只增不删）。</p>
 */
@SpringBootTest(
        classes = GuaranteeAiAdminApplication.class,
        properties = {
                "guarantee.data-init.enabled=false",
                // 跨平台的可打桩门禁命令：任何平台都"命令存在 + 退出码 0"
                // （详见类注释里为什么不能用 cmd /c）
                "guarantee.ai.prompt.gate-command=java -version"
        })
class PromptVersionLifecycleIT {

    @Autowired
    private PromptVersionService promptVersionService;

    @Autowired
    private BusinessAssistantPrompt businessAssistantPrompt;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Integer> createdVersions = new ArrayList<>();

    @BeforeEach
    void setUp() {
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
        AiPromptVersion draftA = promptVersionService.saveDraft(
                bodyWithMarkers("IT-BODY-A"), "第一版", "1");
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
        AiPromptVersion draftB = promptVersionService.saveDraft(
                bodyWithMarkers("IT-BODY-B"), "第二版", "1");
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
                .hasMessageContaining("缺少受保护段落");

        assertThat(statusOf(draft.getVersionNo())).as("被拒后状态不得变化")
                .isEqualTo(AiPromptVersion.STATUS_DRAFT);
        assertThat(publishedCount()).isZero();
    }

    // ==================================================================
    // 辅助
    // ==================================================================

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
