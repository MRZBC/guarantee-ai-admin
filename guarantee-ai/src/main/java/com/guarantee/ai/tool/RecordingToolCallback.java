package com.guarantee.ai.tool;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * ToolCallback 装饰器：在不侵入业务 Tool 的前提下，
 * 统一采集 tool name / arguments / result / status / duration 并记录。
 *
 * <p>用装饰器而不是全局 ToolCallingManager，是为了拿到**每次调用**的精确耗时；
 * ToolCallingManager 只能拿到一整批调用的总耗时。</p>
 */
public class RecordingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ToolKind kind;
    private final AiToolCallRecorder recorder;

    public RecordingToolCallback(ToolCallback delegate, ToolKind kind, AiToolCallRecorder recorder) {
        this.delegate = delegate;
        this.kind = kind;
        this.recorder = recorder;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String toolName = delegate.getToolDefinition().name();
        long start = System.nanoTime();
        try {
            String result = delegate.call(toolInput, toolContext);
            recorder.record(toolName, kind, toolInput, result, true,
                    elapsedMs(start), null, toolContext);
            return result;
        } catch (RuntimeException | Error ex) {
            recorder.record(toolName, kind, toolInput, null, false,
                    elapsedMs(start), ex.getMessage(), toolContext);
            // 原样抛出，交给 Spring AI 的 ToolExecutionExceptionProcessor 处理
            throw ex;
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
