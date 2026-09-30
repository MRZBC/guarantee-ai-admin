package com.guarantee.ai.config;

import com.guarantee.common.exception.BizException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * AI 配置项目录（REQ-CFG-01 / docs/REQ-第四阶段-AI配置与确认审计.md §6.2）。
 *
 * <p><b>这是配置项的唯一真源</b>：键名、类型、默认值、取值范围、是否危险、影响面说明
 * 全部只在这里定义一次。数据库 {@code ai_config_item} 只承载"当前值"，行上的元数据列
 * 是 {@code update()} 时由本目录生成的投影。</p>
 *
 * <p><b>默认值必须与改造前的行为逐项一致</b>（AC-CFG-08）：{@code temperature=0.2}、
 * {@code history-limit=20}、{@code max-rounds=4}、{@code max-calls-per-round=12}、
 * {@code soft-timeout-ms=60000}、{@code tool-result-bytes=16384}、
 * {@code tool-timeout-ms=10000}、{@code base-url=https://api.deepseek.com}、
 * {@code model.name=deepseek-chat}、{@code api-key-ref=DEEPSEEK_API_KEY}。</p>
 *
 * <p><b>工具组与工具类的对应关系</b>（T4-01 按此接线，避免"关了 analysis 却关掉了订单汇总"）：</p>
 * <ul>
 *   <li>{@code tools.order.enabled} → {@code queryOrderSummary}；</li>
 *   <li>{@code tools.analysis.enabled} → {@code queryOrderDistribution}、{@code queryOrderTrend}
 *       （对应 guarantee-analysis 的 OrderAnalysisService）；</li>
 *   <li>{@code tools.system.enabled} → 机构/部门/用户/角色/险种查询，以及
 *       {@code queryMyToolCalls}、{@code queryMyProposals}（后两者同属 {@code ai:system:query} 口径）；</li>
 *   <li>{@code tools.audit.enabled} → {@code queryOperationAudit}；</li>
 *   <li>{@code tools.proposal.enabled} → 全部 {@code propose*}（写能力总开关）。</li>
 * </ul>
 */
@Component
public class AiConfigCatalog {

    // ---------------- 模型 ----------------
    public static final String MODEL_BASE_URL = "model.base-url";
    public static final String MODEL_NAME = "model.name";
    public static final String MODEL_TEMPERATURE = "model.temperature";
    public static final String MODEL_MAX_TOKENS = "model.max-tokens";
    public static final String MODEL_TIMEOUT = "model.timeout";
    public static final String MODEL_MAX_RETRIES = "model.max-retries";
    public static final String MODEL_API_KEY_REF = "model.api-key-ref";

    // ---------------- 能力开关 ----------------
    public static final String TOOLS_ORDER_ENABLED = "tools.order.enabled";
    public static final String TOOLS_ANALYSIS_ENABLED = "tools.analysis.enabled";
    public static final String TOOLS_SYSTEM_ENABLED = "tools.system.enabled";
    public static final String TOOLS_AUDIT_ENABLED = "tools.audit.enabled";
    public static final String TOOLS_PROPOSAL_ENABLED = "tools.proposal.enabled";
    public static final String KNOWLEDGE_ENABLED = "knowledge.enabled";

    // ---------------- 预算 ----------------
    public static final String BUDGET_HISTORY_LIMIT = "budget.history-limit";
    public static final String BUDGET_MAX_ROUNDS = "budget.max-rounds";
    public static final String BUDGET_MAX_CALLS_PER_ROUND = "budget.max-calls-per-round";
    public static final String BUDGET_SOFT_TIMEOUT_MS = "budget.soft-timeout-ms";
    public static final String BUDGET_TOOL_RESULT_BYTES = "budget.tool-result-bytes";
    public static final String BUDGET_TOOL_TIMEOUT_MS = "budget.tool-timeout-ms";

    // ---------------- 提示词 ----------------
    public static final String PROMPT_ACTIVE_VERSION = "prompt.active-version";

    private final Map<String, AiConfigDefinition> definitions;

    public AiConfigCatalog() {
        Map<String, AiConfigDefinition> map = new LinkedHashMap<>();

        put(map, def(MODEL_BASE_URL, AiConfigType.STRING, "https://api.deepseek.com",
                null, null, AiConfigCategory.MODEL, true, 512,
                "OpenAI 兼容服务的接入地址。改错后所有模型调用立即失败（全站不可用），"
                        + "只应指向可信的内网/官方地址。"));
        put(map, def(MODEL_NAME, AiConfigType.STRING, "deepseek-chat",
                null, null, AiConfigCategory.MODEL, true, 128,
                "调用的模型名，会落库到 ai_conversation.model。普通问答与写提案共用同一模型。"));
        put(map, def(MODEL_TEMPERATURE, AiConfigType.DECIMAL, "0.2",
                new BigDecimal("0"), new BigDecimal("2"), AiConfigCategory.MODEL, true, 0,
                "采样温度：越大越随机。口径类问答依赖稳定复述，调高会放大编造风险。"
                        + "改造前是 application.yml 里的字面量 0.2。"));
        put(map, def(MODEL_MAX_TOKENS, AiConfigType.INT, "2048",
                new BigDecimal("256"), new BigDecimal("8192"), AiConfigCategory.MODEL, true, 0,
                "单次回复的 token 上限。调大增加成本与首字延迟，调小可能截断结论。"));
        put(map, def(MODEL_TIMEOUT, AiConfigType.INT, null,
                new BigDecimal("1000"), new BigDecimal("300000"), AiConfigCategory.MODEL, true, 0,
                "模型调用超时（毫秒）。未设置时沿用 Spring AI starter 默认"
                        + "（改造前未显式配置），显式设置会覆盖 starter 行为。"));
        put(map, def(MODEL_MAX_RETRIES, AiConfigType.INT, null,
                new BigDecimal("0"), new BigDecimal("5"), AiConfigCategory.MODEL, false, 0,
                "模型调用失败后的重试次数。未设置时沿用 starter 默认；调大只影响失败路径的耗时。"));
        put(map, def(MODEL_API_KEY_REF, AiConfigType.STRING, "DEEPSEEK_API_KEY",
                null, null, AiConfigCategory.MODEL, true, 128,
                "API Key 的**环境变量引用名**。密钥值永不入库、不回显、不进日志；"
                        + "库里只有这个名字与「是否已配置」的结果。"));

        put(map, def(TOOLS_ORDER_ENABLED, AiConfigType.BOOLEAN, "true",
                null, null, AiConfigCategory.SWITCH, true, 0,
                "订单工具组（queryOrderSummary）。关闭后对所有人都不注册该工具，"
                        + "订单类问题助手只能如实说明能力已关闭。"));
        put(map, def(TOOLS_ANALYSIS_ENABLED, AiConfigType.BOOLEAN, "true",
                null, null, AiConfigCategory.SWITCH, false, 0,
                "分析工具组（queryOrderDistribution、queryOrderTrend）。关闭后区域/机构/险种分布"
                        + "与趋势类问题不再有取数能力。"));
        put(map, def(TOOLS_SYSTEM_ENABLED, AiConfigType.BOOLEAN, "true",
                null, null, AiConfigCategory.SWITCH, false, 0,
                "系统域查询工具组：机构/部门/用户/角色/险种查询，以及 queryMyToolCalls、"
                        + "queryMyProposals 两个自查工具。关闭后助手不再注册这些工具（页面功能不受影响）。"));
        put(map, def(TOOLS_AUDIT_ENABLED, AiConfigType.BOOLEAN, "true",
                null, null, AiConfigCategory.SWITCH, false, 0,
                "审计查询工具（queryOperationAudit）。关闭后助手的审计问答失效，"
                        + "审计页与审计数据不受影响。"));
        put(map, def(TOOLS_PROPOSAL_ENABLED, AiConfigType.BOOLEAN, "true",
                null, null, AiConfigCategory.SWITCH, true, 0,
                "写能力总开关。关闭后任何账号（含 ADMIN）都不再注册 propose* 工具；"
                        + "与权限码 ai:system:write 是「与」关系（开关只能更严，不能更松）。"));
        put(map, def(KNOWLEDGE_ENABLED, AiConfigType.BOOLEAN, "true",
                null, null, AiConfigCategory.SWITCH, false, 0,
                "第三阶段知识层开关。关闭后不注册 queryBusinessKnowledge，"
                        + "数字类问答不受影响，但知识/口径类问题会如实说明知识层不可用。"));

        put(map, def(BUDGET_HISTORY_LIMIT, AiConfigType.INT, "20",
                new BigDecimal("5"), new BigDecimal("50"), AiConfigCategory.BUDGET, false, 0,
                "带入模型的历史消息条数（改造前 HISTORY_LIMIT=20）。调大增加 token 成本，"
                        + "调小可能让模型丢失上文。"));
        put(map, def(BUDGET_MAX_ROUNDS, AiConfigType.INT, "4",
                new BigDecimal("1"), new BigDecimal("8"), AiConfigCategory.BUDGET, false, 0,
                "单轮对话最多工具轮次（改造前 MAX_TOOL_ROUNDS=4）。用尽后进入收口轮，"
                        + "依据已取到的数据作答。"));
        put(map, def(BUDGET_MAX_CALLS_PER_ROUND, AiConfigType.INT, "12",
                new BigDecimal("1"), new BigDecimal("40"), AiConfigCategory.BUDGET, false, 0,
                "单轮工具调用上限（改造前 MAX_TOOL_CALLS_PER_ROUND=12）。超出的调用不执行，"
                        + "只回灌一条可读说明。"));
        put(map, def(BUDGET_SOFT_TIMEOUT_MS, AiConfigType.INT, "60000",
                new BigDecimal("10000"), new BigDecimal("300000"), AiConfigCategory.BUDGET, false, 0,
                "整轮取数软超时（毫秒，改造前 SOFT_TIMEOUT_MS=60000）。触顶后停止取数并进入收口轮。"));
        put(map, def(BUDGET_TOOL_RESULT_BYTES, AiConfigType.INT, "16384",
                new BigDecimal("4096"), new BigDecimal("65536"), AiConfigCategory.BUDGET, false, 0,
                "单个工具返回结果的字节上限（改造前 16KB）。截断会显式标记，模型据此说明结果不完整。"));
        put(map, def(BUDGET_TOOL_TIMEOUT_MS, AiConfigType.INT, "10000",
                new BigDecimal("1000"), new BigDecimal("60000"), AiConfigCategory.BUDGET, false, 0,
                "单个工具执行的超时（毫秒，改造前 TOOL_TIMEOUT_MS=10000）。"
                        + "超时该次调用判为可读失败，不拖垮整轮。"));

        put(map, def(PROMPT_ACTIVE_VERSION, AiConfigType.INT, null,
                new BigDecimal("1"), null, AiConfigCategory.PROMPT, true, 0,
                "当前生效的提示词版本号（指向 ai_prompt_version.version_no）。"
                        + "未设置或该版本不存在时，回落到打包在 jar 内的 classpath 提示词，保证冷启动可用。"));

        this.definitions = Collections.unmodifiableMap(map);
    }

    /**
     * 测试用构造器：用自定义定义构造目录（生产走无参构造器）。
     *
     * <p>存在的意义：验证"未接线项（{@code wired=false}）不允许写入"这条护栏——
     * 本期接线后真实目录里已没有未接线项，不可能靠真实清单构造该场景。</p>
     */
    AiConfigCatalog(List<AiConfigDefinition> custom) {
        Map<String, AiConfigDefinition> map = new LinkedHashMap<>();
        custom.forEach(def -> put(map, def));
        this.definitions = Collections.unmodifiableMap(map);
    }

    /** 全部配置项定义（保持声明顺序，页面按此分组展示）。 */
    public List<AiConfigDefinition> all() {
        return List.copyOf(definitions.values());
    }

    /** 按 key 查找。 */
    public Optional<AiConfigDefinition> find(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(definitions.get(key));
    }

    /** 按 key 取定义；未知 key 给出可读错误（REQ-CFG-07 的"未知键被拒绝"）。 */
    public AiConfigDefinition require(String key) {
        AiConfigDefinition def = key == null ? null : definitions.get(key);
        if (def == null) {
            throw BizException.badRequest("未知的配置项：" + key);
        }
        return def;
    }

    /** 该键是否已被运行期接线；未知 key 返回 false（宁严勿松）。 */
    public boolean isWired(String key) {
        AiConfigDefinition def = key == null ? null : definitions.get(key);
        return def != null && def.wired();
    }

    /** 所有有默认值的配置项：key → 默认值。未设置默认值的项不在其中。 */
    public Map<String, String> defaults() {
        Map<String, String> result = new LinkedHashMap<>();
        definitions.forEach((key, def) -> {
            if (def.hasDefaultValue()) {
                result.put(key, def.defaultValue());
            }
        });
        return Collections.unmodifiableMap(result);
    }

    /** 校验并归一化一个 key 的值；未知 key 抛出可读错误。 */
    public String validate(String key, String rawValue) {
        return validate(require(key), rawValue);
    }

    /**
     * 校验并归一化一个配置值（TEST-CFG-01）。
     *
     * <p>{@code rawValue == null} 原样返回 null，语义是"恢复默认/清除显式值"，不是非法值。</p>
     *
     * @return 归一化后的值（INT 去掉前导零、DECIMAL 去掉尾随零、BOOLEAN 归为 true/false、
     *         ENUM 归为声明里的拼写）；非法值抛 {@link BizException} 且消息里带键名与原因
     */
    public static String validate(AiConfigDefinition def, String rawValue) {
        if (rawValue == null) {
            return null;
        }
        String text = rawValue.trim();
        if (text.isEmpty()) {
            throw BizException.badRequest("配置项 " + def.key() + " 的值不能为空");
        }
        switch (def.type()) {
            case STRING -> {
                if (def.maxLength() > 0 && text.length() > def.maxLength()) {
                    throw BizException.badRequest("配置项 " + def.key() + " 的值长度 " + text.length()
                            + " 超出上限 " + def.maxLength() + " 个字符");
                }
                return text;
            }
            case INT -> {
                int value;
                try {
                    value = Integer.parseInt(text);
                } catch (NumberFormatException ex) {
                    throw BizException.badRequest("配置项 " + def.key() + " 的值「" + text + "」不是合法整数");
                }
                checkRange(def, BigDecimal.valueOf(value), text);
                return String.valueOf(value);
            }
            case DECIMAL -> {
                BigDecimal value;
                try {
                    value = new BigDecimal(text);
                } catch (NumberFormatException ex) {
                    throw BizException.badRequest("配置项 " + def.key() + " 的值「" + text + "」不是合法数值");
                }
                checkRange(def, value, text);
                return value.stripTrailingZeros().toPlainString();
            }
            case BOOLEAN -> {
                String lower = text.toLowerCase(Locale.ROOT);
                if ("true".equals(lower) || "1".equals(lower)) {
                    return "true";
                }
                if ("false".equals(lower) || "0".equals(lower)) {
                    return "false";
                }
                throw BizException.badRequest("配置项 " + def.key() + " 的值「" + text
                        + "」不是合法布尔值（只接受 true/false）");
            }
            case ENUM -> {
                for (String option : def.enumOptions()) {
                    if (option.equalsIgnoreCase(text)) {
                        return option;
                    }
                }
                throw BizException.badRequest("配置项 " + def.key() + " 的值「" + text
                        + "」不在允许取值内：" + String.join("、", def.enumOptions()));
            }
            default -> throw BizException.badRequest("配置项 " + def.key() + " 的类型不支持：" + def.type());
        }
    }

    private static void checkRange(AiConfigDefinition def, BigDecimal value, String rawText) {
        if (!def.rangeBounded()) {
            return;
        }
        boolean below = def.minValue() != null && value.compareTo(def.minValue()) < 0;
        boolean above = def.maxValue() != null && value.compareTo(def.maxValue()) > 0;
        if (below || above) {
            String min = def.minValue() == null ? "-∞" : plain(def.minValue());
            String max = def.maxValue() == null ? "+∞" : plain(def.maxValue());
            throw BizException.badRequest("配置项 " + def.key() + " 的值「" + rawText
                    + "」超出允许范围 [" + min + ", " + max + "]");
        }
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static void put(Map<String, AiConfigDefinition> map, AiConfigDefinition def) {
        AiConfigDefinition previous = map.put(def.key(), def);
        if (previous != null) {
            throw new IllegalStateException("配置键重复定义：" + def.key());
        }
    }

    /**
     * 声明一个**已接线**（运行期真的消费该键）的配置项。
     *
     * <p>本期 T6-02 接线 `model.max-tokens` / `model.timeout` / `model.max-retries` 之后，
     * 目录里全部 20 项都是 wired；未接线项将来若再出现，用下面的重载显式声明 {@code false}，
     * 页面会自动标注并禁用编辑，服务端也会拒绝写入。</p>
     */
    private static AiConfigDefinition def(String key, AiConfigType type, String defaultValue,
                                          BigDecimal min, BigDecimal max, AiConfigCategory category,
                                          boolean dangerous, int maxLength, String description) {
        return def(key, type, defaultValue, min, max, category, dangerous, maxLength, description, true);
    }

    /** 声明配置项，并显式指定运行期是否消费该键。 */
    private static AiConfigDefinition def(String key, AiConfigType type, String defaultValue,
                                          BigDecimal min, BigDecimal max, AiConfigCategory category,
                                          boolean dangerous, int maxLength, String description,
                                          boolean wired) {
        return new AiConfigDefinition(key, type, defaultValue, min, max, List.of(), category,
                dangerous, maxLength, description, wired);
    }

}
