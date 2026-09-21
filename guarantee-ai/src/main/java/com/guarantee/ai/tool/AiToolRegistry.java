package com.guarantee.ai.tool;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * AI 工具注册表。
 *
 * <p>当前阶段只注册 READ 工具。{@link #readToolCallbacks()} 会把业务 Tool 的
 * ToolCallback 包装成 {@link RecordingToolCallback} 以便统一审计。</p>
 *
 * <p><b>安全边界</b>：WRITE 工具在第一阶段一律不注册；
 * 后续接入时必须补齐 Permission Check / Preview / Confirmation / Audit。</p>
 */
@Component
public class AiToolRegistry {

    private final OrderSummaryTool orderSummaryTool;
    private final AiToolCallRecorder recorder;

    public AiToolRegistry(OrderSummaryTool orderSummaryTool, AiToolCallRecorder recorder) {
        this.orderSummaryTool = orderSummaryTool;
        this.recorder = recorder;
    }

    /**
     * 返回全部只读工具（已包装为可审计的 ToolCallback）。
     */
    public ToolCallback[] readToolCallbacks() {
        return Arrays.stream(ToolCallbacks.from(orderSummaryTool))
                .map(cb -> (ToolCallback) new RecordingToolCallback(cb, ToolKind.READ, recorder))
                .toArray(ToolCallback[]::new);
    }
}
