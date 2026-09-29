package com.guarantee.ai.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * AI 观测指标（REQ-MCP-08 / AC-MCP-07/08）。
 *
 * <p><b>指标清单（前缀统一 {@code ai.}）</b>：</p>
 * <ul>
 *   <li>{@code ai.chat.requests} Counter{@code outcome,model} —— 每次问答；</li>
 *   <li>{@code ai.chat.duration} Timer{@code outcome} —— 端到端耗时；</li>
 *   <li>{@code ai.chat.rounds} DistributionSummary{@code capped} —— 轮次分布；</li>
 *   <li>{@code ai.tool.calls} Counter{@code tool,status,source}；</li>
 *   <li>{@code ai.tool.duration} Timer{@code tool}；</li>
 *   <li>{@code ai.tokens} Counter{@code direction,model} —— 真实 usage（不是字数估算）；</li>
 *   <li>{@code ai.proposals} Counter{@code status,source} —— 兑现 SYS-NF-08；</li>
 *   <li>{@code ai.prompt.publish} Counter{@code result} —— 第四阶段发布门禁观测面；</li>
 *   <li>{@code ai.knowledge.retrieval} Counter{@code domain,hit} +
 *       {@code ai.knowledge.retrieval.duration} Timer{@code domain,hit} —— 第三阶段知识检索
 *       （拆两个名字：Micrometer 不允许同名 meter 有两种类型）。</li>
 * </ul>
 *
 * <p><b>基数纪律（AC-MCP-08）</b>：标签只允许上表中的可枚举维度。
 * 本类<b>没有</b>接受 conversationId / userId / 问题文本 / 参数值的入口；
 * 标签值还会过一道 {@link #sanitize}（去空白、截断、非白名单字符归一），
 * 防止"模型名/工具名里混进 id"这类意外把时间序列打爆。</p>
 *
 * <p><b>可关</b>：{@code guarantee.ai.observability.metrics-enabled=false} 时全部方法变成
 * 空操作（故障应急），而不是去改调用方代码。</p>
 */
@Component
public class AiChatMetrics {

    public static final String CHAT_REQUESTS = "ai.chat.requests";
    public static final String CHAT_DURATION = "ai.chat.duration";
    public static final String CHAT_ROUNDS = "ai.chat.rounds";
    public static final String TOOL_CALLS = "ai.tool.calls";
    public static final String TOOL_DURATION = "ai.tool.duration";
    public static final String TOKENS = "ai.tokens";
    public static final String PROPOSALS = "ai.proposals";
    public static final String PROMPT_PUBLISH = "ai.prompt.publish";
    public static final String KNOWLEDGE_RETRIEVAL = "ai.knowledge.retrieval";
    /**
     * 知识检索耗时。
     *
     * <p>单独一个名字而不是与计数共用：Micrometer **不允许**同名 meter 有两种类型
     * （实测 "There is already a registered meter of a different type (CumulativeCounter vs. Timer)"）。
     * REQ §5.3.1 写的是 "Counter/Timer"，落地时按 Micrometer 惯例拆成
     * {@code ai.knowledge.retrieval}（计数）+ {@code ai.knowledge.retrieval.duration}（耗时）。</p>
     */
    public static final String KNOWLEDGE_RETRIEVAL_DURATION = "ai.knowledge.retrieval.duration";

    /** outcome 标签取值（与 {@link AiTurnMetric#OUTCOME_*} 同值域，避免两处口径漂移）。 */
    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_ERROR = "error";
    public static final String OUTCOME_CAPPED = "capped";

    public static final String DIRECTION_INPUT = "input";
    public static final String DIRECTION_OUTPUT = "output";

    /** 标签值兜底（拿不到时用它，避免出现空标签值）。 */
    public static final String UNKNOWN = "unknown";

    private static final int MAX_TAG_LENGTH = 48;

    private final MeterRegistry registry;
    private final boolean enabled;

    public AiChatMetrics(MeterRegistry registry,
                         @Value("${guarantee.ai.observability.metrics-enabled:true}") boolean enabled) {
        this.registry = registry;
        this.enabled = enabled;
    }

    // ==================================================================
    // 问答
    // ==================================================================

    /** 每次问答记一次；{@code outcome} 取 success/error/capped。 */
    public void chatRequest(String outcome, String model) {
        if (!enabled) {
            return;
        }
        Counter.builder(CHAT_REQUESTS)
                .tag("outcome", sanitize(outcome))
                .tag("model", sanitize(model))
                .register(registry)
                .increment();
    }

    /** 端到端耗时（与 {@code totalCostMs} 同源）。 */
    public void chatDuration(String outcome, long millis) {
        if (!enabled) {
            return;
        }
        timer(CHAT_DURATION, "outcome", sanitize(outcome)).record(Math.max(0L, millis), TimeUnit.MILLISECONDS);
    }

    /** 轮次分布（回答"平均几轮"）。 */
    public void chatRounds(int rounds, boolean capped) {
        if (!enabled) {
            return;
        }
        DistributionSummary.builder(CHAT_ROUNDS)
                .tag("capped", Boolean.toString(capped))
                .register(registry)
                .record(Math.max(0, rounds));
    }

    /** 真实 token 用量（不是字数估算）。 */
    public void tokens(int inputTokens, int outputTokens, String model) {
        if (!enabled) {
            return;
        }
        String modelTag = sanitize(model);
        if (inputTokens > 0) {
            Counter.builder(TOKENS).tag("direction", DIRECTION_INPUT).tag("model", modelTag)
                    .register(registry).increment(inputTokens);
        }
        if (outputTokens > 0) {
            Counter.builder(TOKENS).tag("direction", DIRECTION_OUTPUT).tag("model", modelTag)
                    .register(registry).increment(outputTokens);
        }
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /**
     * 每次工具调用记一次（计数 + 耗时）。
     *
     * @param tool   工具名（固定枚举：既有 @Tool 名字）
     * @param status SUCCESS / FAILED
     * @param source CHAT / MCP / EVAL（来源，AC-MCP-05 的观测面）
     */
    public void toolCall(String tool, String status, String source, long millis) {
        if (!enabled) {
            return;
        }
        String toolTag = sanitize(tool);
        Counter.builder(TOOL_CALLS)
                .tag("tool", toolTag)
                .tag("status", sanitize(status))
                .tag("source", sanitize(source))
                .register(registry)
                .increment();
        timer(TOOL_DURATION, "tool", toolTag).record(Math.max(0L, millis), TimeUnit.MILLISECONDS);
    }

    // ==================================================================
    // 提案 / 提示词发布 / 知识检索（跨阶段观测面）
    // ==================================================================

    /** 提案状态计数（CREATED/CONFIRMED/REJECTED/EXPIRED/FAILED…）。 */
    public void proposal(String status, String source) {
        if (!enabled) {
            return;
        }
        Counter.builder(PROPOSALS)
                .tag("status", sanitize(status))
                .tag("source", sanitize(source))
                .register(registry)
                .increment();
    }

    /** 提示词发布结果（ok / gate_failed）。 */
    public void promptPublish(String result) {
        if (!enabled) {
            return;
        }
        Counter.builder(PROMPT_PUBLISH).tag("result", sanitize(result)).register(registry).increment();
    }

    /** 知识检索（domain + 是否命中 + 耗时）。 */
    public void knowledgeRetrieval(String domain, boolean hit, long millis) {
        if (!enabled) {
            return;
        }
        String domainTag = sanitize(domain);
        Counter.builder(KNOWLEDGE_RETRIEVAL)
                .tag("domain", domainTag)
                .tag("hit", Boolean.toString(hit))
                .register(registry)
                .increment();
        timer(KNOWLEDGE_RETRIEVAL_DURATION, "domain", domainTag, "hit", Boolean.toString(hit))
                .record(Math.max(0L, millis), TimeUnit.MILLISECONDS);
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private Timer timer(String name, String... tags) {
        return Timer.builder(name).tags(tags).register(registry);
    }

    /**
     * 标签值归一：去空白、截断、非白名单字符替换。
     *
     * <p>这一步是基数控制的**最后一道闸**：即便调用方误把 id 塞进 model/tool/domain，
     * 也会被截断与归一，不会出现成千上万条时间序列。</p>
     */
    static String sanitize(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return UNKNOWN;
        }
        String normalized = trimmed.replaceAll("[^A-Za-z0-9_.:\\-]", "_");
        if (normalized.length() > MAX_TAG_LENGTH) {
            normalized = normalized.substring(0, MAX_TAG_LENGTH);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }
}
