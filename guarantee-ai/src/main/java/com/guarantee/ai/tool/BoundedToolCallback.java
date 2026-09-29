package com.guarantee.ai.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工具返回值大小上限 + 单次执行超时装饰器（SYS-Q-10 / REQ-BA-06）。
 *
 * <p><b>为什么放在装饰器而不是每个工具里</b>：单次返回不得超过 16KB、单次执行不得超过 10 秒
 * 都是通用的传输/预算约束，不该由 7 个工具各自实现。放在注册链上，任何新增工具自动受约束，
 * 也不存在"某个工具忘了截断"或"某个工具忘了加超时"的可能。</p>
 *
 * <p><b>截断必须显式标记</b>：返回值统一带有 {@code meta} 对象（见 {@link ToolResultMeta}），
 * 这里把 {@code meta.truncated} 置为 true 并写入 {@code truncatedHint}，
 * 让模型知道"数据不完整"，而不是基于残缺数据下结论。</p>
 *
 * <p><b>超时必须是一条可读失败，而不是把整轮拖死</b>（REQ-BA-06 单次工具执行 10s）：
 * 装饰器在**独立线程**上执行被装饰的工具，超时后抛出
 * {@link ToolExecutionTimeoutException}（消息即"哪个指标没取到 + 为什么"）。装饰链上
 * {@link RecordingToolCallback} 在其外层，因此这次调用会被记为
 * {@code FAILED} 并带上同一条可读原因；Spring AI 的
 * {@code DefaultToolExecutionExceptionProcessor}（{@code throw-exception-on-error} 默认 false）
 * 再把它原样回灌给模型。</p>
 *
 * <p>装饰顺序很关键：本装饰器必须**在** {@link RecordingToolCallback} 内层，
 * 这样 SSE 推给前端的 {@code result} 与落库的 {@code ai_tool_call.result} 是同一份
 * 已截断内容，不会出现"界面看到完整的、审计只存了截断的"这种不一致。</p>
 */
