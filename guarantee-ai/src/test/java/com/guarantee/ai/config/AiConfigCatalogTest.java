package com.guarantee.ai.config;

import com.guarantee.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 配置项目录与值校验（TEST-CFG-01 前半部分 / AC-CFG-07）。
 *
 * <p>本类守住两件事：</p>
 * <ol>
 *   <li><b>默认值与改造前逐项一致</b>（AC-CFG-08）：temperature=0.2、history-limit=20、
 *       max-rounds=4、max-calls-per-round=12、soft-timeout-ms=60000、tool-result-bytes=16384、
 *       tool-timeout-ms=10000、base-url / model / api-key 引用名与 application.yml 相同；</li>
 *   <li><b>类型/范围/枚举校验给出可读错误</b>：非法值在触库之前就被拒绝。</li>
 * </ol>
 */
class AiConfigCatalogTest {

    private final AiConfigCatalog catalog = new AiConfigCatalog();

    // ==================================================================
    // 配置项清单与默认值（REQ §6.2 / AC-CFG-08）
    // ==================================================================

    @Test
    @DisplayName("配置项清单覆盖 REQ §6.2 全部条目（+ 工具超时项），键名不漂移")
    void catalogShouldContainExpectedKeys() {
        List<String> keys = catalog.all().stream().map(AiConfigDefinition::key).toList();

        assertThat(keys).containsExactlyInAnyOrder(
                // 模型 ×7
                "model.base-url", "model.name", "model.temperature", "model.max-tokens",
                "model.timeout", "model.max-retries", "model.api-key-ref",
                // 能力开关 ×6
                "tools.order.enabled", "tools.analysis.enabled", "tools.system.enabled",
                "tools.audit.enabled", "tools.proposal.enabled", "knowledge.enabled",
                // 预算 ×6（tool-timeout-ms 是 REQ §5.1.1"BoundedToolCallback 的超时改为读配置"的落地项）
                "budget.history-limit", "budget.max-rounds", "budget.max-calls-per-round",
                "budget.soft-timeout-ms", "budget.tool-result-bytes", "budget.tool-timeout-ms",
                // 提示词 ×1
                "prompt.active-version");
        assertThat(keys).as("配置键不得重复").doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("默认值与改造前行为逐项一致（AC-CFG-08）")
    void defaultsMustMatchPreChangeBehaviour() {
        Map<String, String> defaults = catalog.defaults();

        assertThat(defaults).containsEntry(AiConfigCatalog.MODEL_BASE_URL, "https://api.deepseek.com");
        assertThat(defaults).containsEntry(AiConfigCatalog.MODEL_NAME, "deepseek-chat");
        assertThat(defaults).containsEntry(AiConfigCatalog.MODEL_API_KEY_REF, "DEEPSEEK_API_KEY");
        assertThat(defaults).containsEntry(AiConfigCatalog.MODEL_TEMPERATURE, "0.2");
        assertThat(defaults).containsEntry(AiConfigCatalog.TOOLS_ORDER_ENABLED, "true");
        assertThat(defaults).containsEntry(AiConfigCatalog.TOOLS_ANALYSIS_ENABLED, "true");
        assertThat(defaults).containsEntry(AiConfigCatalog.TOOLS_SYSTEM_ENABLED, "true");
        assertThat(defaults).containsEntry(AiConfigCatalog.TOOLS_AUDIT_ENABLED, "true");
        assertThat(defaults).containsEntry(AiConfigCatalog.TOOLS_PROPOSAL_ENABLED, "true");
        assertThat(defaults).containsEntry(AiConfigCatalog.KNOWLEDGE_ENABLED, "true");

        // 与 AiChatService / BoundedToolCallback 的既有常量逐项对应
        assertThat(defaults).containsEntry(AiConfigCatalog.BUDGET_HISTORY_LIMIT, "20");          // HISTORY_LIMIT=20
        assertThat(defaults).containsEntry(AiConfigCatalog.BUDGET_MAX_ROUNDS, "4");              // MAX_TOOL_ROUNDS=4
        assertThat(defaults).containsEntry(AiConfigCatalog.BUDGET_MAX_CALLS_PER_ROUND, "12");    // =12
        assertThat(defaults).containsEntry(AiConfigCatalog.BUDGET_SOFT_TIMEOUT_MS, "60000");     // SOFT_TIMEOUT_MS
        assertThat(defaults).containsEntry(AiConfigCatalog.BUDGET_TOOL_RESULT_BYTES, "16384");   // 16KB
        assertThat(defaults).containsEntry(AiConfigCatalog.BUDGET_TOOL_TIMEOUT_MS, "10000");     // TOOL_TIMEOUT_MS

        // 三项"由 starter / 首次导入决定"的配置没有硬编码默认值，用 null 表示"未设置"
        assertThat(defaults).as("未设置默认值的项不得凭空造默认值")
                .doesNotContainKeys(AiConfigCatalog.MODEL_TIMEOUT,
                        AiConfigCatalog.MODEL_MAX_RETRIES,
                        AiConfigCatalog.PROMPT_ACTIVE_VERSION);
        assertThat(defaults).hasSize(17);
    }

    @Test
    @DisplayName("危险配置清单与 REQ §6.2 的「危险」列一致")
    void dangerousFlagShouldMatchRequirementTable() {
        List<String> dangerous = catalog.all().stream()
                .filter(AiConfigDefinition::dangerous)
                .map(AiConfigDefinition::key)
                .collect(Collectors.toList());

        assertThat(dangerous).containsExactlyInAnyOrder(
                AiConfigCatalog.MODEL_BASE_URL,
                AiConfigCatalog.MODEL_NAME,
                AiConfigCatalog.MODEL_TEMPERATURE,
                AiConfigCatalog.MODEL_MAX_TOKENS,
                AiConfigCatalog.MODEL_TIMEOUT,
                AiConfigCatalog.MODEL_API_KEY_REF,
                AiConfigCatalog.TOOLS_ORDER_ENABLED,
                AiConfigCatalog.TOOLS_PROPOSAL_ENABLED,
                AiConfigCatalog.PROMPT_ACTIVE_VERSION);
    }

    @Test
    @DisplayName("每个配置项都有分类与影响面说明（REQ §7 可维护性）")
    void everyDefinitionShouldBeSelfDescribing() {
        for (AiConfigDefinition def : catalog.all()) {
            assertThat(def.category()).as(def.key() + " 必须有分类").isNotNull();
            assertThat(def.description()).as(def.key() + " 必须有影响面说明").isNotBlank();
        }
    }

    // ==================================================================
    // 校验（TEST-CFG-01）
    // ==================================================================

    @Test
    @DisplayName("未知键被拒绝并给出可读错误（AC-CFG-07）")
    void unknownKeyShouldBeRejected() {
        assertThatThrownBy(() -> catalog.require("not.exists.key"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未知的配置项")
                .hasMessageContaining("not.exists.key");
        assertThatThrownBy(() -> catalog.validate("not.exists.key", "1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未知的配置项");
    }

    @Test
    @DisplayName("INT：非数字被拒绝，越界被拒绝，边界值可用")
    void intValidation() {
        String key = AiConfigCatalog.BUDGET_MAX_ROUNDS;

        assertThat(catalog.validate(key, " 6 ")).isEqualTo("6");
        assertThat(catalog.validate(key, "1")).isEqualTo("1");
        assertThat(catalog.validate(key, "8")).isEqualTo("8");

        assertThatThrownBy(() -> catalog.validate(key, "abc"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining(key).hasMessageContaining("不是合法整数");
        assertThatThrownBy(() -> catalog.validate(key, "0"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining(key).hasMessageContaining("超出允许范围").hasMessageContaining("[1, 8]");
        assertThatThrownBy(() -> catalog.validate(key, "9"))
                .isInstanceOf(BizException.class).hasMessageContaining("超出允许范围");
        assertThatThrownBy(() -> catalog.validate(key, "2.5"))
                .isInstanceOf(BizException.class).hasMessageContaining("不是合法整数");
    }

    @Test
    @DisplayName("DECIMAL：允许小数、拒绝越界、归一化去掉尾随零")
    void decimalValidation() {
        String key = AiConfigCatalog.MODEL_TEMPERATURE;

        assertThat(catalog.validate(key, "0.40")).isEqualTo("0.4");
        assertThat(catalog.validate(key, "0")).isEqualTo("0");
        assertThat(catalog.validate(key, "2")).isEqualTo("2");

        assertThatThrownBy(() -> catalog.validate(key, "2.1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining(key).hasMessageContaining("[0, 2]");
        assertThatThrownBy(() -> catalog.validate(key, "-0.1"))
                .isInstanceOf(BizException.class).hasMessageContaining("超出允许范围");
        assertThatThrownBy(() -> catalog.validate(key, "hot"))
                .isInstanceOf(BizException.class).hasMessageContaining("不是合法数值");
    }

    @Test
    @DisplayName("BOOLEAN：接受 true/false/1/0（大小写不敏感），其余拒绝")
    void booleanValidation() {
        String key = AiConfigCatalog.TOOLS_PROPOSAL_ENABLED;

        assertThat(catalog.validate(key, "TRUE")).isEqualTo("true");
        assertThat(catalog.validate(key, "1")).isEqualTo("true");
        assertThat(catalog.validate(key, "False")).isEqualTo("false");
        assertThat(catalog.validate(key, "0")).isEqualTo("false");

        assertThatThrownBy(() -> catalog.validate(key, "yes"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining(key).hasMessageContaining("不是合法布尔值");
    }

    @Test
    @DisplayName("ENUM：命中可选值（大小写不敏感）归一化为声明拼写，未命中列出可选值")
    void enumValidationUsesSyntheticDefinition() {
        AiConfigDefinition def = new AiConfigDefinition("test.gate-mode", AiConfigType.ENUM, "DETERMINISTIC",
                null, null, List.of("DETERMINISTIC", "LIVE"), AiConfigCategory.SWITCH, false, 0, "测试用枚举项");

        assertThat(AiConfigCatalog.validate(def, "deterministic")).isEqualTo("DETERMINISTIC");
        assertThat(AiConfigCatalog.validate(def, "LIVE")).isEqualTo("LIVE");
        assertThatThrownBy(() -> AiConfigCatalog.validate(def, "GRAY"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不在允许取值内")
                .hasMessageContaining("DETERMINISTIC");
    }

    @Test
    @DisplayName("STRING：拒绝空白与超长；null 表示恢复默认而不是非法值")
    void stringValidation() {
        String key = AiConfigCatalog.MODEL_NAME;

        assertThat(catalog.validate(key, "  deepseek-reasoner ")).isEqualTo("deepseek-reasoner");
        assertThat(catalog.validate(key, null)).as("null 的语义是恢复默认").isNull();

        assertThatThrownBy(() -> catalog.validate(key, "   "))
                .isInstanceOf(BizException.class).hasMessageContaining("不能为空");
        assertThatThrownBy(() -> catalog.validate(key, "x".repeat(129)))
                .isInstanceOf(BizException.class).hasMessageContaining("长度").hasMessageContaining("128");
    }

    @Test
    @DisplayName("定义自身的约束：ENUM 必须声明可选值，键不能为空")
    void definitionInvariants() {
        assertThatThrownBy(() -> new AiConfigDefinition("", AiConfigType.STRING, "x", null, null,
                List.of(), AiConfigCategory.MODEL, false, 0, "d"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiConfigDefinition("k", AiConfigType.ENUM, "x", null, null,
                List.of(), AiConfigCategory.MODEL, false, 0, "d"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ENUM");
    }

    @Test
    @DisplayName("范围边界以数值比较，不受字符串字典序影响（如 100 < 20 的陷阱）")
    void rangeComparisonIsNumeric() {
        AiConfigDefinition def = new AiConfigDefinition("test.bytes", AiConfigType.INT, "16384",
                new BigDecimal("4096"), new BigDecimal("65536"), List.of(),
                AiConfigCategory.BUDGET, false, 0, "测试用范围项");

        assertThat(AiConfigCatalog.validate(def, "4096")).isEqualTo("4096");
        assertThat(AiConfigCatalog.validate(def, "65536")).isEqualTo("65536");
        assertThatThrownBy(() -> AiConfigCatalog.validate(def, "4095"))
                .isInstanceOf(BizException.class).hasMessageContaining("超出允许范围");
    }
}
