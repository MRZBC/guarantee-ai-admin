package com.guarantee.ai.service;

import com.guarantee.ai.config.AiConfigCatalog;
import com.guarantee.ai.config.AiConfigItem;
import com.guarantee.ai.config.AiConfigService;
import com.guarantee.ai.config.AiConfigSnapshot;
import com.guarantee.ai.config.AiConfigWarmUp;
import com.guarantee.ai.config.mapper.AiConfigItemMapper;
import com.guarantee.ai.dto.AiChatRequest;
import com.guarantee.ai.entity.AiConversation;
import com.guarantee.ai.entity.AiMessage;
import com.guarantee.ai.mapper.AiConversationMapper;
import com.guarantee.ai.time.TimeSemanticParser;
import com.guarantee.ai.tool.AiToolRegistry;
import com.guarantee.ai.tool.BoundedToolCallback;
import com.guarantee.common.security.CurrentUser;
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
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4-01 配置接线单元测试（REQ-CFG-06 / REQ-CFG-07，AC-CFG-01/07/08；TEST-CFG-06）。
 *
 * <p>三组断言，对应三条验收：</p>
 * <ol>
 *   <li><b>AC-CFG-08 缺省一致性</b>：空表 + 默认值时，运行期生效的每个数字都与改造前的硬编码
 *       逐项一致（历史 20 / 轮次 4 / 单轮 12 / 软超时 60s / 16KB / 工具 10s / 温度 0.2），
 *       且工具开关全 true；</li>
 *   <li><b>TEST-CFG-06 生效性</b>：同一个 {@code AiChatService} 实例（不重启）改配置后，
 *       **下一次请求**的入参已经变了（历史条数 / 温度 / 工具开关）；</li>
 *   <li><b>一轮一份快照</b>（REQ-CFG-06）：一次请求只做一次版本比对，且同一个快照实例
 *       同时用于工具裁剪与预算。</li>
 * </ol>
 *
 * <p>不需要数据库、不需要 API Key：配置源是"内存版表"（Mockito 的 Mapper + 可变行集合），
 * 但走的是**真实的** {@link AiConfigService}（含校验、版本比较、快照构建）。</p>
 */
class AiConfigWiringTest {

    private static final long CONVERSATION_ID = 11L;
    private static final long USER_ID = 1L;

    private FakeConfigStore configStore;
    private AiConfigService configService;
    private AiConversationService conversationService;
    private AiToolRegistry toolRegistry;
    private AiConversationMapper conversationMapper;
    private CapturingChatModel model;
    private AiChatService service;

