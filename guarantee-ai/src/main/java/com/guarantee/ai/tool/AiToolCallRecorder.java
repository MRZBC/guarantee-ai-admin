package com.guarantee.ai.tool;

import com.guarantee.ai.entity.AiAuditLog;
import com.guarantee.ai.entity.AiToolCall;
import com.guarantee.ai.mapper.AiAuditLogMapper;
import com.guarantee.ai.mapper.AiToolCallMapper;
import com.guarantee.ai.metrics.AiChatMetrics;
import com.guarantee.common.security.SensitiveFieldMasker;
import com.guarantee.common.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Tool Call 记录器：把每次工具执行落库到 {@code ai_tool_call}，并写一条审计日志。
 *
 * <p>记录内容：tool name、arguments、result、status、duration。</p>
 *
 * <p><b>敏感字段统一脱敏</b>（SYS-A-09）：arguments / result / errorMessage 在落库与
 * 推送前都经 {@link SensitiveFieldMasker} 处理，与审计表、提案表使用同一套规则
 * （RK-12：三处各写一套正则必然出现旁路）。</p>
 */
@Component
public class AiToolCallRecorder {

    private static final Logger log = LoggerFactory.getLogger(AiToolCallRecorder.class);

    private static final int MAX_TEXT = 60_000;

    private final AiToolCallMapper toolCallMapper;
    private final AiAuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;
    private final AiChatMetrics metrics;

    public AiToolCallRecorder(AiToolCallMapper toolCallMapper, AiAuditLogMapper auditLogMapper,
                              ObjectMapper objectMapper, AiChatMetrics metrics) {
        this.toolCallMapper = toolCallMapper;
        this.auditLogMapper = auditLogMapper;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    /**
     * 本次调用的来源（CHAT / MCP / EVAL）。
     *
     * <p>缺省按 CHAT：写入口（助手）不显式写也不会把助手调用标成别的来源；
     * MCP 网关与评测运行器会显式写 {@code AiToolContextKeys.CALL_SOURCE}。</p>
     */
    static String callSource(ToolContext toolContext) {
        Object value = toolContext == null || toolContext.getContext() == null
                ? null : toolContext.getContext().get(AiToolContextKeys.CALL_SOURCE);
        if (value instanceof String text && !text.isBlank()) {
            return text.trim().toUpperCase(java.util.Locale.ROOT);
        }
        return "CHAT";
    }

    /**
     * 记录一次工具调用。任何异常都不会向上抛出，避免影响业务工具本身的执行结果。
     *
     * <p><b>SYS-A-09 脱敏</b>：{@code arguments} 与 {@code result} 在落库**与推送前**
     * 都要经 {@code SensitiveFieldMasker} 处理。这条路径很容易被忽略——
     * ANALYST 对自己的会话有完整查询权，如果写工具入参里的手机号原文落进
     * {@code ai_tool_call}，SYS-P-11 的字段收窄与 D-4 会被同时绕过。</p>
     *
     * @return 落库后的主键，未落库时为 null
     */
    public Long record(String toolName,
                       ToolKind kind,
                       String arguments,
                       String result,
                       boolean success,
                       long durationMs,
                       String errorMessage,
                       ToolContext toolContext) {

        Long conversationId = longValue(toolContext, AiToolContextKeys.CONVERSATION_ID);
        Long userId = longValue(toolContext, AiToolContextKeys.USER_ID);
        ToolCallEventSink sink = sink(toolContext);
        String source = callSource(toolContext);
        // traceId 与审计、成本日志同源：优先请求线程写进 ToolContext 的快照，回落 MDC
        String traceId = resolveTraceId(toolContext);

        // 采集本轮工具事实（口径 + 真实提案编号）：这是收尾时服务端生成口径、校验编号的唯一来源。
        // 必须用**脱敏前**的原始返回值，避免掩码把 dataSource 里的内容改掉。
        collectTurnFacts(toolContext, toolName, result);

        // 脱敏后的入参/结果：落库与 SSE 使用同一份，避免"界面看到明文、审计只有掩码"
        String safeArguments = mask(arguments);
        String safeResult = mask(result);

        Long id = null;
        try {
            AiToolCall entity = new AiToolCall();
            entity.setConversationId(conversationId);
            entity.setToolName(toolName);
            entity.setToolType(kind.name());
            entity.setArguments(truncate(safeArguments));
            entity.setResult(truncate(safeResult));
            entity.setStatus(success ? "SUCCESS" : "FAILED");
            entity.setDurationMs(durationMs);
            entity.setErrorMessage(truncate(mask(errorMessage), 500));
            // 来源与 traceId：AC-MCP-05（可识别是谁发起的）/ AC-MCP-10（三者 trace_id 一致）
            entity.setSource(source);
            entity.setTraceId(traceId);
            toolCallMapper.insert(entity);
            id = entity.getId();

            AiAuditLog audit = new AiAuditLog();
            audit.setConversationId(conversationId);
            audit.setUserId(userId);
            audit.setAction("TOOL_CALL");
            audit.setDetail("tool=" + toolName + " type=" + kind + " status="
                    + entity.getStatus() + " durationMs=" + durationMs);
            audit.setTraceId(traceId);
            auditLogMapper.insert(audit);
        } catch (Exception ex) {
            log.error("记录 Tool Call 失败 tool={} conversationId={}", toolName, conversationId, ex);
        }

        // 指标与落库解耦：即使上面落库失败，调用计数与耗时的观测也不该断（REQ-MCP-08）
        metrics.toolCall(toolName, success ? "SUCCESS" : "FAILED", source, durationMs);

        if (sink != null) {
            sink.emit(new ToolCallEvent(id, toolName, kind.name(), truncate(safeArguments),
                    truncate(safeResult), success ? "SUCCESS" : "FAILED", durationMs,
                    truncate(mask(errorMessage), 500)));
        }

        log.info("Tool Call 完成 tool={} type={} status={} durationMs={} conversationId={}",
                toolName, kind, success ? "SUCCESS" : "FAILED", durationMs, conversationId);
        return id;
    }

    /**
     * 文本级脱敏（SYS-A-09）。
     *
     * <p>工具入参是模型生成的 JSON，键名已知（例如 {@code phone}）。这里同时做两件事：
     * ① 按 JSON 解析后递归处理敏感键；② 解析失败时退化为正则模式替换。
     * 两层都失败的概率极低，但即使都失败也不会把明文写进去——正则层能覆盖手机号/邮箱形态。</p>
     */
    private String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        try {
            Object parsed = objectMapper.readValue(text, Object.class);
            Object masked = SensitiveFieldMasker.maskDeep(parsed);
            return objectMapper.writeValueAsString(masked);
        } catch (Exception ex) {
            // 非 JSON（例如工具抛出的错误文本）：用模式替换兜底
            return SensitiveFieldMasker.maskText(text);
        }
    }

