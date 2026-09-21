package com.guarantee.ai.service;

import com.guarantee.ai.dto.AiChatRequest;
import com.guarantee.ai.entity.AiConversation;
import com.guarantee.ai.entity.AiMessage;
import com.guarantee.ai.time.TimeRange;
import com.guarantee.ai.time.TimeSemanticParser;
import com.guarantee.ai.tool.AiToolContextKeys;
import com.guarantee.ai.tool.AiToolRegistry;
import com.guarantee.ai.tool.ToolCallEvent;
import com.guarantee.ai.tool.ToolCallEventSink;
import com.guarantee.ai.vo.ChatStreamEvents;
import com.guarantee.common.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 聊天核心服务：SSE 流式输出 + Tool 调用循环 + 会话落库。
 *
 * <p><b>为什么自己驱动工具循环</b>：Spring AI 2.0 的 {@code ToolCallingAdvisor}
 * 由 {@code DefaultChatClient} 按请求自动装配，但在本项目的装配方式下并不会进入顾问链
 * （已实测：自定义顾问会被调用，{@code ToolCallingAdvisor} 不会）。
 * 这里改为直接使用框架提供的 {@link ToolCallingManager} 显式驱动循环，好处是：</p>
 * <ul>
 *   <li>行为完全可控、可测试，不依赖隐式自动装配；</li>
 *   <li>每一轮工具执行都能被 {@code RecordingToolCallback} 精确计时并落库；</li>
 *   <li>工具调用轮次本身不产生正文（assistant 只返回 tool_calls），
 *       因此可以把每轮流式内容直接转发给前端，最终答案依然是**真流式**。</li>
 * </ul>
 *
 * <p>一次请求的完整链路：</p>
 * <ol>
 *   <li>解析/新建会话，写入用户消息；</li>
 *   <li>用 {@link TimeSemanticParser} 把「2026年第三季度」这类语义预解析成明确日期注入 System Prompt；</li>
 *   <li>流式调用模型；若返回 tool_calls，则交给 {@link ToolCallingManager} 执行，
 *       执行过程通过 {@link ToolCallEventSink} 实时推给前端并落库；</li>
 *   <li>把工具结果回灌会话继续下一轮，直到模型给出最终答案；</li>
 *   <li>落库助手消息并关联本轮全部 Tool Call。</li>
 * </ol>
 */
