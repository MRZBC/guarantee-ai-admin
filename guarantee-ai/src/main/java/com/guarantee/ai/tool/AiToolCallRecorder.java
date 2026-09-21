package com.guarantee.ai.tool;

import com.guarantee.ai.entity.AiAuditLog;
import com.guarantee.ai.entity.AiToolCall;
import com.guarantee.ai.mapper.AiAuditLogMapper;
import com.guarantee.ai.mapper.AiToolCallMapper;
import com.guarantee.common.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

/**
 * Tool Call 记录器：把每次工具执行落库到 {@code ai_tool_call}，并写一条审计日志。
 *
 * <p>记录内容：tool name、arguments、result、status、duration。</p>
 */
@Component
public class AiToolCallRecorder {

    private static final Logger log = LoggerFactory.getLogger(AiToolCallRecorder.class);

    private static final int MAX_TEXT = 60_000;

    private final AiToolCallMapper toolCallMapper;
    private final AiAuditLogMapper auditLogMapper;

    public AiToolCallRecorder(AiToolCallMapper toolCallMapper, AiAuditLogMapper auditLogMapper) {
        this.toolCallMapper = toolCallMapper;
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * 记录一次工具调用。任何异常都不会向上抛出，避免影响业务工具本身的执行结果。
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

        Long id = null;
        try {
            AiToolCall entity = new AiToolCall();
            entity.setConversationId(conversationId);
            entity.setToolName(toolName);
            entity.setToolType(kind.name());
            entity.setArguments(truncate(arguments));
            entity.setResult(truncate(result));
            entity.setStatus(success ? "SUCCESS" : "FAILED");
            entity.setDurationMs(durationMs);
            entity.setErrorMessage(truncate(errorMessage, 500));
            toolCallMapper.insert(entity);
            id = entity.getId();

            AiAuditLog audit = new AiAuditLog();
            audit.setConversationId(conversationId);
            audit.setUserId(userId);
            audit.setAction("TOOL_CALL");
            audit.setDetail("tool=" + toolName + " type=" + kind + " status="
                    + entity.getStatus() + " durationMs=" + durationMs);
            audit.setTraceId(resolveTraceId(toolContext));
            auditLogMapper.insert(audit);
        } catch (Exception ex) {
            log.error("记录 Tool Call 失败 tool={} conversationId={}", toolName, conversationId, ex);
        }

        if (sink != null) {
            sink.emit(new ToolCallEvent(id, toolName, kind.name(), truncate(arguments),
                    truncate(result), success ? "SUCCESS" : "FAILED", durationMs,
                    truncate(errorMessage, 500)));
        }

        log.info("Tool Call 完成 tool={} type={} status={} durationMs={} conversationId={}",
                toolName, kind, success ? "SUCCESS" : "FAILED", durationMs, conversationId);
        return id;
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