    @BeforeEach
    void setUp() {
        CurrentUser.clear();
        configStore = new FakeConfigStore();
        // 用 spy 计数"每次请求只做一次版本比对"；实现仍是真实的 AiConfigService
        configService = org.mockito.Mockito.spy(configStore.service());

        conversationService = mock(AiConversationService.class);
        BusinessAssistantPrompt promptProvider = mock(BusinessAssistantPrompt.class);
        TimeSemanticParser timeSemanticParser = mock(TimeSemanticParser.class);
        toolRegistry = mock(AiToolRegistry.class);
        ProposalEventPublisher proposalEventPublisher = mock(ProposalEventPublisher.class);

        AiConversation conversation = new AiConversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setConversationNo("C202609300011");
        conversation.setTitle("配置接线单测");
        when(conversationService.resolveOrCreate(any(), any(), anyString(), anyString()))
                .thenReturn(conversation);
        when(conversationService.recentMessages(anyLong(), anyInt())).thenReturn(List.of());
        when(conversationService.appendMessage(anyLong(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    AiMessage message = new AiMessage();
                    message.setId(88L);
                    message.setRole(invocation.getArgument(1));
                    message.setContent(invocation.getArgument(2));
                    return message;
                });
        when(promptProvider.build(any(Optional.class))).thenReturn("（测试用 System Prompt）");
        when(timeSemanticParser.parse(anyString())).thenReturn(Optional.empty());
        when(toolRegistry.callbacks(any(), any())).thenReturn(new ToolCallback[0]);

        model = new CapturingChatModel();
        conversationMapper = mock(AiConversationMapper.class);
        service = new AiChatService(model, mock(org.springframework.ai.model.tool.ToolCallingManager.class),
                conversationService, promptProvider, timeSemanticParser, toolRegistry,
                proposalEventPublisher, mock(ProposalClaimGuard.class),
                mock(DataSourceClaimGuard.class), mock(NumberClaimGuard.class),
                new ObjectMapper(), configService, conversationMapper,
                "test-model", System::nanoTime);
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    // ==================================================================
    // AC-CFG-08：缺省时行为与改造前逐项一致
    // ==================================================================

    @Test
    @DisplayName("AC-CFG-08 缺省一致性：20 / 4 / 12 / 60000 / 16384 / 10000 / 0.2 逐项等于改造前的值")
    void defaultsMatchPreChangeBehaviour() {
        AiConfigSnapshot snapshot = configService.snapshot();

        assertThat(snapshot.getInt(AiConfigCatalog.BUDGET_HISTORY_LIMIT))
                .as("改造前 HISTORY_LIMIT=20").isEqualTo(20);
        assertThat(snapshot.getInt(AiConfigCatalog.BUDGET_MAX_ROUNDS))
                .as("改造前 MAX_TOOL_ROUNDS=4").isEqualTo(4);
        assertThat(snapshot.getInt(AiConfigCatalog.BUDGET_MAX_CALLS_PER_ROUND))
                .as("改造前 MAX_TOOL_CALLS_PER_ROUND=12").isEqualTo(12);
        assertThat(snapshot.getLong(AiConfigCatalog.BUDGET_SOFT_TIMEOUT_MS))
                .as("改造前 SOFT_TIMEOUT_MS=60000").isEqualTo(60_000L);
        assertThat(snapshot.getInt(AiConfigCatalog.BUDGET_TOOL_RESULT_BYTES))
                .as("改造前工具结果上限 16KB").isEqualTo(16 * 1024);
        assertThat(snapshot.getInt(AiConfigCatalog.BUDGET_TOOL_TIMEOUT_MS))
                .as("改造前工具超时 10s").isEqualTo(10_000);
        assertThat(snapshot.get(AiConfigCatalog.MODEL_TEMPERATURE))
                .as("改造前 yml temperature=0.2").isEqualTo("0.2");
        assertThat(snapshot.get(AiConfigCatalog.MODEL_NAME)).isEqualTo("deepseek-chat");

        // 常量与配置默认值必须相等：同值定义两处，用断言钉住，防漂移
        assertThat(BoundedToolCallback.MAX_RESULT_BYTES)
                .isEqualTo(snapshot.getInt(AiConfigCatalog.BUDGET_TOOL_RESULT_BYTES));
        assertThat(BoundedToolCallback.TOOL_TIMEOUT_MS)
                .isEqualTo(snapshot.getLong(AiConfigCatalog.BUDGET_TOOL_TIMEOUT_MS));

        // 工具组开关缺省全 true（⇒ 注册集与改造前一致）
        for (String key : List.of(AiConfigCatalog.TOOLS_ORDER_ENABLED,
                AiConfigCatalog.TOOLS_ANALYSIS_ENABLED, AiConfigCatalog.TOOLS_SYSTEM_ENABLED,
                AiConfigCatalog.TOOLS_AUDIT_ENABLED, AiConfigCatalog.TOOLS_PROPOSAL_ENABLED,
                AiConfigCatalog.KNOWLEDGE_ENABLED)) {
            assertThat(snapshot.getBoolean(key)).as("%s 缺省应为 true", key).isTrue();
        }

        // 模型超时/重试：缺省无值 = 沿用 starter（改造前未显式配置）
        assertThat(snapshot.findInt(AiConfigCatalog.MODEL_TIMEOUT)).isEmpty();
        assertThat(snapshot.findInt(AiConfigCatalog.MODEL_MAX_RETRIES)).isEmpty();
        // 未显式配置 ⇒ 不覆盖 starter/yml
        assertThat(snapshot.isOverridden(AiConfigCatalog.MODEL_TEMPERATURE)).isFalse();
        assertThat(snapshot.isOverridden(AiConfigCatalog.MODEL_NAME)).isFalse();
    }

    @Test
    @DisplayName("AC-CFG-08 运行期：缺省时历史 20 条、温度沿用 starter（不被覆盖）")
    void defaultRequestUsesLegacyValues() {
        chat();

        verify(conversationService).recentMessages(CONVERSATION_ID, 20);
        assertThat(model.lastOptions().getTemperature())
                .as("未显式配置温度时不得覆盖 starter 的 0.2")
                .isEqualTo(0.2);
    }

    // ==================================================================
    // TEST-CFG-06：改配置不重启，下一请求参数已变
    // ==================================================================

    @Test
    @DisplayName("TEST-CFG-06 生效性：改 budget.history-limit 后，下一请求就按新条数取历史（不重启）")
    void historyLimitTakesEffectOnNextRequest() {
        chat();
        verify(conversationService).recentMessages(CONVERSATION_ID, 20);

        configService.update(AiConfigCatalog.BUDGET_HISTORY_LIMIT, "7", "1");
        chat();

        verify(conversationService).recentMessages(CONVERSATION_ID, 7);
    }

    @Test
    @DisplayName("TEST-CFG-06 生效性：改 model.temperature 后，下一请求的模型参数已变")
    void temperatureTakesEffectOnNextRequest() {
        chat();
        assertThat(model.lastOptions().getTemperature()).isEqualTo(0.2);

        configService.update(AiConfigCatalog.MODEL_TEMPERATURE, "0.9", "1");
        chat();

        assertThat(model.lastOptions().getTemperature()).isEqualTo(0.9);
        assertThat(configService.get(AiConfigCatalog.MODEL_TEMPERATURE)).isEqualTo("0.9");
    }

    @Test
    @DisplayName("TEST-CFG-06 生效性：改 model.name 后，下一请求的模型调用参数与落库模型名都已变")
    void modelNameTakesEffectOnNextRequest() {
        chat();
        assertThat(model.lastOptions().getModel())
                .as("未显式配置模型名时不得覆盖 starter/yml")
                .isNull();

        configService.update(AiConfigCatalog.MODEL_NAME, "deepseek-reasoner", "1");
        chat();

        assertThat(model.lastOptions().getModel())
                .as("显式配置后必须随请求发给模型（不只是落库）")
                .isEqualTo("deepseek-reasoner");
        verify(conversationService).resolveOrCreate(eq(USER_ID), any(), anyString(),
                eq("deepseek-reasoner"));
    }

    @Test
    @DisplayName("TEST-CFG-06 生效性：关掉工具组后，下一请求传给注册表的快照里该开关已为 false")
    void toolSwitchTakesEffectOnNextRequest() {
        chat();
        assertThat(capturedSnapshot().getBoolean(AiConfigCatalog.TOOLS_ORDER_ENABLED)).isTrue();

        configService.update(AiConfigCatalog.TOOLS_ORDER_ENABLED, "false", "1");
        chat();

        AiConfigSnapshot second = capturedSnapshot();
        assertThat(second.getBoolean(AiConfigCatalog.TOOLS_ORDER_ENABLED)).isFalse();
        assertThat(second.version()).as("版本号已推进，快照随之重载").isGreaterThan(0L);
    }

    @Test
    @DisplayName("非法值：运行期写入被拒绝（不影响已有快照），库里已有非法值回落默认并记入 invalidKeys")
    void invalidValuesAreRejectedAndFallBack() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        configService.update(AiConfigCatalog.BUDGET_MAX_ROUNDS, "999", "1"))
                .as("超范围值应在触库前被拒绝（AC-CFG-07）")
                .hasMessageContaining("budget.max-rounds");

        configStore.setRaw(AiConfigCatalog.BUDGET_MAX_ROUNDS, "abc");
        AiConfigSnapshot snapshot = configStore.service().snapshot();
        assertThat(snapshot.getInt(AiConfigCatalog.BUDGET_MAX_ROUNDS))
                .as("库中非法值回落默认，助手继续可用")
                .isEqualTo(4);
        assertThat(snapshot.invalidKeys()).contains(AiConfigCatalog.BUDGET_MAX_ROUNDS);
    }

