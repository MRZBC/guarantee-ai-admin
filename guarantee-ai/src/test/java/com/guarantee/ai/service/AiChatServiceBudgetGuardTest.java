package com.guarantee.ai.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.guarantee.ai.config.AiConfigCatalog;
import com.guarantee.ai.config.AiConfigService;
import com.guarantee.ai.config.mapper.AiConfigItemMapper;
import com.guarantee.ai.dto.AiChatRequest;
import com.guarantee.ai.entity.AiConversation;
import com.guarantee.ai.entity.AiMessage;
import com.guarantee.ai.time.TimeSemanticParser;
import com.guarantee.ai.tool.AiToolRegistry;
import com.guarantee.common.security.CurrentUser;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
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
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 受约束执行的**单元测试**（REQ-BA-06 / REQ-BA-11，TEST-BA-02）。
 *
 * <p>不需要数据库、不需要模型 API Key：用可编排的假模型 + 真的
 * {@link DefaultToolCallingManager}（Spring AI 真实执行路径）驱动工具循环，
 * 覆盖三条必须守住的行为：</p>
 * <ol>
 *   <li><b>单轮超上限被截断且模型可继续</b>：一轮要 15 个工具，只有前 12 个执行，
 *       其余拿到的是一条可读说明（而不是错误），模型下一轮照常继续；</li>
 *   <li><b>软超时进入收口轮</b>：注入假时钟，让第二轮取数前就超过 60s —— 停止取数、
 *       摘掉工具再要一次回答（这正是"用户不会看到空气泡"的保证）；</li>
 *   <li><b>成本日志一条</b>：轮次 / 工具调用数 / 工具耗时 / token / 总耗时 / 是否触顶
 *       全部落进同一条结构化日志（AC-BA-08）。</li>
 * </ol>
 */
class AiChatServiceBudgetGuardTest {

    private static final String TOOL_NAME = "queryOrderSummary";
    private static final String FINAL_ANSWER = "（收口轮）依据已获取的数据：2026 年第二季度订单量高于第一季度。";
    private static final long CONVERSATION_ID = 7L;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /*
      T4-01 起预算来自 AI 配置，不再是 AiChatService 的常量。这里写死"改造前的值"
      （与 AiConfigCatalog 默认值一致，AC-CFG-08 由 AiConfigCatalogTest 与本类共同钉住），
      用来验证**缺省配置下行为与改造前逐项一致**。
    */
    private static final int MAX_CALLS_PER_ROUND = 12;
    private static final int MAX_ROUNDS = 4;

    /** 无任何显式配置的配置服务：快照 = 目录默认值（等价于改造前的硬编码常量）。 */
    private static AiConfigService defaultConfigService() {
        AiConfigItemMapper mapper = mock(AiConfigItemMapper.class);
        when(mapper.selectAll()).thenReturn(List.of());
        return new AiConfigService(mapper, new AiConfigCatalog());
    }

    /** 假时钟：测试自己推进，避免真的等 60 秒。 */
    private final AtomicLong fakeNanos = new AtomicLong();

    private AiConversationService conversationService;
    private ScriptedChatModel model;
    private RecordingTestTool tool;
    private AiChatService service;
    /** 提示词版本提供者：用于 stub 本轮"实际生效"的提示词版本（版本回溯断言）。 */
    private BusinessAssistantPrompt promptProvider;
    /** 配置快照来源：D4 断言"回填的正是本轮快照的版本号"。 */
    private AiConfigService configService;
    /** 版本回溯写入的出口（D4：失败轮也必须回填 prompt_version/config_version）。 */
    private com.guarantee.ai.mapper.AiConversationMapper conversationMapper;

