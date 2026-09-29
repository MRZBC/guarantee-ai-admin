package com.guarantee.ai.metrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Micrometer 指标单元测试（TEST-MCP-01 / AC-MCP-07 / AC-MCP-08）。
 *
 * <p>验证两件事：</p>
 * <ol>
 *   <li><b>口径正确</b>：每类事件真的记到了对应的 meter，标签值与语义一致（计数、耗时、token 方向…）；</li>
 *   <li><b>基数可控</b>：所有 meter 的标签键都在白名单内，且标签值里不允许出现会话 id / 用户 id /
 *       问题文本 —— 这是 AC-MCP-08 的判定方式（"标签中不含会话 id / 用户 id / 问题文本"）。</li>
 * </ol>
 */
class AiChatMetricsTest {

    /** 允许出现在标签上的枚举维度（§5.3.1 的标签列）。 */
    private static final Set<String> ALLOWED_TAG_KEYS =
            Set.of("outcome", "model", "capped", "tool", "status", "source", "direction", "domain", "hit", "result");

    private SimpleMeterRegistry registry;
    private AiChatMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new AiChatMetrics(registry, true);
    }

    // ==================================================================
    // 口径
    // ==================================================================

    @Test
    @DisplayName("ai.chat.requests：按 outcome/model 累计")
    void chatRequestsCountsByOutcomeAndModel() {
        metrics.chatRequest(AiChatMetrics.OUTCOME_SUCCESS, "deepseek-chat");
        metrics.chatRequest(AiChatMetrics.OUTCOME_SUCCESS, "deepseek-chat");
        metrics.chatRequest(AiChatMetrics.OUTCOME_ERROR, "deepseek-chat");

        assertThat(registry.get(AiChatMetrics.CHAT_REQUESTS)
                .tag("outcome", "success").tag("model", "deepseek-chat").counter().count()).isEqualTo(2.0);
        assertThat(registry.get(AiChatMetrics.CHAT_REQUESTS)
                .tag("outcome", "error").tag("model", "deepseek-chat").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("ai.chat.duration 与 ai.chat.rounds：耗时与轮次分布")
    void chatDurationAndRounds() {
        metrics.chatDuration(AiChatMetrics.OUTCOME_SUCCESS, 1500);
        metrics.chatRounds(2, false);
        metrics.chatRounds(3, false);

        assertThat(registry.get(AiChatMetrics.CHAT_DURATION)
                .tag("outcome", "success").timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(1500.0);
        assertThat(registry.get(AiChatMetrics.CHAT_ROUNDS)
                .tag("capped", "false").summary().count()).isEqualTo(2);
        assertThat(registry.get(AiChatMetrics.CHAT_ROUNDS)
                .tag("capped", "false").summary().totalAmount()).isEqualTo(5.0);
    }

    @Test
    @DisplayName("ai.tool.calls / ai.tool.duration：工具名 + 状态 + **来源**三个标签")
    void toolCallsCarrySource() {
        metrics.toolCall("queryOrderSummary", "SUCCESS", "MCP", 120);
        metrics.toolCall("queryOrderSummary", "FAILED", "CHAT", 30);

        assertThat(registry.get(AiChatMetrics.TOOL_CALLS)
                .tag("tool", "queryordersummary").tag("status", "success").tag("source", "mcp")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get(AiChatMetrics.TOOL_CALLS)
                .tag("tool", "queryordersummary").tag("status", "failed").tag("source", "chat")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get(AiChatMetrics.TOOL_DURATION)
                .tag("tool", "queryordersummary").timer().count()).isEqualTo(2);
    }

    @Test
    @DisplayName("ai.tokens：direction=input/output 两个方向；0 值不建时间序列")
    void tokensRespectDirection() {
        metrics.tokens(1200, 800, "deepseek-chat");

        assertThat(registry.get(AiChatMetrics.TOKENS)
                .tag("direction", "input").tag("model", "deepseek-chat").counter().count()).isEqualTo(1200.0);
        assertThat(registry.get(AiChatMetrics.TOKENS)
                .tag("direction", "output").tag("model", "deepseek-chat").counter().count()).isEqualTo(800.0);

        // 全 0：不产生任何 meter（避免"每个模型都有一条 0 曲线"）
        metrics.tokens(0, 0, "deepseek-reasoner");
        assertThat(registry.find(AiChatMetrics.TOKENS)
                .tag("direction", "input").tag("model", "deepseek-reasoner").counter()).isNull();
    }

    @Test
    @DisplayName("跨阶段观测面：提案状态 / 提示词发布 / 知识检索")
    void crossStageMeters() {
        metrics.proposal("CONFIRMED", "CHAT");
        metrics.promptPublish("gate_failed");
        metrics.knowledgeRetrieval("SYSTEM", true, 12);

        assertThat(registry.get(AiChatMetrics.PROPOSALS)
                .tag("status", "confirmed").tag("source", "chat").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(AiChatMetrics.PROMPT_PUBLISH)
                .tag("result", "gate_failed").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(AiChatMetrics.KNOWLEDGE_RETRIEVAL)
                .tag("domain", "system").tag("hit", "true").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(AiChatMetrics.KNOWLEDGE_RETRIEVAL_DURATION)
                .tag("domain", "system").tag("hit", "true").timer().count()).isEqualTo(1);
    }

    // ==================================================================
    // 基数纪律（AC-MCP-08）
    // ==================================================================

    @Test
    @DisplayName("AC-MCP-08：所有标签键都在白名单内，标签值里不出现会话/用户 id 与文本")
    void tagsStayWithinAllowedDimensions() {
        metrics.chatRequest(AiChatMetrics.OUTCOME_CAPPED, "deepseek-chat");
        metrics.chatDuration(AiChatMetrics.OUTCOME_CAPPED, 60_000);
        metrics.chatRounds(4, true);
        metrics.toolCall("queryOrg", "SUCCESS", "EVAL", 15);
        metrics.tokens(10, 20, "deepseek-chat");
        metrics.proposal("REJECTED", "CHAT");
        metrics.promptPublish("ok");
        metrics.knowledgeRetrieval("ORDER", false, 3);

        assertThat(registry.getMeters()).isNotEmpty();
        for (Meter meter : registry.getMeters()) {
            for (Tag tag : meter.getId().getTags()) {
                assertThat(ALLOWED_TAG_KEYS)
                        .as("指标 %s 出现了非白名单标签键 %s", meter.getId().getName(), tag.getKey())
                        .contains(tag.getKey());
                assertThat(tag.getValue())
                        .as("标签值不得是 id 或正文：%s=%s", tag.getKey(), tag.getValue())
                        .doesNotContain("conversation")
                        .doesNotContain("42");
            }
        }
    }

    @Test
    @DisplayName("标签值归一：空值 → unknown，大小写/空白/非法字符/超长都被收敛")
    void tagValuesAreSanitized() {
        assertThat(AiChatMetrics.sanitize(null)).isEqualTo(AiChatMetrics.UNKNOWN);
        assertThat(AiChatMetrics.sanitize("   ")).isEqualTo(AiChatMetrics.UNKNOWN);
        assertThat(AiChatMetrics.sanitize("  DeepSeek-Chat  ")).isEqualTo("deepseek-chat");
        // 恶意/意外的值：换行、空格、超长 → 归一后仍是一条可枚举标签
        assertThat(AiChatMetrics.sanitize("a b\nc")).isEqualTo("a_b_c");
        assertThat(AiChatMetrics.sanitize("x".repeat(200))).hasSize(48);
        // 即便有人把 id 塞进 model，也不会产生"每个 id 一条曲线"的爆炸（被截断归一）
        assertThat(AiChatMetrics.sanitize("conversation-12345"))
                .isEqualTo("conversation-12345".substring(0, 18));
    }

    @Test
    @DisplayName("开关关闭时全部为空操作（故障应急，不改调用方代码）")
    void disabledMetricsRecordNothing() {
        AiChatMetrics disabled = new AiChatMetrics(registry, false);

        disabled.chatRequest(AiChatMetrics.OUTCOME_SUCCESS, "deepseek-chat");
        disabled.chatDuration(AiChatMetrics.OUTCOME_SUCCESS, 10);
        disabled.chatRounds(1, false);
        disabled.toolCall("queryOrg", "SUCCESS", "CHAT", 5);
        disabled.tokens(1, 1, "deepseek-chat");
        disabled.proposal("CONFIRMED", "CHAT");
        disabled.promptPublish("ok");
        disabled.knowledgeRetrieval("SYSTEM", true, 1);

        assertThat(registry.getMeters()).isEmpty();
    }
}
