package com.guarantee.ai.tool;

import reactor.core.publisher.Sinks;

/**
 * 把 Tool Call 事件从「Tool 执行线程」推送到「SSE 响应流」的桥。
 *
 * <p>Tool 由 Spring AI 的 ToolCallingAdvisor 在流式过程中同步执行，
 * 此时 HTTP 响应流已经建立，因此通过一个缓冲 Sink 把事件合并进响应流。</p>
 */
public final class ToolCallEventSink {

    private final Sinks.Many<ToolCallEvent> sink;

    public ToolCallEventSink(Sinks.Many<ToolCallEvent> sink) {
        this.sink = sink;
    }

    public void emit(ToolCallEvent event) {
        // 前端断开连接后 emit 会失败，这里静默忽略，不影响工具本身的执行结果
        sink.tryEmitNext(event);
    }

    public void complete() {
        sink.tryEmitComplete();
    }
}
