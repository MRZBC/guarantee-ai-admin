package com.guarantee.web.ai;

import com.guarantee.ai.config.AiConfigCatalog;
import com.guarantee.ai.config.AiConfigService;
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
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TEST-CFG-06 的**集成证据**：改 AI 配置后**不重启**，下一个请求的模型调用参数已经变了。
 *
 * <p>REQ §8 把 TEST-CFG-06 定义为 IT，可观测点是"模型调用参数"（AC-CFG-01 要求
 * "不重启、不重新打包，下一个请求生效（日志/落库可证）"）。本类用 Stub ChatModel 记录
 * 每次调用真正拿到的 {@link ToolCallingChatOptions}，因此断言的是**实际发给模型的参数**，
 * 而不是"配置读出来是多少"：</p>
 * <ol>
 *   <li>改 {@code model.temperature} → 下一请求的 options.temperature 变了；</li>
 *   <li>改 {@code model.name} → 下一请求的 options.model 变了；</li>
 *   <li>关 {@code tools.order.enabled} → 下一请求注册给模型的工具里没有
 *       {@code queryOrderSummary}（其它组不受影响）；</li>
 *   <li>回答级回溯（REQ-CFG-05）：落库的 {@code ai_conversation.config_version}
 *       等于本轮生效的快照版本号。</li>
 * </ol>
 *
 * <p><b>数据纪律</b>：本测试会真的写 {@code ai_config_item}（共享开发库）。因此
 * {@code @BeforeEach}/{@code @AfterEach} 都把涉及的键 {@code reset} 回默认值，并在测试内
 * 断言"改前改后读到的都是默认值"，绝不留污染（本仓库有过"测试把险种留在停用状态"的事故）。</p>
 */
@SpringBootTest(
        classes = {GuaranteeAiAdminApplication.class, AiConfigWiringIT.StubModelConfig.class},
        properties = {"guarantee.data-init.enabled=false"})
class AiConfigWiringIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class StubModelConfig {