    private static Long longValue(ToolContext context, String key) {        Object v = raw(context, key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        return null;
    }

    private static ToolCallEventSink sink(ToolContext context) {
        Object v = raw(context, AiToolContextKeys.EVENT_SINK);
        return v instanceof ToolCallEventSink s ? s : null;
    }

    /**
     * 把一次工具返回值里的事实并入本轮收集器。
     *
     * <p>任何异常都必须被吞掉：事实采集是**收尾用的增强**，绝不能因为它失败而让
     * 一次正常的数据查询报错（与 {@link #record} 的既有约定一致）。</p>
     */
    private void collectTurnFacts(ToolContext context, String toolName, String result) {
        Object value = raw(context, AiToolContextKeys.TURN_FACTS);
        if (!(value instanceof TurnFacts facts) || result == null || result.isBlank()) {
            return;
        }
        try {
            facts.merge(TurnFacts.extract(result, objectMapper));
        } catch (Exception ex) {
            log.warn("采集工具事实失败 tool={}", toolName, ex);
        }
    }

    private static Object raw(ToolContext context, String key) {
        if (context == null || context.getContext() == null) {
            return null;
        }
        return context.getContext().get(key);
    }

    private static String truncate(String text) {
        return truncate(text, MAX_TEXT);
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "...(truncated)";
    }

    /**
     * Tool 在流式过程中执行，此时已不在最初的 Web 请求线程上，MDC 可能已被清理，
     * 因此优先使用随 ToolContext 下传的 TraceId。
     */
    private static String resolveTraceId(ToolContext context) {
        Object fromContext = raw(context, AiToolContextKeys.TRACE_ID);
        if (fromContext instanceof String s && !s.isBlank()) {
            return s;
        }
        return TraceContext.currentTraceId();
    }
}