    // ==================================================================
    // 一轮一份快照（REQ-CFG-06）
    // ==================================================================

    @Test
    @DisplayName("一轮对话只用一份快照：请求前比对一次版本，且工具裁剪与预算共用同一实例")
    void singleSnapshotPerTurn() {
        chat();

        verify(configService, times(1)).refreshIfStale();
        // 传给注册表的快照实例就是当前快照（不是又一次加载出来的另一份）
        assertThat(capturedSnapshot()).isSameAs(configService.snapshot());
    }

    @Test
    @DisplayName("回答级回溯：只更新 config_version，且**不覆盖** T4-03 写的 prompt_version")
    void recordsConfigVersionAtTurnEnd() {
        AiConversation existing = new AiConversation();
        existing.setId(CONVERSATION_ID);
        existing.setPromptVersion(7);
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(existing);

        chat();
        verify(conversationMapper).updateVersionTrace(eq(CONVERSATION_ID), eq(7), eq(0L));

        configService.update(AiConfigCatalog.BUDGET_HISTORY_LIMIT, "9", "1");
        chat();

        // 注意先把版本号取出来：在 eq(...) 里调用 spy 的方法会被当成又一次 mock 交互
        long version = configService.snapshot().version();
        verify(conversationMapper).updateVersionTrace(eq(CONVERSATION_ID), eq(7), eq(version));
    }

