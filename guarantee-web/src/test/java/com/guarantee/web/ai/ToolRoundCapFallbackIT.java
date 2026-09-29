package com.guarantee.web.ai;

import com.guarantee.ai.dto.AiChatRequest;
import com.guarantee.ai.service.AiChatService;
import com.guarantee.common.security.CurrentUser;
import com.guarantee.common.security.Roles;
import com.guarantee.system.service.UserService;
import com.guarantee.web.GuaranteeAiAdminApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具轮次用尽 / 模型空回答时的**兜底**集成测试（不需要真实 LLM API Key）。
 *
 * <p><b>现场事故（2026-09-29 22:25）</b>：用户问「请分析 2026 年第二季度投标订单，和第一季度比较，
 * 并从区域、机构、险种三个维度找出主要变化」。真实 SSE 实测：模型把 4 轮工具调用**全部**用在取数上
 * （36 次只读工具调用，含逐区域、逐机构展开），第 5 轮被 {@code MAX_TOOL_ROUNDS} 挡掉后
 * {@code Flux.empty()} 收场 → 正文一个字都没有 → 前端只剩一个空气泡。
 * 用户原话：「为何无法正确响应，就算有bug或者做不了，也应该兜底一下吧」。</p>
 *
 * <p>本测试用可编排的假模型复现这条路径，断言两件事：</p>
 * <ol>
 *   <li><b>轮次用尽不再等于"不回答"</b>：收尾轮会**摘掉工具**再要一次回答，
 *       模型只能用已查到的数据作答（{@link ScriptedChatModel} 在"没有工具"时才吐答案，
 *       这与真实模型的行为一致）；</li>
 *   <li><b>真的没有正文时必须有兜底文案</b>：用户看到"这次没成 + 为什么 + 下一步"，
 *       而不是空气泡。</li>
 * </ol>
 *
 * <p>需要可用的 MySQL（见 application.yml）。运行方式：{@code mvn verify}。</p>
 */
@SpringBootTest(
        classes = {GuaranteeAiAdminApplication.class, ToolRoundCapFallbackIT.StubChatModelConfig.class},
        properties = {"guarantee.data-init.enabled=false"})
class ToolRoundCapFallbackIT {

    /** 收尾轮的正文：只要模型拿到"没有工具"的那一轮，就返回它。 */
    static final String FINAL_ANSWER = "（收尾轮）依据已获取的数据：2026 年第二季度投标订单量高于第一季度。";

    /** 与提示词一致的工具调用：真实存在、入参合法，会打到真实数据库。 */
    private static final String TOOL_NAME = "queryOrderSummary";
    private static final String TOOL_ARGUMENTS =
            "{\"orderType\":\"TENDER\",\"startDate\":\"2026-04-01\",\"endDate\":\"2026-06-30\"}";

    @TestConfiguration(proxyBeanMethods = false)
    static class StubChatModelConfig {

        @Bean
        @Primary
        ScriptedChatModel scriptedChatModel() {
            return new ScriptedChatModel();
        }
    }

    /**
     * 可编排的假模型：模拟"一直在调工具、就是不收口"的真实行为。
     *
     * <p>三种模式对应三条要守的路径：轮次用尽后收口、模型彻底空回答、模型连收尾轮都想调工具。</p>
     */
    static class ScriptedChatModel implements ChatModel {

        enum Mode {
            /** 有工具就要求调用工具；**没有工具**时才给出最终回答（真实模型的行为） */
            TOOL_UNTIL_NO_TOOLS,
            /** 任何一轮都返回空正文、且不调用工具 */
            ALWAYS_EMPTY,
            /** 连"被摘掉工具"的收尾轮也要工具（模型不听话的极端情形） */
            TOOL_CALL_ALWAYS
        }

        private volatile Mode mode = Mode.TOOL_UNTIL_NO_TOOLS;
        /** 拿到工具的调用次数（= 工具轮次） */
        private final AtomicInteger toolRounds = new AtomicInteger();
        /** 没有工具的调用次数（= 收尾轮） */
        private final AtomicInteger noToolRounds = new AtomicInteger();

        void setMode(Mode mode) {
            this.mode = mode;
        }

        /** 假模型是单例 Bean，跨用例共享：计数必须在每个用例开始时清零，否则断言会互相污染。 */
        void reset() {
            toolRounds.set(0);
            noToolRounds.set(0);
        }

        int toolRounds() {
            return toolRounds.get();
        }

        int noToolRounds() {
            return noToolRounds.get();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return stream(prompt).blockLast();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            boolean hasTools = hasTools(prompt);
            if (hasTools) {
                toolRounds.incrementAndGet();
            } else {
                noToolRounds.incrementAndGet();
            }
            return switch (mode) {
                case ALWAYS_EMPTY -> Flux.just(text(""));
                case TOOL_CALL_ALWAYS -> Flux.just(toolCall());
                case TOOL_UNTIL_NO_TOOLS -> hasTools
                        ? Flux.just(toolCall())
                        : Flux.just(text(FINAL_ANSWER));
            };
        }

        /** 模型看到的工具集是否非空——收尾轮必须为空，这是"硬收口"的关键。 */
        private static boolean hasTools(Prompt prompt) {
            return prompt.getOptions() instanceof ToolCallingChatOptions options
                    && options.getToolCallbacks() != null
                    && !options.getToolCallbacks().isEmpty();
        }

