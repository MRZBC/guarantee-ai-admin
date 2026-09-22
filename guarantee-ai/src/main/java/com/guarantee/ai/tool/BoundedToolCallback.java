package com.guarantee.ai.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.nio.charset.StandardCharsets;

/**
 * 工具返回值大小上限装饰器（SYS-Q-10）。
 *
 * <p><b>为什么放在装饰器而不是每个工具里</b>：单次返回不得超过 16KB 是一条通用的
 * 传输约束，不该由 7 个工具各自实现。放在注册链上，任何新增工具自动受约束，
 * 也不存在"某个工具忘了截断"的可能。</p>
 *
 * <p><b>截断必须显式标记</b>：返回值统一带有 {@code meta} 对象（见 {@link ToolResultMeta}），
 * 这里把 {@code meta.truncated} 置为 true 并写入 {@code truncatedHint}，
 * 让模型知道"数据不完整"，而不是基于残缺数据下结论。</p>
 *
 * <p>装饰顺序很关键：本装饰器必须**在** {@link RecordingToolCallback} 内层，
 * 这样 SSE 推给前端的 {@code result} 与落库的 {@code ai_tool_call.result} 是同一份
 * 已截断内容，不会出现"界面看到完整的、审计只存了截断的"这种不一致。</p>
 */
public class BoundedToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(BoundedToolCallback.class);

    /** 单次工具返回结果序列化后的字节上限。 */
    public static final int MAX_RESULT_BYTES = 16 * 1024;

    /** 截断提示语，模型会据此说明"结果不完整"。 */
    public static final String TRUNCATED_HINT =
            "结果超出 16KB 上限已截断，仅返回部分数据；请缩小查询范围（例如加时间条件、减小 limit）后重试";

    private final ToolCallback delegate;

    public BoundedToolCallback(ToolCallback delegate) {
        this.delegate = delegate;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return bound(delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return bound(delegate.call(toolInput, toolContext));
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