    @Test
    @DisplayName("启动预热：DB 不可用/表不存在时只告警，绝不阻断启动（AC-CFG-07）")
    void warmUpNeverBreaksStartup() {
        AiConfigItemMapper broken = mock(AiConfigItemMapper.class);
        when(broken.selectAll()).thenThrow(new IllegalStateException("Table 'ai_config_item' doesn't exist"));
        AiConfigWarmUp warmUp = new AiConfigWarmUp(new AiConfigService(broken, new AiConfigCatalog()));

        assertThatCode(warmUp::warmUpOnStartup).doesNotThrowAnyException();
        assertThatCode(warmUp::warmUpOnStartup).as("重复执行同样安全").doesNotThrowAnyException();
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    private void chat() {
        CurrentUser.set(new CurrentUser.Principal(USER_ID, "admin", "超级管理员", List.of("ADMIN"),
                List.of("ai:chat")));
        AiChatRequest request = new AiChatRequest();
        request.setMessage("2026 年第二季度投标订单有多少？");
        List<ServerSentEvent<String>> events = service.stream(USER_ID, request)
                .collectList().block(Duration.ofSeconds(30));
        assertThat(events).isNotNull().isNotEmpty();
    }

    /** 最近一次传给工具注册表的配置快照。 */
    private AiConfigSnapshot capturedSnapshot() {
        org.mockito.ArgumentCaptor<AiConfigSnapshot> captor =
                org.mockito.ArgumentCaptor.forClass(AiConfigSnapshot.class);
        verify(toolRegistry, org.mockito.Mockito.atLeastOnce()).callbacks(any(), captor.capture());
        return captor.getValue();
    }

    /**
     * 内存版配置表：用 Mockito 的 Mapper + 可变行集合模拟 {@code ai_config_item}，
     * 但 {@link AiConfigService} 是真的（校验 / 版本比较 / 快照构建都走真实实现）。
     */
    private static final class FakeConfigStore {

        private final AiConfigItemMapper mapper = mock(AiConfigItemMapper.class);
        private final AtomicReference<List<AiConfigItem>> rows = new AtomicReference<>(List.of());
        private long version;

        FakeConfigStore() {
            when(mapper.selectAll()).thenAnswer(invocation -> rows.get());
            when(mapper.maxVersion()).thenAnswer(invocation -> version);
            when(mapper.updateValue(any())).thenAnswer(invocation -> {
                AiConfigItem item = invocation.getArgument(0);
                upsert(item);
                return 1;
            });
            when(mapper.insert(any())).thenAnswer(invocation -> {
                upsert(invocation.getArgument(0));
                return 1;
            });
        }

        AiConfigService service() {
            return new AiConfigService(mapper, new AiConfigCatalog());
        }

        /** 直接写一行（绕过校验）：模拟"有人手工 UPDATE 了一个非法值"。 */
        void setRaw(String key, String value) {
            AiConfigItem row = new AiConfigItem();
            row.setConfigKey(key);
            row.setConfigValue(value);
            row.setVersion(++version);
            upsert(row);
        }

        private void upsert(AiConfigItem item) {
            List<AiConfigItem> next = new ArrayList<>(rows.get());
            next.removeIf(row -> row.getConfigKey().equals(item.getConfigKey()));
            AiConfigItem copy = new AiConfigItem();
            copy.setConfigKey(item.getConfigKey());
            copy.setConfigValue(item.getConfigValue());
            copy.setVersion(item.getVersion() == null ? ++version : item.getVersion());
            version = Math.max(version, copy.getVersion());
            next.add(copy);
            rows.set(next);
        }
    }

    /** 记下每次模型调用的 Prompt，便于断言"这一轮实际用了什么参数"。 */
    private static final class CapturingChatModel implements ChatModel {

        private final List<Prompt> prompts = new ArrayList<>();

        /** 模拟 starter/yml 的默认选项（temperature=0.2）；未显式配置时不得被覆盖。 */
        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().temperature(0.2).build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return stream(prompt).blockLast();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            prompts.add(prompt);
            ChatGenerationMetadata metadata = ChatGenerationMetadata.builder()
                    .finishReason("stop")
                    .build();
            return Flux.just(new ChatResponse(List.of(
                    new Generation(new AssistantMessage("好的。"), metadata))));
        }

        ToolCallingChatOptions lastOptions() {
            return (ToolCallingChatOptions) prompts.get(prompts.size() - 1).getOptions();
        }
    }
}
