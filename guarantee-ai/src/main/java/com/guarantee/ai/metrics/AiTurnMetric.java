package com.guarantee.ai.metrics;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单轮指标（{@code ai_turn_metric}）——**一次问答一行**，与结构化日志 {@code AI_TURN_COST} 同源。
 *
 * <p>为什么单独建表（REQ-MCP-09 / Q-MCP-06）：轮次、调用数、token、耗时、触顶此前只写日志，
 * 而"上周平均轮次是多少""哪类问题最常触顶""换模型后成本变化多少"都答不上来。
 * 另外 {@code ai_message.token_count} 是**字数估算**，拿它当用量会得出错误结论 ——
 * 本表的 {@code input_tokens}/{@code output_tokens} 才是模型真实 usage。</p>
 *
 * <p><b>只追加</b>：本表不带逻辑删除三列、不进 {@code LogicalDeleteTables.MANAGED}
 * （与 {@code ai_knowledge_import_log} 同策略）—— 指标是流水，不是可编辑业务对象。</p>
 *
 * <p><b>不记录正文与敏感字段</b>（红线 §2.3-5）：只有可枚举维度（模型名、来源、触顶原因）
 * 与数值，没有用户输入、没有参数值。</p>
 *
 * <p>字段与 {@code V9__ai_observability.sql} 的列一一对应：
 * conversation_id / message_id / user_id / model / prompt_version / rounds / tool_calls /
 * tool_cost_ms / total_cost_ms / input_tokens / output_tokens / capped / cap_reason /
 * source / outcome / trace_id / created_at。</p>
 */
@Data
public class AiTurnMetric {

    /** 来源：平台页面/助手的正常问答。默认值，写库时也是默认。 */
    public static final String SOURCE_CHAT = "CHAT";
    /** 来源：外部 Agent 经业务 MCP 调用（T5-03）。 */
    public static final String SOURCE_MCP = "MCP";
    /** 来源：评测运行器（T5-02）。 */
    public static final String SOURCE_EVAL = "EVAL";

    /** 作答结果：正常完成。 */
    public static final String OUTCOME_SUCCESS = "SUCCESS";
    /** 作答结果：失败（模型/工具/框架异常，仍落一行，便于算失败率）。 */
    public static final String OUTCOME_ERROR = "ERROR";
    /** 作答结果：因预算护栏触顶而提前收尾。 */
    public static final String OUTCOME_CAPPED = "CAPPED";

    /** 触顶原因：软超时。 */
    public static final String CAP_SOFT_TIMEOUT = "SOFT_TIMEOUT";
    /** 触顶原因：轮次上限。 */
    public static final String CAP_MAX_ROUNDS = "MAX_ROUNDS";
    /** 触顶原因：单轮调用数上限。 */
    public static final String CAP_MAX_CALLS_PER_ROUND = "MAX_CALLS_PER_ROUND";
    /** 触顶原因：框架/流式层强限。 */
    public static final String CAP_FRAMEWORK_LIMIT = "FRAMEWORK_LIMIT";
    /** 触顶但未给出原因时的兜底（宁可写 UNKNOWN，也不留空导致"触顶率算不出来"）。 */
    public static final String CAP_UNKNOWN = "UNKNOWN";

    private Long id;
    private Long conversationId;
    private Long messageId;
    private Long userId;

    /** 模型名（来自 {@code ai_conversation.model} 同源配置）。 */
    private String model;
    /** 提示词版本（第四阶段提供；无版本机制时为文件名或 null）。 */
    private String promptVersion;

    private Integer rounds;
    private Integer toolCalls;
    private Long toolCostMs;
    private Long totalCostMs;
    /** 模型真实 usage（不是字数估算）。 */
    private Integer inputTokens;
    private Integer outputTokens;

    /** 是否触顶：0 否 / 1 是（与既有 TINYINT 标志列口径一致）。 */
    private Integer capped;
    /** 触顶原因，取值见 {@code CAP_*} 常量；未触顶时为 null。 */
    private String capReason;

    /** CHAT / MCP / EVAL，取值见 {@code SOURCE_*} 常量。 */
    private String source;
    /** SUCCESS / ERROR / CAPPED，取值见 {@code OUTCOME_*} 常量。 */
    private String outcome;

    /** 与审计、工具调用、成本日志串联的 traceId。 */
    private String traceId;
    private LocalDateTime createdAt;
}