        @Bean
        @Primary
        ChatModel capturingChatModel() {
            return new CapturingChatModel();
        }
    }

    /** 本测试会改动的配置键；前后各复位一次。 */
    private static final List<String> TOUCHED_KEYS = List.of(
            AiConfigCatalog.MODEL_TEMPERATURE,
            AiConfigCatalog.MODEL_NAME,
            AiConfigCatalog.TOOLS_ORDER_ENABLED);

    @Autowired
    private AiChatService aiChatService;

    @Autowired
    private AiConfigService aiConfigService;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ChatModel chatModel;

    @BeforeEach
    void setUp() {
        resetTouchedKeys();
        CurrentUser.set(new CurrentUser.Principal(adminUserId(), "admin", "超级管理员",
                List.of(Roles.ADMIN), userService.listPermissionCodesByUserId(adminUserId())));
    }

    @AfterEach
    void tearDown() {
        resetTouchedKeys();
        CurrentUser.clear();
    }

    @Test
    @DisplayName("缺省（空表）：模型参数与工具集与改造前一致，且 config_version 落库")
    void defaultsAreAppliedAndTraced() {
        assertThat(aiConfigService.get(AiConfigCatalog.MODEL_TEMPERATURE))
                .as("测试前后都必须读得到默认值（不留污染）").isEqualTo("0.2");
        assertThat(aiConfigService.snapshot().isOverridden(AiConfigCatalog.MODEL_TEMPERATURE))
                .as("默认值 = 未被显式配置").isFalse();

        Turn turn = ask();

        assertThat(turn.options().getTemperature())
                .as("未显式配置时不覆盖 starter 的 0.2").isEqualTo(0.2);
        assertThat(toolNames(turn.options()))
                .as("缺省开关全 true：订单工具在位")
                .contains("queryOrderSummary", "queryOrg", "queryBusinessKnowledge");

        assertThat(configVersionOf(turn.conversationId()))
                .as("回答级回溯：落库的配置版本 = 本轮快照版本（REQ-CFG-05）")
                .isEqualTo(aiConfigService.version());
    }

    @Test
    @DisplayName("TEST-CFG-06：改 temperature/model.name/工具开关后，下一请求的模型参数与工具集已变")
    void changesTakeEffectOnNextRequestWithoutRestart() {
        Turn before = ask();
        assertThat(before.options().getTemperature()).isEqualTo(0.2);
        assertThat(toolNames(before.options())).contains("queryOrderSummary");

        // 通过真实服务改配置（等价于页面上点保存）：不重启进程
        aiConfigService.update(AiConfigCatalog.MODEL_TEMPERATURE, "0.7", "1");
        aiConfigService.update(AiConfigCatalog.MODEL_NAME, "deepseek-reasoner", "1");
        aiConfigService.update(AiConfigCatalog.TOOLS_ORDER_ENABLED, "false", "1");

        Turn after = ask();

        assertThat(after.options().getTemperature())
                .as("下一个请求的模型调用参数已变（不重启、不重新打包）").isEqualTo(0.7);
        assertThat(after.options().getModel())
                .as("模型名必须真的随请求下发，而不是只改落库值").isEqualTo("deepseek-reasoner");
        assertThat(toolNames(after.options()))
                .as("关掉的组不再注册给模型")
                .doesNotContain("queryOrderSummary")
                .as("其它组不受影响")
                .contains("queryOrg", "queryBusinessKnowledge");

        assertThat(configVersionOf(after.conversationId()))
                .as("回答级回溯记录的是改后的快照版本")
                .isEqualTo(aiConfigService.version())
                .isGreaterThan(configVersionOf(before.conversationId()));
    }

    @Test
    @DisplayName("复位后恢复缺省：reset 让下一请求立即回到改造前参数（回滚路径）")
    void resetRestoresDefaultsOnNextRequest() {
        aiConfigService.update(AiConfigCatalog.MODEL_TEMPERATURE, "0.9", "1");
        assertThat(ask().options().getTemperature()).isEqualTo(0.9);

        aiConfigService.reset(AiConfigCatalog.MODEL_TEMPERATURE, "1");

        assertThat(aiConfigService.get(AiConfigCatalog.MODEL_TEMPERATURE)).isEqualTo("0.2");
        assertThat(aiConfigService.snapshot().isOverridden(AiConfigCatalog.MODEL_TEMPERATURE)).isFalse();
        assertThat(ask().options().getTemperature())
                .as("回滚同样立即生效").isEqualTo(0.2);
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private record Turn(long conversationId, ToolCallingChatOptions options) {
    }

    private Turn ask() {
        AiChatRequest request = new AiChatRequest();
        request.setMessage("2026 年第二季度投标订单有多少？");
        List<ServerSentEvent<String>> events = aiChatService.stream(adminUserId(), request)
                .collectList().block(Duration.ofSeconds(60));
        assertThat(events).isNotNull().isNotEmpty();

        long conversationId = ((Number) firstData(events, "meta").get("conversationId")).longValue();
        return new Turn(conversationId, ((CapturingChatModel) chatModel).lastOptions());
    }

    private static List<String> toolNames(ToolCallingChatOptions options) {
        return options.getToolCallbacks().stream()
                .map(ToolCallback::getToolDefinition)
                .map(definition -> definition.name())
                .toList();
    }

    private Long configVersionOf(long conversationId) {
        return jdbcTemplate.queryForObject(
                "SELECT config_version FROM ai_conversation WHERE id = ?", Long.class, conversationId);
    }

    private void resetTouchedKeys() {
        for (String key : TOUCHED_KEYS) {
            aiConfigService.reset(key, "1");
        }
    }

    private Long adminUserId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = 'admin'", Long.class);
    }

    private Map<String, Object> firstData(List<ServerSentEvent<String>> events, String eventName) {
        String payload = events.stream()
                .filter(event -> eventName.equals(event.event()))
                .map(ServerSentEvent::data)
                .findFirst()
                .orElseThrow(() -> new AssertionError("未收到 SSE 事件: " + eventName));
        return objectMapper.readValue(payload, Map.class);
    }

    /**
     * 记录每次模型调用真正拿到的 options。
     *
     * <p>{@code getOptions()} 模拟 starter/yml 的默认值（temperature=0.2 / model=deepseek-chat），
     * 用来验证"未显式配置时不覆盖"（AC-CFG-08）。</p>
     */
    static final class CapturingChatModel implements ChatModel {

        private final List<ToolCallingChatOptions> seen = new CopyOnWriteArrayList<>();

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder()
                    .temperature(0.2)
                    .model("deepseek-chat")
                    .build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return stream(prompt).blockLast();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            if (prompt.getOptions() instanceof ToolCallingChatOptions options) {
                seen.add(options);
            }
            ChatGenerationMetadata metadata = ChatGenerationMetadata.builder()
                    .finishReason("stop")
                    .build();
            return Flux.just(new ChatResponse(List.of(
                    new Generation(new AssistantMessage("这是配置接线 IT 的测试回答。"), metadata))));
        }

        ToolCallingChatOptions lastOptions() {
            return seen.get(seen.size() - 1);
        }
    }
}
