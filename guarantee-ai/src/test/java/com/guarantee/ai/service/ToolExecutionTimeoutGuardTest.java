package com.guarantee.ai.service;

import com.guarantee.ai.tool.AiToolCallRecorder;
import com.guarantee.ai.tool.BoundedToolCallback;
import com.guarantee.ai.tool.RecordingToolCallback;
import com.guarantee.ai.tool.ToolKind;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionException;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 单次工具执行护栏的单元测试（REQ-BA-06，TEST-BA-02）。
 *
 * <p>守两件事：</p>
 * <ol>
 *   <li><b>超时是一条可读失败</b>：超过 {@link BoundedToolCallback#TOOL_TIMEOUT_MS}
 *       后该次调用记为 {@code FAILED}（{@code ai_tool_call.status}），并且模型拿到
 *       "哪个工具 / 多久没返回 / 哪项指标没取到"的可读原因——这是"工具失败也算有出口"
 *       的落点；</li>
 *   <li><b>既有行为不被这次改造带坏</b>：业务异常原样上抛、正常结果原样返回、
 *       超过 16KB 仍然截断。</li>
 * </ol>
 */
class ToolExecutionTimeoutGuardTest {

    @Test
    @DisplayName("单次工具执行上限是具名常量 10 秒（REQ-BA-06）")
    void timeoutIsTenSeconds() {
        assertThat(BoundedToolCallback.TOOL_TIMEOUT_MS)
                .as("需求目标：单次工具执行 10s → 记为 FAILED + 可读原因")
                .isEqualTo(10_000L);
    }

    @Test
    @DisplayName("工具超时：记为 FAILED，并把可读失败原因回灌给模型（不让整轮卡死）")
    void timeoutIsRecordedAsFailedAndReadableReasonReachesModel() {
        AiToolCallRecorder recorder = mock(AiToolCallRecorder.class);
        ToolCallback recording = new RecordingToolCallback(
                new BoundedToolCallback(sleepingTool("slowQuery", 500L), 50L),
                ToolKind.READ, recorder);

        String modelVisibleResult = executeSingleToolCall(recording, "slowQuery");

        assertThat(modelVisibleResult)
                .as("回灌给模型的必须是一条可读原因，而不是堆栈或英文异常")
                .contains("slowQuery")
                .contains("超过 50 毫秒")
                .contains("超时")
                .contains("该项指标本轮没有取到")
                .doesNotContain("Exception")
                .doesNotContain("at com.guarantee");
        verify(recorder).record(eq("slowQuery"), eq(ToolKind.READ), anyString(), isNull(), eq(false),
                anyLong(), contains("超时"), nullable(ToolContext.class));
    }

    @Test
    @DisplayName("超时异常带出工具名与阈值，且包成框架认识的 ToolExecutionException")
    void timeoutExceptionCarriesToolNameAndLimit() {
        ToolCallback bounded = new BoundedToolCallback(sleepingTool("queryOrderSummary", 300L), 40L);

        assertThatThrownBy(() -> bounded.call("{}"))
                .as("必须是 ToolExecutionException：框架只在这一类异常上把原因回灌给模型")
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("queryOrderSummary")
                .hasMessageContaining("超过 40 毫秒")
                .hasRootCauseInstanceOf(BoundedToolCallback.ToolExecutionTimeoutException.class);
    }

    @Test
    @DisplayName("业务异常原样上抛：不得被超时装饰器包成 ExecutionException/IllegalStateException")
    void businessExceptionIsPropagatedUnchanged() {
        IllegalStateException original = new IllegalStateException("dimension 只能是 REGION / ORG / INSURANCE");
        ToolCallback bounded = new BoundedToolCallback(throwingTool("queryOrderDistribution", original), 1_000L);

        assertThatThrownBy(() -> bounded.call("{}")).isSameAs(original);
    }

    @Test
    @DisplayName("正常结果原样返回；超过 16KB 仍然截断并留下可读标记（SYS-Q-10 回归）")
    void oversizedResultIsStillTruncated() {
        ToolCallback bounded = new BoundedToolCallback(resultTool("queryOrderSummary", "{\"orderCount\":1}"), 1_000L);
        assertThat(bounded.call("{}")).isEqualTo("{\"orderCount\":1}");

        String oversized = "{\"items\":[\"" + "x".repeat(20_000) + "\"],\"meta\":{\"truncated\":false}}";
        ToolCallback truncating = new BoundedToolCallback(resultTool("queryOrderSummary", oversized), 1_000L);

        String truncated = truncating.call("{}");
        assertThat(truncated).as("截断必须留下可读标记，模型才知道'只看过一部分'")
                .contains("结果超限已截断");
        assertThat(truncated.getBytes(StandardCharsets.UTF_8).length)
                .as("截断后的内容不得显著超过 16KB（允许追加的标记尾巴）")
                .isLessThanOrEqualTo(BoundedToolCallback.MAX_RESULT_BYTES + 64);
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** 走一遍真实的 {@code DefaultToolCallingManager}（生产同一条路径），取回模型看到的结果。 */
    private static String executeSingleToolCall(ToolCallback callback, String toolName) {
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(callback))
                .build();
        Prompt prompt = new Prompt("查一下", options);
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", toolName, "{}")))
                .build();
        DefaultToolCallingManager manager = new DefaultToolCallingManager(
                ObservationRegistry.NOOP,
                name -> null,
                new DefaultToolExecutionExceptionProcessor(false));

        ToolExecutionResult result = manager.executeToolCalls(
                prompt, new ChatResponse(List.of(new Generation(message))));
        for (Message historyMessage : result.conversationHistory()) {
            if (historyMessage instanceof ToolResponseMessage toolResponses) {
                return toolResponses.getResponses().get(0).responseData();
            }
        }
        throw new AssertionError("工具执行后没有产生 tool 响应");
    }

    private static ToolCallback sleepingTool(String name, long sleepMillis) {
        return tool(name, () -> {
            Thread.sleep(sleepMillis);
            return "{\"ok\":true}";
        });
    }

    private static ToolCallback throwingTool(String name, RuntimeException failure) {
        return tool(name, () -> {
            throw failure;
        });
    }

    private static ToolCallback resultTool(String name, String result) {
        return tool(name, () -> result);
    }

    private static ToolCallback tool(String name, ToolBody body) {
        ToolDefinition definition = DefaultToolDefinition.builder()
                .name(name)
                .description("测试用工具")
                .inputSchema("{\"type\":\"object\"}")
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return call(toolInput, null);
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                try {
                    return body.run();
                } catch (RuntimeException | Error ex) {
                    throw ex;
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            }
        };
    }

    /** 允许抛受检异常的测试用函数体。 */
    @FunctionalInterface
    private interface ToolBody {
        String run() throws Exception;
    }
}
