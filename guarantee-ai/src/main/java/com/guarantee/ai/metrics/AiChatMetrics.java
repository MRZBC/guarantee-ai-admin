package com.guarantee.ai.metrics;

import com.guarantee.common.security.AuditSourceContext;
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

    // ------------------------------------------------------------------
    // ai.proposals 的标签值（AC-MCP-07 / SYS-NF-08）
    //
    // 这些常量存在的原因（T6-06 修的坑）：`ai.proposals` 曾经**没有生产调用点**——
    // 定义了 meter 与 `proposal(...)`，但没有任何地方调用，于是 /actuator/prometheus 上
    // 连 HELP/TYPE 都没有（Micrometer 首次自增才注册）。状态与来源做成常量，
    // 就是为了让"打点"这件事在 ProposalService 里显式可见、可被单测逐个状态断言。
    //
    // 状态取值与 ai_operation_proposal.status 的**终态**同值域，只有一个刻意改名：
    //   DB 写 EXECUTED，指标写 CONFIRMED —— 指标的口径是"用户确认并执行成功"（SYS-NF-08 的确认率），
    //   而 EXECUTED/PARTIAL 在指标里都算 CONFIRMED（部分成功也是"确认后执行过"）。
    // ------------------------------------------------------------------

    /** 提案创建（写工具生成，落库成功）。 */
    public static final String PROPOSAL_CREATED = "CREATED";
    /** 提案被确认并执行成功（对应 DB 的 EXECUTED / PARTIAL）。 */
    public static final String PROPOSAL_CONFIRMED = "CONFIRMED";
    /** 提案被用户拒绝。 */
    public static final String PROPOSAL_REJECTED = "REJECTED";
    /** 确认时校验不过（权限已变更 / 目标指纹不一致）→ 失效，未执行。 */
    public static final String PROPOSAL_INVALIDATED = "INVALIDATED";
    /** 超时未确认 → 过期。 */
    public static final String PROPOSAL_EXPIRED = "EXPIRED";
    /** 确认后执行失败（业务异常，已回滚）。 */
    public static final String PROPOSAL_FAILED = "FAILED";

    /**
     * 提案事件的来源标签（枚举）。
     *
     * <p>取值与审计来源（{@code AuditSourceContext}）保持同一套词表，避免"审计说 AI、指标说别的"：</p>
     * <ul>
     *   <li>{@link #PROPOSAL_SOURCE_AI}：助手写工具发起（创建）；</li>
     *   <li>{@link #PROPOSAL_SOURCE_WEB}：页面渠道发起（确认 / 拒绝 / 校验失效）；</li>
     *   <li>{@link #PROPOSAL_SOURCE_SYSTEM}：系统定时清理（{@code expireOverdue}）导致的过期。</li>
     * </ul>
     */
    public static final String PROPOSAL_SOURCE_AI = AuditSourceContext.AI;
    public static final String PROPOSAL_SOURCE_WEB = AuditSourceContext.WEB;
    public static final String PROPOSAL_SOURCE_SYSTEM = "SYSTEM";

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

    /**
     * 提案状态计数（{@link #PROPOSAL_CREATED} / {@link #PROPOSAL_CONFIRMED} / …）。
     *
     * <p>调用点必须**在真实的状态流转处**（生成落库后 / 确认执行成功 / 拒绝 / 失效 / 过期），
     * 而不是在查询或页面上补记：指标与 {@code ai_operation_proposal} 的状态必须同源，
     * 否则"确认率"会被算成两次。</p>
     *
     * @param status {@link #PROPOSAL_CREATED} 等枚举值
     * @param source {@link #PROPOSAL_SOURCE_AI} / {@link #PROPOSAL_SOURCE_WEB} / {@link #PROPOSAL_SOURCE_SYSTEM}
     */
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
