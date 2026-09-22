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
import com.guarantee.common.security.CurrentUser;
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
    private final ProposalEventPublisher proposalEventPublisher;
    private final ObjectMapper objectMapper;
    private final String modelName;

    public AiChatService(ChatModel chatModel,
                         ToolCallingManager toolCallingManager,
                         AiConversationService conversationService,
                         BusinessAssistantPrompt promptProvider,
                         TimeSemanticParser timeSemanticParser,
                         AiToolRegistry toolRegistry,
                         ProposalEventPublisher proposalEventPublisher,
                         ObjectMapper objectMapper,
                         @Value("${spring.ai.openai.chat.model:unknown}") String modelName) {
        this.chatModel = chatModel;
        this.toolCallingManager = toolCallingManager;
        this.conversationService = conversationService;
        this.promptProvider = promptProvider;
        this.timeSemanticParser = timeSemanticParser;
        this.toolRegistry = toolRegistry;
        this.proposalEventPublisher = proposalEventPublisher;
        this.objectMapper = objectMapper;
        this.modelName = modelName;
    }

    /**
     * 执行一次流式对话。
     *
     * @return SSE 事件流（meta / delta / tool_call / reset / proposal / proposal_result / done / error）
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
        Sinks.Many<ChatStreamEvents.Proposal> proposalSink = Sinks.many().unicast().onBackpressureBuffer();
        Sinks.Many<ChatStreamEvents.ProposalResult> resultSink = Sinks.many().unicast().onBackpressureBuffer();
        // 注册通道：提案确认发生在**另一个 HTTP 请求**上，只有靠注册表才能把结果推回本会话
        proposalEventPublisher.register(conversationId, proposalSink, resultSink);

        Map<String, Object> toolContext = buildToolContext(conversationId, userId, userText,
                principalContext(), new ToolCallEventSink(sink), proposalSink);

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
                .doOnComplete(() -> {
                    sink.tryEmitComplete();
                    proposalSink.tryEmitComplete();
                    resultSink.tryEmitComplete();
                });

        Flux<ServerSentEvent<String>> toolEvents = sink.asFlux()
                .map(toolCall -> event("tool_call", toolCall));

        // 写工具生成的提案：必须紧跟 tool_call 之后推到前端，才能渲染确认卡（5.3.1）
        Flux<ServerSentEvent<String>> proposalEvents = proposalSink.asFlux()
                .map(payload -> event("proposal", payload));

        // 提案结果事件：确认接口在另一个请求上产生，通过注册表推回本流
        Flux<ServerSentEvent<String>> proposalResultEvents = resultSink.asFlux()
                .map(payload -> event("proposal_result", payload));

        Flux<ServerSentEvent<String>> meta = Flux.just(event("meta",
                new ChatStreamEvents.Meta(conversationId, conversation.getConversationNo(),
                        conversation.getTitle())));

        Flux<ServerSentEvent<String>> streamed = Flux.concat(meta,
                Flux.merge(contentEvents, toolEvents, proposalEvents, proposalResultEvents));

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
        })).doFinally(signal -> proposalEventPublisher.unregister(conversationId));
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
     *
     * <p>工具集在这里按权限裁剪（SYS-P-12a）：无权限的工具不出现在模型面前。</p>
     */
    private ToolCallingChatOptions buildToolCallingOptions(Map<String, Object> toolContext) {
        // 注意用 getOptions() 而不是 getDefaultOptions()：
        // OpenAiChatModel.getOptions() 返回 OpenAiChatOptions（其内部会强转 options），
        // 而 getDefaultOptions() 在部分实现里返回的是通用 ChatOptions。
        ChatOptions defaults = chatModel.getOptions();
        ToolCallingChatOptions.Builder<?> builder = defaults instanceof ToolCallingChatOptions toolCallingDefaults
                ? toolCallingDefaults.mutate()
                : ToolCallingChatOptions.builder();
        // 权限必须在调用前可见：Spring AI 只会在执行工具时才把 ToolContext 传给 call()，
        // 因此这里从已构造好的上下文中取权限快照来决定注册哪些工具。
        @SuppressWarnings("unchecked")
        List<String> permissions = toolContext.get(AiToolContextKeys.PERMISSIONS) instanceof List<?> list
                ? (List<String>) list : List.of();
        builder.toolCallbacks(List.of(toolRegistry.callbacks(permissions)));
        builder.toolContext(toolContext);
        return builder.build();
    }

    /**
     * 执行一轮模型调用；若该轮产生工具调用，则执行工具并递归下一轮。
     *
     * <p><b>为什么必须按轮区分正文：</b>模型在发起工具调用时，往往还会先输出一句
     * 「我这就去查…」式的前言正文（实测 DeepSeek 会输出例如
     * {@code I'll query the tender order statistics for August 2026}）。这类正文只属于
     * 中间过程，不能并入正式回答——否则最终答案会变成「前言 + 真正回答」直接粘连，
     * 既落库也展示给用户。</p>
     *
     * <p>因此这里按轮累积：只有<b>没有工具调用</b>的那一轮（即最终回答轮）才写入
     * {@code answer}。中间轮为了保持实时感仍会流式转发正文，但在进入下一轮前补发
     * {@code reset} 事件，让前端把这一轮已经显示的前言清掉。</p>
     */
    private Flux<ServerSentEvent<String>> runToolLoop(Prompt prompt, StringBuilder answer, int depth) {
        if (depth >= MAX_TOOL_ROUNDS) {
            log.warn("工具调用达到最大轮次 {}，停止循环", MAX_TOOL_ROUNDS);
            return Flux.empty();
        }
        return Flux.defer(() -> {
            // doOnNext 是串行调用的，普通 ArrayList 足够
            List<ChatResponse> collected = new ArrayList<>();
            // 本轮正文：仅当本轮不产生工具调用时，才并入最终回答
            StringBuilder roundText = new StringBuilder();

            Flux<ServerSentEvent<String>> streamed = chatModel.stream(prompt)
                    .doOnNext(collected::add)
                    .concatMap(chunk -> {
                        String text = textOf(chunk);
                        // 必须用 isEmpty 而不是 StringUtils.hasText：
                        // hasText 对纯空白返回 false，会把「只含空格或换行」的增量分片整个丢掉。
                        // 模型的分片经常会单独给出一个 " " 或 "\n"，一旦丢弃，回答里的空格与换行
                        // 就会缺失，Markdown 的标题/表格/列表结构会被压成一行。
                        if (text == null || text.isEmpty()) {
                            return Flux.empty();
                        }
                        roundText.append(text);
                        return Flux.just(event("delta", new ChatStreamEvents.Delta(text)));
                    });

            return streamed.concatWith(Flux.defer(() -> {
                List<AssistantMessage.ToolCall> toolCalls = mergeToolCalls(collected);
                if (toolCalls.isEmpty()) {
                    // 最终回答轮：本轮的正文才是要返回给用户并落库的内容
                    answer.append(roundText);
                    return Flux.empty();
                }
                log.debug("模型请求执行 {} 个工具调用（第 {} 轮），本轮前言正文 {} 字符不计入最终回答",
                        toolCalls.size(), depth + 1, roundText.length());

                // 先让前端丢弃本轮前言，再执行工具并进入下一轮，避免前言与最终回答粘连。
                // 工具执行是阻塞的，且 Tool 内部会通过 ToolContext 中的 sink 实时推送事件。
                return Flux.concat(
                        Flux.just(event("reset", new ChatStreamEvents.Reset())),
                        Flux.defer(() -> {
                            ToolExecutionResult result =
                                    toolCallingManager.executeToolCalls(prompt, aggregate(toolCalls));
                            Prompt next = new Prompt(result.conversationHistory(), prompt.getOptions());
                            return runToolLoop(next, answer, depth + 1);
                        }));
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
     *
     * <p><b>权限必须随上下文中传</b>（SYS-P-03）：工具线程上没有 SecurityContext、
     * 也没有 {@code CurrentUser} ThreadLocal，这是权限到达工具的唯一通路。
     * 同时 {@code AiToolRegistry} 也用这份快照裁剪注册集（SYS-P-12a）。</p>
     */
    private static Map<String, Object> buildToolContext(Long conversationId, Long userId, String userText,
                                                        CurrentUser.Principal principal,
                                                        ToolCallEventSink eventSink,
                                                        Sinks.Many<ChatStreamEvents.Proposal> proposalSink) {
        Map<String, Object> context = new HashMap<>();
        putIfNotNull(context, AiToolContextKeys.CONVERSATION_ID, conversationId);
        putIfNotNull(context, AiToolContextKeys.USER_ID, userId);
        putIfNotNull(context, AiToolContextKeys.TRACE_ID, TraceContext.currentTraceId());
        putIfNotNull(context, AiToolContextKeys.USER_TEXT, userText);
        context.put(AiToolContextKeys.EVENT_SINK, eventSink);
        context.put(AiToolContextKeys.PROPOSAL_SINK, proposalSink);
        if (principal != null) {
            putIfNotNull(context, AiToolContextKeys.USERNAME, principal.username());
            putIfNotNull(context, AiToolContextKeys.REAL_NAME, principal.realName());
            putIfNotNull(context, AiToolContextKeys.ORG_ID, principal.orgId());
            context.put(AiToolContextKeys.PERMISSIONS, principal.permissions());
            context.put(AiToolContextKeys.ROLES, principal.roles());
        } else {
            // 无上下文（例如集成测试直接调用 stream）：给出空列表而不是缺键，
            // 使 AiToolRegistry 走 fail-closed（只注册无需权限的工具）
            context.put(AiToolContextKeys.PERMISSIONS, List.of());
            context.put(AiToolContextKeys.ROLES, List.of());
        }
        return context;
    }

    /**
     * 取请求线程上的登录主体。
     *
     * <p>注意 {@code stream()} 在 Web 线程上被调用（Controller 内），
     * 此时 {@code CurrentUser} 仍然有效，因此在构造 ToolContext 的这一刻读取它是可靠的；
     * 之后工具循环切到 Reactor 线程，ThreadLocal 才会失效。</p>
     */
    private static CurrentUser.Principal principalContext() {
        return CurrentUser.get();
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
