package com.guarantee.ai.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

/**
 * 工具返回值规范化装饰器（间接提示注入的传输层处理，见 {@link ToolResultSanitizer}）。
 *
 * <p><b>装饰顺序</b>：必须在本装饰器**外层**再套 {@link BoundedToolCallback}——
 * 先规范化（结果仍是合法 JSON、且通常更短），再判断是否超 16KB 并截断；
 * 反过来的话，被字节截断的 JSON 已经非法，规范化只能放弃（降级为不处理）。</p>
 *
 * <p>装饰链整体：{@code 业务 Tool → SanitizingToolCallback → BoundedToolCallback → RecordingToolCallback}，
 * 其中 Recording 在最外层，因此**模型看到的、SSE 推给前端的、落库审计的**是同一份已规范化内容。</p>
 *
 * <p><b>失败时原样放行</b>：规范化依赖 JSON 解析，任何异常（非法 JSON、序列化失败）都只说明
 * "这一次规范化不了"，此时返回原值并记一条 warn。这里不适合 fail-closed——把工具结果整体丢掉
 * 会让一次正常查询变成报错，而风险面并没有因此变小（16KB 上限与 JSON 转义仍在）。</p>
 */
public class SanitizingToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(SanitizingToolCallback.class);

    private final ToolCallback delegate;
    private final ObjectMapper objectMapper;

    public SanitizingToolCallback(ToolCallback delegate, ObjectMapper objectMapper) {
        this.delegate = delegate;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return sanitize(delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return sanitize(delegate.call(toolInput, toolContext));
    }

    private String sanitize(String result) {
        if (result == null) {
            return null;
        }
        try {
            return ToolResultSanitizer.sanitize(result, objectMapper);
        } catch (RuntimeException ex) {
            log.warn("工具返回值规范化失败，按原值放行 tool={}", delegate.getToolDefinition().name(), ex);
            return result;
        }
    }
}