        private ChatResponse toolCall() {
            AssistantMessage message = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            "call_loop_" + toolRounds.get(), "function", TOOL_NAME, TOOL_ARGUMENTS)))
                    .build();
            return new ChatResponse(List.of(new Generation(message,
                    ChatGenerationMetadata.builder().finishReason("tool_calls").build())));
        }

        private static ChatResponse text(String content) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(content),
                    ChatGenerationMetadata.builder().finishReason("stop").build())));
        }
    }

    private static final String QUESTION = "请分析 2026 年第二季度投标订单，和第一季度比较，并从区域、机构、险种三个维度找出主要变化";

    @Autowired
    private AiChatService aiChatService;

    @Autowired
    private ScriptedChatModel chatModel;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /** {@code AiChatService} 在请求线程上读 {@code CurrentUser}：不给主体就拿不到权限快照。 */
    @BeforeEach
    void setUpPrincipal() {
        Long userId = adminUserId();
        CurrentUser.set(new CurrentUser.Principal(userId, "admin", "超级管理员", List.of(Roles.ADMIN),
                userService.listPermissionCodesByUserId(userId)));
        chatModel.setMode(ScriptedChatModel.Mode.TOOL_UNTIL_NO_TOOLS);
        chatModel.reset();
    }

    @AfterEach
    void clearPrincipal() {
        CurrentUser.clear();
    }

    @Test
    @DisplayName("工具轮次用尽：收尾轮摘掉工具再要一次回答，用户拿到的是结论而不是空气泡")
    void exhaustedToolRoundsStillProduceAnAnswer() {
        List<ServerSentEvent<String>> events = chat(QUESTION);

        ScriptedChatModel stub = chatModel;
        assertThat(stub.toolRounds())
                .as("必须真的把工具轮次用满（上限 4 轮）才会走到收尾轮")
                .isGreaterThanOrEqualTo(2);
        assertThat(stub.noToolRounds())
                .as("收尾轮只能有一次，否则就是又一次死循环")
                .isEqualTo(1);

        String answer = answerOf(events);
        assertThat(answer)
                .as("轮次用尽不等于不回答：必须把模型在收尾轮给出的结论交给用户")
                .contains(FINAL_ANSWER);
        assertThat(events).as("不得把这条路径报成错误").noneMatch(e -> "error".equals(e.event()));
        assertThat(events).as("流必须正常收尾").anyMatch(e -> "done".equals(e.event()));

        // 落库 = 用户所见：刷新历史后不能又变成空气泡
        assertThat(lastAssistantContent(conversationIdOf(events)))
                .as("兜底/收尾后的正文必须落库")
                .contains(FINAL_ANSWER);
    }

    @Test
    @DisplayName("模型整轮空回答：给出可读的兜底说明，而不是留一个空气泡")
    void emptyAnswerFallsBackToReadableNotice() {
        chatModel.setMode(ScriptedChatModel.Mode.ALWAYS_EMPTY);

        List<ServerSentEvent<String>> events = chat(QUESTION);

        String answer = answerOf(events);
        assertThat(answer)
                .as("空回答必须兜底成可读文案")
                .contains("抱歉，这次没能给出结论")
                .contains("模型这一轮没有返回任何内容");
        assertThat(answer)
                .as("兜底文案不得带口径行——口径只能逐字来自工具返回值，这里没有工具返回")
                .doesNotContain("口径：");
        assertThat(events).anyMatch(e -> "done".equals(e.event()));

        assertThat(lastAssistantContent(conversationIdOf(events)))
                .as("兜底文案同样要落库，否则刷新后又是一片空白")
                .contains("抱歉，这次没能给出结论");
    }

    @Test
    @DisplayName("连收尾轮也在要工具（模型不听话）：仍按「轮次用尽」给出兜底原因")
    void stubbornToolCallingStillEndsWithNotice() {
        chatModel.setMode(ScriptedChatModel.Mode.TOOL_CALL_ALWAYS);

        List<ServerSentEvent<String>> events = chat(QUESTION);

        ScriptedChatModel stub = chatModel;
        assertThat(stub.noToolRounds())
                .as("收尾轮只发一次：模型仍要工具也不能再进循环")
                .isEqualTo(1);

        assertThat(answerOf(events))
                .as("兜底原因要如实说是轮次用尽，而不是含糊的「没有返回内容」")
                .contains("工具调用都用在了取数上")
                .contains("把问题拆小");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private List<ServerSentEvent<String>> chat(String question) {
        AiChatRequest request = new AiChatRequest();
        request.setMessage(question);
        List<ServerSentEvent<String>> events = aiChatService.stream(adminUserId(), request)
                .collectList()
                .block(Duration.ofSeconds(90));
        assertThat(events).isNotNull().isNotEmpty();
        return events;
    }

    private Long adminUserId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = 'admin'", Long.class);
    }

    /** 把所有 delta 的 content 拼回最终正文。 */
    private String answerOf(List<ServerSentEvent<String>> events) {
        return events.stream()
                .filter(e -> "delta".equals(e.event()))
                .map(ServerSentEvent::data)
                .reduce("", (acc, data) -> {
                    Object content = parse(data).get("content");
                    return acc + (content == null ? "" : content);
                });
    }

    private Long conversationIdOf(List<ServerSentEvent<String>> events) {
        String payload = events.stream()
                .filter(e -> "meta".equals(e.event()))
                .map(ServerSentEvent::data)
                .findFirst()
                .orElseThrow(() -> new AssertionError("未收到 meta 事件"));
        return ((Number) parse(payload).get("conversationId")).longValue();
    }

    private String lastAssistantContent(Long conversationId) {
        return jdbcTemplate.queryForObject(
                "SELECT content FROM ai_message WHERE conversation_id = ? AND role = 'ASSISTANT' "
                        + "ORDER BY id DESC LIMIT 1", String.class, conversationId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) {
        return objectMapper.readValue(json, Map.class);
    }
}
