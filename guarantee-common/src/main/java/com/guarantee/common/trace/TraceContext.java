package com.guarantee.common.trace;

import org.slf4j.MDC;

/**
 * TraceId 上下文。基于 SLF4J MDC，保证日志与响应体可以关联到同一次请求。
 */
public final class TraceContext {

    public static final String MDC_KEY = "traceId";
    public static final String HEADER = "X-Trace-Id";

    private TraceContext() {
    }

    /** 当前请求的 TraceId，可能为 {@code null}（例如非 Web 线程）。 */
    public static String currentTraceId() {
        return MDC.get(MDC_KEY);
    }

    static void set(String traceId) {
        MDC.put(MDC_KEY, traceId);
    }

    static void clear() {
        MDC.remove(MDC_KEY);
    }
}