    @BeforeEach
    void setUp() {
        CurrentUser.clear();
        conversationService = mock(AiConversationService.class);
        promptProvider = mock(BusinessAssistantPrompt.class);
        TimeSemanticParser timeSemanticParser = mock(TimeSemanticParser.class);
        AiToolRegistry toolRegistry = mock(AiToolRegistry.class);
        ProposalEventPublisher proposalEventPublisher = mock(ProposalEventPublisher.class);

        AiConversation conversation = new AiConversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setConversationNo("C202609300001");
        conversation.setTitle("受约束执行单测");
        when(conversationService.resolveOrCreate(any(), any(), anyString(), anyString()))
                .thenReturn(conversation);
        when(conversationService.recentMessages(anyLong(), anyInt())).thenReturn(List.of());
        when(conversationService.appendMessage(anyLong(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    AiMessage message = new AiMessage();
                    message.setId(99L);
                    message.setRole(invocation.getArgument(1));
                    message.setContent(invocation.getArgument(2));
                    return message;
                });
        when(promptProvider.build(any(Optional.class))).thenReturn("（测试用 System Prompt）");
        when(timeSemanticParser.parse(anyString())).thenReturn(Optional.empty());

        tool = new RecordingTestTool();
        // T4-01 起 AiChatService 用"显式传快照"的重载（工具裁剪与预算共用同一份配置）
        when(toolRegistry.callbacks(any(), any())).thenReturn(new ToolCallback[]{tool});

        model = new ScriptedChatModel(fakeNanos);
        DefaultToolCallingManager manager = new DefaultToolCallingManager(
                ObservationRegistry.NOOP,
                name -> null,
                new DefaultToolExecutionExceptionProcessor(false));

        service = new AiChatService(model, manager, conversationService, promptProvider,
                timeSemanticParser, toolRegistry, proposalEventPublisher,
                mock(ProposalClaimGuard.class),
                mock(DataSourceClaimGuard.class),
                mock(NumberClaimGuard.class),
                new ObjectMapper(), configService = defaultConfigService(),
                conversationMapper = mock(com.guarantee.ai.mapper.AiConversationMapper.class),
                "test-model", fakeNanos::get);
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    @Test
    @DisplayName("单轮调用超过 12：超出部分不执行，回灌可读说明，模型继续到下一轮")
    void overLimitToolCallsAreNotExecutedAndModelCanContinue() {
        // 两轮各要 15 个工具：超出 12 的部分只能拿到可读说明，且**每轮**都重新给满 12 个名额
        model.setToolCallsPerRound(15);
        model.setToolRequestRounds(2);
        ListAppender<ILoggingEvent> logs = attachCostAppender();
        try {
            List<ServerSentEvent<String>> events = chat();

            assertThat(tool.executions())
                    .as("每一轮只执行前 " + MAX_CALLS_PER_ROUND + " 个：2 轮 × 12 = 24"
                            + "（若是整轮封顶则只有 12，无上限则是 30）")
                    .isEqualTo(2 * MAX_CALLS_PER_ROUND);
            assertThat(model.modelCalls()).as("2 轮取数 + 1 轮最终回答").isEqualTo(3);
            assertThat(model.sawOverBudgetNotice())
                    .as("被跳过的调用必须回灌可读说明，模型才知道下一轮补查")
                    .isTrue();
            assertThat(answerOf(events))
                    .as("受约束执行也必须产出可读回答，不能是空气泡")
                    .contains(FINAL_ANSWER);
            assertThat(events).as("不得把这条路径报成错误").noneMatch(e -> "error".equals(e.event()));
            assertThat(events).as("流必须正常收尾").anyMatch(e -> "done".equals(e.event()));

            String costLine = onlyCostLine(logs);
            assertThat(costLine)
                    .as("成本日志字段固定（REQ-BA-11）")
                    .contains("conversationId=" + CONVERSATION_ID)
                    .contains("rounds=3")
                    .contains("toolCalls=24")
                    .contains("toolCostMs=")
                    .contains("inputTokens=")
                    .contains("outputTokens=")
                    .contains("totalCostMs=");
            // D3：不仅"有这些键"，还要**逐键对齐**（模板 13 个占位符 ↔ 13 个实参）
            assertCostLogTailAligned(costLine, "false", "none", "CHAT", "SUCCESS");
        } finally {
            detachCostAppender(logs);
        }
    }

    @Test
    @DisplayName("框架硬上限（单工具 40 次）触顶：转入收口轮给出可读回答，而不是把整轮报成 error")
    void frameworkToolCallLimitDegradesToFinalAnswerRound() {
        // 每轮都要 15 个工具且永不收口：第 3 轮取数时必然越过框架的"单工具 40 次"硬上限
        model.setToolCallsPerRound(15);
        model.setToolRequestRounds(MAX_ROUNDS);
        ListAppender<ILoggingEvent> logs = attachCostAppender();
        try {
            List<ServerSentEvent<String>> events = chat();

            assertThat(tool.executions())
                    .as("框架硬上限封顶在 40 次以内，且至少完成了两轮满额取数")
                    .isGreaterThanOrEqualTo(2 * MAX_CALLS_PER_ROUND)
                    .isLessThanOrEqualTo(40);
            assertThat(model.answerRounds())
                    .as("触顶后必须进入收口轮（摘掉工具），而不是直接结束")
                    .isEqualTo(1);
            assertThat(model.sawToolLimitInstruction())
                    .as("收口轮指令要说明是框架硬上限触顶")
                    .isTrue();
            assertThat(answerOf(events))
                    .as("触顶必须给出可读回答，绝不能只剩一个 error 事件")
                    .contains(FINAL_ANSWER);
            assertThat(events).as("不得把这条路径报成错误").noneMatch(e -> "error".equals(e.event()));
            assertThat(events).anyMatch(e -> "done".equals(e.event()));

            assertCostLogTailAligned(onlyCostLine(logs), "true", "tool_limit", "CHAT", "CAPPED");
        } finally {
            detachCostAppender(logs);
        }
    }

    @Test
    @DisplayName("软超时 60s：停止取数、摘掉工具进入收口轮，仍给出可读回答")
    void softTimeoutStopsFetchingAndEntersFinalAnswerRound() {
        // 每次模型调用推进 30s：第二轮刚发起工具调用时就超过 60s 预算
        model.setToolCallsPerRound(1);
        model.advanceNanosPerCall(Duration.ofSeconds(30).toNanos());
        ListAppender<ILoggingEvent> logs = attachCostAppender();
        try {
            List<ServerSentEvent<String>> events = chat();

            assertThat(tool.executions())
                    .as("超时后不再取数：第二轮请求的工具一个都不执行")
                    .isEqualTo(1);
            assertThat(model.answerRounds())
                    .as("只应有一次收口轮（摘掉工具）")
                    .isEqualTo(1);
            assertThat(model.sawSoftTimeoutInstruction())
                    .as("收口轮的指令必须说明原因是软超时，而不是轮次用尽")
                    .isTrue();
            assertThat(answerOf(events))
                    .as("停止取数后必须用已有数据作答")
                    .contains(FINAL_ANSWER);
            assertThat(events).noneMatch(e -> "error".equals(e.event()));
            assertThat(events).anyMatch(e -> "done".equals(e.event()));

            String costLine = onlyCostLine(logs);
            assertThat(costLine)
                    .contains("conversationId=" + CONVERSATION_ID)
                    .contains("rounds=3")
                    .contains("toolCalls=1");
            assertCostLogTailAligned(costLine, "true", "duration", "CHAT", "CAPPED");
        } finally {
            detachCostAppender(logs);
        }
    }

    // ==================================================================
    // D3 / D4：成本日志字段对齐 + 失败轮版本回溯
    // ==================================================================

    @Test
    @DisplayName("D3 失败路径：AI_TURN_COST 的 capped/capReason/source/outcome 四键逐键对齐（实参错位会被这条抓住）")
    void failingTurnCostLogFieldsAreAligned() {
        model.setFailure(new IllegalStateException("脚本化模型故障（D3 对齐用例）"));
        ListAppender<ILoggingEvent> logs = attachCostAppender();
        try {
            List<ServerSentEvent<String>> events = chat();

            assertThat(events).as("失败轮必须有 error 事件").anyMatch(e -> "error".equals(e.event()));

            String costLine = onlyCostLine(logs);
            // 修复前实测：capped=none capReason=CHAT source=ERROR outcome={}
            //（模板 13 个占位符只传 12 个实参，从 capped 起整体错位）
            assertCostLogTailAligned(costLine, "false", "none", "CHAT", "ERROR");
            assertThat(costLine).as("错位形态：占位符没被填掉").doesNotContain("outcome={}");
            assertThat(costLine)
                    .as("错位形态：把 capReason 的值塞进了 capped")
                    .doesNotContain("capped=none", "capped=rounds", "capped=duration", "capped=tool_limit");
        } finally {
            detachCostAppender(logs);
        }
    }

    @Test
    @DisplayName("D4 失败轮也回填 prompt_version/config_version（用本轮快照的版本号，不是上一轮的）")
    void failingTurnBackfillsVersionTrace() {
        model.setFailure(new IllegalStateException("脚本化模型故障（D4 版本回溯用例）"));
        when(promptProvider.activeVersionNo()).thenReturn(5);

        chat();

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Long> configVersion = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(conversationMapper).updateVersionTrace(eq(CONVERSATION_ID), eq(5), configVersion.capture());
        assertThat(configVersion.getValue())
                .as("config_version 必须是**本轮快照的版本号**（不是 null、也不是上一轮的值）")
                .isNotNull()
                .isEqualTo(configService.snapshot().version());
    }

    /**
     * D3 的核心断言：AI_TURN_COST 尾部四键必须**逐键**取到正确值。
     *
     * <p>为什么不能用 {@code contains("capped=none")} 这类单键断言：历史缺陷正是"键在、值错位"——
     * 模板 13 个占位符只传了 12 个实参，于是 {@code capped} 拿到了 capReason、{@code capReason}
     * 拿到了 source、{@code source} 拿到了 outcome、{@code outcome} 拿到了没被替换的 {@code {}}。
     * 单键 {@code contains} 全部照样通过（实测 {@code capped=duration} 就是这么过的），
     * 只有"按键取值 + 校验相邻顺序"才能抓住。</p>
     */
    private static void assertCostLogTailAligned(String costLine, String capped, String capReason,
                                                 String source, String outcome) {
        assertThat(costLine)
                .as("AI_TURN_COST 尾部四键必须按模板顺序逐键对齐：capped / capReason / source / outcome")
                .containsPattern("capped=" + capped + " capReason=" + capReason
                        + " source=" + source + " outcome=" + outcome);
        assertThat(costLine).as("占位符必须全部被替换").doesNotContain("{}");
    }

    // ==================================================================
    // 假模型 / 假工具
    // ==================================================================

    /**
     * 可编排的假模型：有工具就要工具，没有工具（收口轮）才给最终回答。
     *
     * <p>与真实模型的行为一致——这正是 {@code finalAnswerRound} 能"硬收口"的前提。</p>
     */
    private static final class ScriptedChatModel implements ChatModel {

        private final AtomicLong clock;
        private final AtomicInteger toolRequestRounds = new AtomicInteger();
        private final AtomicInteger answerRounds = new AtomicInteger();
        private final AtomicBoolean sawOverBudgetNotice = new AtomicBoolean();
        private final AtomicBoolean sawSoftTimeoutInstruction = new AtomicBoolean();
        private final AtomicBoolean sawToolLimitInstruction = new AtomicBoolean();

        private volatile int toolCallsPerRound = 1;
        private volatile int maxToolRequestRounds = MAX_ROUNDS;
        private volatile long advanceNanosPerCall;
        /** 置非空即模拟"模型流式失败"，用于错误路径（成本日志 outcome=ERROR 与版本回溯）。 */
        private volatile RuntimeException failure;

        private ScriptedChatModel(AtomicLong clock) {
            this.clock = clock;
        }

        void setFailure(RuntimeException failure) {
            this.failure = failure;
        }

        void setToolCallsPerRound(int count) {
            this.toolCallsPerRound = count;
        }

        /** 只在前 N 个"有工具"的轮次里要工具，之后（即使还有工具）直接给最终回答。 */
        void setToolRequestRounds(int rounds) {
            this.maxToolRequestRounds = rounds;
        }

        void advanceNanosPerCall(long nanos) {
            this.advanceNanosPerCall = nanos;
        }

        int modelCalls() {
            return toolRequestRounds.get() + answerRounds.get();
        }

        int answerRounds() {
            return answerRounds.get();
        }

        boolean sawOverBudgetNotice() {
            return sawOverBudgetNotice.get();
        }

        boolean sawSoftTimeoutInstruction() {
            return sawSoftTimeoutInstruction.get();
        }

        boolean sawToolLimitInstruction() {
            return sawToolLimitInstruction.get();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return stream(prompt).blockLast();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            if (failure != null) {
                return Flux.error(failure);
            }
            boolean hasTools = hasTools(prompt);
            boolean requestTools = false;
            if (hasTools) {
                requestTools = toolRequestRounds.incrementAndGet() <= maxToolRequestRounds;
            } else {
                answerRounds.incrementAndGet();
            }
            if (advanceNanosPerCall > 0) {
                clock.addAndGet(advanceNanosPerCall);
            }
            inspect(prompt, hasTools);
            return Flux.just(requestTools ? toolCallChunk() : textChunk(FINAL_ANSWER));
        }

        private void inspect(Prompt prompt, boolean hasTools) {
            for (Message message : prompt.getInstructions()) {
                if (message instanceof ToolResponseMessage toolResponses) {
                    for (ToolResponseMessage.ToolResponse response : toolResponses.getResponses()) {
                        if (response.responseData() != null
                                && response.responseData().contains("本轮工具调用过多")) {
                            sawOverBudgetNotice.set(true);
                        }
                    }
                } else if (!hasTools && message.getText() != null) {
                    if (message.getText().contains("软超时预算")) {
                        sawSoftTimeoutInstruction.set(true);
                    }
                    if (message.getText().contains("单次分析上限")) {
                        sawToolLimitInstruction.set(true);
                    }
                }
            }
        }

        private static boolean hasTools(Prompt prompt) {
            return prompt.getOptions() instanceof ToolCallingChatOptions options
                    && options.getToolCallbacks() != null
                    && !options.getToolCallbacks().isEmpty();
        }

        private ChatResponse toolCallChunk() {
            List<AssistantMessage.ToolCall> calls = new ArrayList<>(toolCallsPerRound);
            for (int i = 0; i < toolCallsPerRound; i++) {
                calls.add(new AssistantMessage.ToolCall(
                        "call_" + toolRequestRounds.get() + "_" + i, "function", TOOL_NAME,
                        "{\"orderType\":\"TENDER\",\"startDate\":\"2026-04-01\",\"endDate\":\"2026-06-30\"}"));
            }
            AssistantMessage message = AssistantMessage.builder()
                    .content("")
                    .toolCalls(calls)
                    .build();
            return new ChatResponse(List.of(new Generation(message,
                    ChatGenerationMetadata.builder().finishReason("tool_calls").build())), usageMetadata());
        }

        private static ChatResponse textChunk(String content) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(content),
                    ChatGenerationMetadata.builder().finishReason("stop").build())), usageMetadata());
        }

        /** 每轮固定上报 (10, 4) token，让成本日志可断言。 */
        private static ChatResponseMetadata usageMetadata() {
            return ChatResponseMetadata.builder()
                    .usage(new DefaultUsage(10, 4))
                    .build();
        }
    }

    /** 记账用的假工具：真实执行路径会调用它，因此执行次数就是"实际取数次数"。 */
    private static final class RecordingTestTool implements ToolCallback {

        private static final ToolDefinition DEFINITION = DefaultToolDefinition.builder()
                .name(TOOL_NAME)
                .description("测试用订单汇总")
                .inputSchema("{\"type\":\"object\"}")
                .build();

        private final AtomicInteger executions = new AtomicInteger();

        int executions() {
            return executions.get();
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return DEFINITION;
        }

        @Override
        public String call(String toolInput) {
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            executions.incrementAndGet();
            return "{\"orderCount\":5427,\"meta\":{\"denied\":false,\"dataSource\":\"测试口径\"}}";
        }
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private List<ServerSentEvent<String>> chat() {
        AiChatRequest request = new AiChatRequest();
        request.setMessage("请分析 2026 年第二季度投标订单的主要变化");
        List<ServerSentEvent<String>> events = service.stream(1L, request)
                .collectList()
                .block(Duration.ofSeconds(20));
        assertThat(events).isNotNull().isNotEmpty();
        return events;
    }

    /** 把所有 delta 的 content 拼回最终正文。 */
    @SuppressWarnings("unchecked")
    private String answerOf(List<ServerSentEvent<String>> events) {
        StringBuilder answer = new StringBuilder();
        for (ServerSentEvent<String> event : events) {
            if (!"delta".equals(event.event())) {
                continue;
            }
            Map<String, Object> payload = OBJECT_MAPPER.readValue(event.data(), Map.class);
            Object content = payload.get("content");
            if (content != null) {
                answer.append(content);
            }
        }
        return answer.toString();
    }

    private ListAppender<ILoggingEvent> attachCostAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        costLogger().addAppender(appender);
        return appender;
    }

    private void detachCostAppender(ListAppender<ILoggingEvent> appender) {
        costLogger().detachAppender(appender);
    }

    private static Logger costLogger() {
        return (Logger) LoggerFactory.getLogger(AiChatService.class);
    }

    private static String onlyCostLine(ListAppender<ILoggingEvent> appender) {
        List<String> lines = new ArrayList<>();
        for (ILoggingEvent event : appender.list) {
            String message = event.getFormattedMessage();
            if (message != null && message.contains(AiChatService.COST_LOG_TAG)) {
                lines.add(message);
            }
        }
        assertThat(lines).as("一次请求只写一条成本日志").hasSize(1);
        return lines.get(0);
    }
}