public class BoundedToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(BoundedToolCallback.class);

    /** 单次工具返回结果序列化后的字节上限。 */
    public static final int MAX_RESULT_BYTES = 16 * 1024;

    /**
     * 单次工具执行时长上限（毫秒，REQ-BA-06）。
     *
     * <p>依据：工具本身 p95 ≤ 300ms、合计 &lt;1s（需求文档 §7），10 秒给足了一个数量级的余量；
     * 真机上超时只可能来自"SQL 没走索引 / 数据量异常放大"，而不是正常查询。
     * 一旦触顶，宁可让模型得到一条"该项没取到"的可读失败，也不要让整轮预算被一个查询吃掉。</p>
     */
    public static final long TOOL_TIMEOUT_MS = 10_000L;

    /** 截断提示语，模型会据此说明"结果不完整"。 */
    public static final String TRUNCATED_HINT =
            "结果超出 16KB 上限已截断，仅返回部分数据；请缩小查询范围（例如加时间条件、减小 limit）后重试";

    /**
     * 超时执行器：每个工具调用一个**虚拟线程**。
     *
     * <p>用虚拟线程而不是平台线程池：工具是阻塞 IO（JDBC），虚拟线程在阻塞时自动让出载体线程，
     * 不会与 Tomcat 的请求线程池争抢。执行器本身只持有调度器，无需关闭。</p>
     */
    private static final ExecutorService TOOL_EXECUTOR =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("ai-tool-", 0).factory());

    private final ToolCallback delegate;

    /** 单次执行超时（毫秒）；{@code <=0} 表示不限期。 */
    private final long timeoutMs;

    public BoundedToolCallback(ToolCallback delegate) {
        this(delegate, TOOL_TIMEOUT_MS);
    }

    /**
     * 供单元测试注入更短/更长的超时；生产链路恒用 {@link #TOOL_TIMEOUT_MS}
     * （{@link AiToolRegistry} 只走单参构造）。
     */
    public BoundedToolCallback(ToolCallback delegate, long timeoutMs) {
        this.delegate = delegate;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return bound(invokeWithTimeout(() -> delegate.call(toolInput)));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return bound(invokeWithTimeout(() -> delegate.call(toolInput, toolContext)));
    }

    /**
     * 在独立线程上执行工具并施加单次超时。
     *
     * <p>业务异常（{@code RuntimeException} / {@code Error}）**原样重抛**，保持既有语义
     * （装饰链在外层记录 FAILED，Spring AI 再把消息回灌模型）。</p>
     *
     * <p><b>超时为什么要包成 {@link ToolExecutionException}</b>：Spring AI 的
     * {@code DefaultToolCallingManager} 只在捕获 {@code ToolExecutionException} 时才调用
     * {@code ToolExecutionExceptionProcessor} 把消息变成该次工具的结果；抛普通
     * {@code RuntimeException} 会一路冒泡，直接把整轮对话变成 error 事件——
     * 那正是"答题答一半就崩"的形态。包一层框架异常，超时就变成一次**可读的工具失败**。</p>
     */
    private String invokeWithTimeout(Callable<String> invocation) {
        if (timeoutMs <= 0) {
            return unchecked(invocation);
        }
        Future<String> future = TOOL_EXECUTOR.submit(invocation);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            // 取消只能"打断"，JDBC 查询可能仍会跑完；这里保证的是**本轮不再等它**。
            future.cancel(true);
            String toolName = delegate.getToolDefinition().name();
            log.warn("工具执行超时，已判定失败 tool={} timeoutMs={}", toolName, timeoutMs);
            throw timeoutFailure(toolName, timeoutMs);
        } catch (ExecutionException ex) {
            throw uncheckedFailure(ex);
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ToolExecutionException(delegate.getToolDefinition(),
                    new IllegalStateException(
                            "工具执行被中断：" + delegate.getToolDefinition().name(), ex));
        }
    }

    /** 超时失败：外层包 {@link ToolExecutionException}，让框架把原因回灌给模型。 */
    private ToolExecutionException timeoutFailure(String toolName, long timeoutMs) {
        return new ToolExecutionException(delegate.getToolDefinition(),
                new ToolExecutionTimeoutException(toolName, timeoutMs));
    }

    private static String unchecked(Callable<String> invocation) {
        try {
            return invocation.call();
        } catch (RuntimeException | Error ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex.getMessage(), ex);
        }
    }

    private static RuntimeException uncheckedFailure(ExecutionException ex) {
        Throwable cause = ex.getCause() == null ? ex : ex.getCause();
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(cause.getMessage(), cause);
    }

    /**
     * 单次工具执行超时（REQ-BA-06）。
     *
     * <p>它是**原因**（cause），外层一定会被包成 {@link ToolExecutionException} 再抛出：
     * 装饰链外层的 {@link RecordingToolCallback} 正是用异常判定 {@code FAILED}，
     * 而 Spring AI 的 {@code DefaultToolCallingManager} 只会把
     * {@code ToolExecutionException} 的消息作为该次工具结果回灌模型——两件事都因此成立。</p>
     */
    public static final class ToolExecutionTimeoutException extends RuntimeException {

        private final String toolName;

        private final long timeoutMs;

        ToolExecutionTimeoutException(String toolName, long timeoutMs) {
            super(timeoutMessage(toolName, timeoutMs));
            this.toolName = toolName;
            this.timeoutMs = timeoutMs;
        }

        public String getToolName() {
            return toolName;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }
    }

    /**
     * 超时可读原因（会同时进 {@code ai_tool_call.error_message} 与模型上下文）。
     *
     * <p>措辞与既有失败文案同一风格（{@link #TRUNCATED_HINT} / 无权限话术）：说清
     * "哪个工具 / 多久没返回 / 哪项数据没取到 / 下一步怎么办"，不出现堆栈与技术术语。</p>
     */
    static String timeoutMessage(String toolName, long timeoutMs) {
        String limit = timeoutMs % 1000 == 0 ? (timeoutMs / 1000) + " 秒" : timeoutMs + " 毫秒";
        return "工具 " + toolName + " 执行超过 " + limit + " 仍未返回，本次调用已判定失败（超时）："
                + "该项指标本轮没有取到。请缩小查询范围（例如加时间条件、减小 limit）后重试，"
                + "并在回答里如实说明该指标缺失。";
    }

    /**
     * 超限时截断并标记。
     *
     * <p>先尝试在 {@code meta} 里打标记（保留结构化信息），失败则退化为纯文本截断——
     * 无论如何都不允许把超过 16KB 的内容交给模型。</p>
     */
    private String bound(String result) {
        if (result == null) {
            return null;
        }
        byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_RESULT_BYTES) {
            return result;
        }
        log.warn("工具返回结果 {} 字节，超出 {} 字节上限，已截断", bytes.length, MAX_RESULT_BYTES);
        String marked = markTruncated(result);
        if (marked != null && marked.getBytes(StandardCharsets.UTF_8).length <= MAX_RESULT_BYTES) {
            return marked;
        }
        // 兜底：按字节边界安全截断（避免截断到多字节字符中间）
        String cut = new String(bytes, 0, MAX_RESULT_BYTES, StandardCharsets.UTF_8);
        if (!cut.isEmpty() && cut.charAt(cut.length() - 1) == '\uFFFD') {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "\n...(结果超限已截断)";
    }

    /**
     * 在结果 JSON 的 {@code meta} 对象里标记 truncation。
     *
     * <p>只做字符串层面的定点插入，不引入完整 JSON 解析——工具返回值是框架生成的
     * 紧凑 JSON，形如 {@code {"a":1,...,"meta":{...}}}，末端的 {@code meta} 是稳定的。</p>
     */
    private static String markTruncated(String json) {
        int idx = json.lastIndexOf("\"truncated\":");
        if (idx < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder(json);
        int valueStart = idx + "\"truncated\":".length();
        int valueEnd = valueStart;
        while (valueEnd < sb.length()
                && (Character.isLetterOrDigit(sb.charAt(valueEnd)) || sb.charAt(valueEnd) == ' ')) {
            valueEnd++;
        }
        sb.replace(valueStart, valueEnd, "true");
        // 追加 truncatedHint（在 meta 对象内、截断标记之后）
        int insertAt = valueStart + "true".length();
        sb.insert(insertAt, ",\"truncatedHint\":\"" + TRUNCATED_HINT + "\"");
        return sb.toString();
    }
}