@Service
public class AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);

    private static final int HISTORY_LIMIT = 20;

    /** 防止模型陷入工具调用死循环。 */
    private static final int MAX_TOOL_ROUNDS = 4;

    private final ChatModel chatModel;
    private final ToolCallingManager toolCallingManager;
    private final AiConversationService conversationService;
    private final BusinessAssistantPrompt promptProvider;
    private final TimeSemanticParser timeSemanticParser;
    private final AiToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final String modelName;

    public AiChatService(ChatModel chatModel,
                         ToolCallingManager toolCallingManager,
                         AiConversationService conversationService,
                         BusinessAssistantPrompt promptProvider,
                         TimeSemanticParser timeSemanticParser,
                         AiToolRegistry toolRegistry,
                         ObjectMapper objectMapper,
                         @Value("${spring.ai.openai.chat.model:unknown}") String modelName) {
        this.chatModel = chatModel;
        this.toolCallingManager = toolCallingManager;
        this.conversationService = conversationService;
        this.promptProvider = promptProvider;
        this.timeSemanticParser = timeSemanticParser;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.modelName = modelName;
    }

    /**
     * 执行一次流式对话。
     *
     * @return SSE 事件流（meta / delta / tool_call / done / error）
     */
    public Flux<ServerSentEvent<String>> stream(Long userId, AiChatRequest request) {
        String userText = request.getMessage();

        AiConversation conversation = conversationService.resolveOrCreate(
                userId, request.getConversationId(), userText, modelName);
        Long conversationId = conversation.getId();

        // 先取历史，再写入本轮用户消息，避免历史里重复出现本轮问题
        List<Message> history = loadHistory(conversationId);
        conversationService.appendMessage(conversationId, "USER", userText);
        conversationService.audit(conversationId, userId, "CHAT", "用户提问已受理");

        Optional<TimeRange> parsedTime = timeSemanticParser.parse(userText);
        String systemPrompt = promptProvider.build(parsedTime);

        Sinks.Many<ToolCallEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        Map<String, Object> toolContext = buildToolContext(conversationId, userId, new ToolCallEventSink(sink));

        ToolCallingChatOptions options = buildToolCallingOptions(toolContext);

        List<Message> messages = new ArrayList<>(history.size() + 2);
        messages.add(new SystemMessage(systemPrompt));
        messages.addAll(history);
        messages.add(new UserMessage(buildUserText(userText, parsedTime)));

        StringBuilder answer = new StringBuilder();
        AtomicBoolean persisted = new AtomicBoolean(false);
        AtomicBoolean failed = new AtomicBoolean(false);

        Flux<ServerSentEvent<String>> contentEvents = runToolLoop(new Prompt(messages, options), answer, 0)
                // 模型输出结束后关闭 tool 事件通道，merge 才会随之完成
                .doOnComplete(sink::tryEmitComplete);

        Flux<ServerSentEvent<String>> toolEvents = sink.asFlux()
                .map(toolCall -> event("tool_call", toolCall));

        Flux<ServerSentEvent<String>> meta = Flux.just(event("meta",
                new ChatStreamEvents.Meta(conversationId, conversation.getConversationNo(),
                        conversation.getTitle())));

        Flux<ServerSentEvent<String>> streamed = Flux.concat(meta, Flux.merge(contentEvents, toolEvents));

        Flux<ServerSentEvent<String>> safe = streamed.onErrorResume(ex -> {
            failed.set(true);
            log.error("AI 流式对话失败 conversationId={}", conversationId, ex);
            persistAssistant(persisted, conversationId, userId, answer.toString());
            conversationService.audit(conversationId, userId, "ERROR", safeMessage(ex));
            return Flux.just(event("error", new ChatStreamEvents.Error(safeMessage(ex))));
        });

        return safe.concatWith(Flux.defer(() -> {
            if (failed.get()) {
                return Flux.empty();
            }
            Long messageId = persistAssistant(persisted, conversationId, userId, answer.toString());
            conversationService.audit(conversationId, userId, "CHAT", "助手回答已完成");
            return Flux.just(event("done", new ChatStreamEvents.Done(conversationId, messageId)));
        }));
    }

    // ------------------------------------------------------------------
    // 工具调用循环
    // ------------------------------------------------------------------

    /**
     * 构造带工具的工具调用选项。
     *
     * <p>必须**基于模型自身的默认选项**做 mutate，而不能用
     * {@code ToolCallingChatOptions.builder()} 从零构造：具体实现（如
     * {@code OpenAiChatOptions}）会把 prompt 的 options 强转成自己的类型，
     * 传通用的 {@code DefaultToolCallingChatOptions} 会在运行时抛 ClassCastException。</p>
     */
    private ToolCallingChatOptions buildToolCallingOptions(Map<String, Object> toolContext) {
        // 注意用 getOptions() 而不是 getDefaultOptions()：
        // OpenAiChatModel.getOptions() 返回 OpenAiChatOptions（其内部会强转 options），
        // 而 getDefaultOptions() 在部分实现里返回的是通用 ChatOptions。
        ChatOptions defaults = chatModel.getOptions();
        ToolCallingChatOptions.Builder<?> builder = defaults instanceof ToolCallingChatOptions toolCallingDefaults
                ? toolCallingDefaults.mutate()
                : ToolCallingChatOptions.builder();
        builder.toolCallbacks(List.of(toolRegistry.readToolCallbacks()));
        builder.toolContext(toolContext);
        return builder.build();
    }

    /**
     * 执行一轮模型调用；若该轮产生工具调用，则执行工具并递归下一轮。
     *
     * <p>工具调用轮次的 assistant 消息只包含 tool_calls、没有正文，
     * 所以把每轮流式正文直接转发给前端不会泄漏中间态，最终答案依旧是真流式。</p>
     */
    private Flux<ServerSentEvent<String>> runToolLoop(Prompt prompt, StringBuilder answer, int depth) {
        if (depth >= MAX_TOOL_ROUNDS) {
            log.warn("工具调用达到最大轮次 {}，停止循环", MAX_TOOL_ROUNDS);
            return Flux.empty();
        }
        return Flux.defer(() -> {
            // doOnNext 是串行调用的，普通 ArrayList 足够
            List<ChatResponse> collected = new ArrayList<>();

            Flux<ServerSentEvent<String>> streamed = chatModel.stream(prompt)
                    .doOnNext(collected::add)
                    .concatMap(chunk -> {
                        String text = textOf(chunk);
                        if (!StringUtils.hasText(text)) {
                            return Flux.empty();
                        }
                        answer.append(text);
                        return Flux.just(event("delta", new ChatStreamEvents.Delta(text)));
                    });

            return streamed.concatWith(Flux.defer(() -> {
                List<AssistantMessage.ToolCall> toolCalls = mergeToolCalls(collected);
                if (toolCalls.isEmpty()) {
                    return Flux.empty();
                }
                log.debug("模型请求执行 {} 个工具调用（第 {} 轮）", toolCalls.size(), depth + 1);

                // 工具执行是阻塞的，且 Tool 内部会通过 ToolContext 中的 sink 实时推送事件
                ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, aggregate(toolCalls));
                Prompt next = new Prompt(result.conversationHistory(), prompt.getOptions());
                return runToolLoop(next, answer, depth + 1);
            }));
        });
    }

    private static String textOf(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return null;
        }
        return response.getResult().getOutput().getText();
    }

    /**
     * 合并流式分片中的工具调用。
     *
     * <p>OpenAI 兼容协议的流式响应中，同一次工具调用的 name 出现在首个分片，
     * 参数可能被拆成多个增量分片，因此按 id 合并并拼接参数。
     * 若后一个分片携带的是「更完整的完整参数」而非增量，则直接采用它。</p>
     */
    private static List<AssistantMessage.ToolCall> mergeToolCalls(List<ChatResponse> chunks) {
        Map<String, AssistantMessage.ToolCall> byKey = new LinkedHashMap<>();
        for (ChatResponse response : chunks) {
            if (response == null || !response.hasToolCalls()) {
                continue;
            }
            for (AssistantMessage.ToolCall call : response.getResult().getOutput().getToolCalls()) {
                String key = StringUtils.hasText(call.id()) ? call.id() : call.name();
                byKey.merge(key, call, AiChatService::mergeToolCall);
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private static AssistantMessage.ToolCall mergeToolCall(AssistantMessage.ToolCall previous,
                                                           AssistantMessage.ToolCall current) {
        String merged = current.arguments();
        if (merged == null) {
            merged = previous.arguments();
        } else if (previous.arguments() != null && !merged.startsWith(previous.arguments())) {
            merged = previous.arguments() + merged;
        }
        String name = StringUtils.hasText(previous.name()) ? previous.name() : current.name();
        String type = StringUtils.hasText(previous.type()) ? previous.type() : current.type();
        return new AssistantMessage.ToolCall(previous.id(), type, name, merged);
    }

    /** 把合并后的工具调用重新包装成一次完整的模型响应，交给 ToolCallingManager 执行。 */
    private static ChatResponse aggregate(List<AssistantMessage.ToolCall> toolCalls) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(toolCalls)
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    // ------------------------------------------------------------------
    // 持久化与工具方法
    // ------------------------------------------------------------------

    /**
     * 保存助手消息（只执行一次），并把本轮 Tool Call 关联到该消息。
     *
     * @return 助手消息主键；内容为空时不落库，返回 null
     */
    private Long persistAssistant(AtomicBoolean persisted, Long conversationId, Long userId, String answer) {
        if (!persisted.compareAndSet(false, true)) {
            return null;
        }
        if (!StringUtils.hasText(answer)) {
            return null;
        }
        try {
            AiMessage saved = conversationService.appendMessage(conversationId, "ASSISTANT", answer);
            conversationService.bindToolCallsToMessage(conversationId, saved.getId());
            return saved.getId();
        } catch (RuntimeException ex) {
            log.error("保存助手消息失败 conversationId={} userId={}", conversationId, userId, ex);
            return null;
        }
    }

    private List<Message> loadHistory(Long conversationId) {
        List<AiMessage> recent = conversationService.recentMessages(conversationId, HISTORY_LIMIT);
        List<Message> messages = new ArrayList<>(recent.size());
        for (AiMessage m : recent) {
            Message converted = toSpringMessage(m);
            if (converted != null) {
                messages.add(converted);
            }
        }
        return messages;
    }

    private static Message toSpringMessage(AiMessage message) {
        if (message == null || !StringUtils.hasText(message.getContent())) {
            return null;
        }
        return switch (message.getRole()) {
            case "USER" -> new UserMessage(message.getContent());
            case "ASSISTANT" -> new AssistantMessage(message.getContent());
            // SYSTEM / TOOL 消息不进多轮上下文，避免污染对话
            default -> null;
        };
    }

    /**
     * 构造 ToolContext。
     *
     * <p>注意：Spring AI 的 {@code ToolContext} 不允许 value 为 null
     * （{@code Assert.noNullElements}），因此非 Web 线程（例如无 MDC 的集成测试）下
     * TraceId 为空时必须跳过该键。</p>
     */
    private static Map<String, Object> buildToolContext(Long conversationId, Long userId,
                                                        ToolCallEventSink eventSink) {
        Map<String, Object> context = new HashMap<>();
        putIfNotNull(context, AiToolContextKeys.CONVERSATION_ID, conversationId);
        putIfNotNull(context, AiToolContextKeys.USER_ID, userId);
        putIfNotNull(context, AiToolContextKeys.TRACE_ID, TraceContext.currentTraceId());
        context.put(AiToolContextKeys.EVENT_SINK, eventSink);
        return context;
    }

    private static void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    /** 把预解析出的明确日期追加给模型，降低其自行换算相对时间的出错概率。 */
    private static String buildUserText(String userText, Optional<TimeRange> parsedTime) {
        if (parsedTime.isEmpty()) {
            return userText;
        }
        TimeRange range = parsedTime.get();
        return userText + "\n\n[系统预解析] 本轮时间范围：" + range.startDate() + " ~ " + range.endDate()
                + "（" + range.description() + "）。调用工具时请直接使用这两个日期。";
    }

    private ServerSentEvent<String> event(String name, Object payload) {
        return ServerSentEvent.<String>builder()
                .event(name)
                .data(toJson(payload))
                .build();
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException ex) {
            log.warn("SSE 载荷序列化失败: {}", payload, ex);
            return "{}";
        }
    }

    /** 把底层异常翻译成用户能看懂、且不泄漏堆栈的提示。 */
    private static String safeMessage(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String text = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        String lower = text == null ? "" : text.toLowerCase();
        if (lower.contains("401") || lower.contains("unauthorized") || lower.contains("api key")
                || lower.contains("incorrect api key")) {
            return "AI 模型调用失败：API Key 未配置或无效（请设置环境变量 DEEPSEEK_API_KEY 后重启服务）";
        }
        if (lower.contains("connection refused") || lower.contains("connect timed out")
                || lower.contains("unknownhost") || lower.contains("timeout")) {
            return "AI 模型调用失败：无法连接模型服务，请检查网络与 spring.ai.openai.base-url 配置";
        }
        if (lower.contains("429") || lower.contains("rate limit")) {
            return "AI 模型调用失败：请求过于频繁或额度不足，请稍后重试";
        }
        return "AI 处理失败：" + Objects.toString(text, "未知错误");
    }
}
