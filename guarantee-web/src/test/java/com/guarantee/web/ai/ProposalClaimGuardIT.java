package com.guarantee.web.ai;

import com.guarantee.ai.dto.AiChatRequest;
import com.guarantee.ai.entity.AiConversation;
import com.guarantee.ai.service.AiChatService;
import com.guarantee.ai.service.AiConversationService;
import com.guarantee.ai.service.ProposalClaimGuard;
import com.guarantee.ai.service.ProposalPreview;
import com.guarantee.ai.service.ProposalRequest;
import com.guarantee.ai.service.ProposalService;
import com.guarantee.ai.tool.AiToolContextKeys;
import com.guarantee.ai.tool.MyProposalsQueryTool;
import com.guarantee.ai.tool.MyProposalsToolResult;
import com.guarantee.ai.vo.ChatStreamEvents;
import com.guarantee.common.security.Permissions;
import com.guarantee.common.security.Roles;
import com.guarantee.web.GuaranteeAiAdminApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「声称有提案但没有提案」后端兜底校验的集成测试（SYS-Q-06b）。
 *
 * <p><b>为什么必须在真实链路上测</b>：校验逻辑本身在
 * {@code ProposalClaimGuardTest} 里已有单元测试，但真机故障是
 * "用户看到了编造的回复"——因此真正要证明的是这条链路：
 * 模型输出 → 校验判定 → 纠正文案**被追加进本条助手消息**（SSE delta + 落库）
 * → 用户下次进会话仍能看到纠正。</p>
 *
 * <p>两条用例对应两个必须同时成立的结论：</p>
 * <ol>
 *   <li>编造（会话内无 PENDING 提案）→ 用户收到纠正，且落库内容也含纠正；</li>
 *   <li>确有 PENDING 提案 → **一个字都不改**（误伤会让用户以为卡片是假的）。</li>
 * </ol>
 *
 * <p>用确定性的假模型复现"模型没调用任何工具、直接编造提案编号"的形态，
 * 不依赖真实 LLM。</p>
 */
@SpringBootTest(
        classes = {GuaranteeAiAdminApplication.class, ProposalClaimGuardIT.FabricatingModelConfig.class},
        properties = {
                "guarantee.data-init.enabled=false",
                "guarantee.ai.proposal-expire-interval-ms=3600000",
                "guarantee.ai.audit-inspect-interval-ms=3600000"
        })
class ProposalClaimGuardIT {

    /** 真机复现的正文形态：编号是改过尾数的假编号，且本轮没有任何工具调用。 */
    private static final String FABRICATED_ANSWER =
            "待确认提案：提案编号 OP202609231200258712，目标为险种「投标保函（标准）」。"
                    + "请在确认卡上点击「确认执行」后生效。";

    /**
     * 只会输出"编造的提案正文"的假模型。
     *
     * <p>刻意**不发起任何工具调用**：真机故障的关键特征就是"正文声称有提案，
     * 而 {@code ai_tool_call} 一条都没有"。</p>
     */
    static class FabricatingChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            return textResponse();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(textResponse());
        }

        private static ChatResponse textResponse() {
            ChatGenerationMetadata metadata = ChatGenerationMetadata.builder()
                    .finishReason("stop")
                    .build();
            return new ChatResponse(List.of(new Generation(
                    new AssistantMessage(FABRICATED_ANSWER), metadata)));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FabricatingModelConfig {

        @Bean
        @Primary
        ChatModel fabricatingChatModel() {
            return new FabricatingChatModel();
        }
    }

    @Autowired
    private AiChatService aiChatService;

    @Autowired
    private AiConversationService conversationService;

    @Autowired
    private ProposalService proposalService;

    @Autowired
    private MyProposalsQueryTool myProposalsQueryTool;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private tools.jackson.databind.ObjectMapper objectMapper;

    private com.guarantee.web.support.ProposalFixture proposalFixture;

    @org.junit.jupiter.api.BeforeEach
    void setUpFixture() {
        proposalFixture = new com.guarantee.web.support.ProposalFixture(jdbcTemplate);
    }

    /**
     * 清理本类造出的提案。
     *
     * <p>集成测试跑在共享开发库上，而前端的「待确认提案」是按用户全局返回的待办清单——
     * 测试留下的 PENDING 提案会出现在真实使用者的助手面板里（真机上发生过：
     * 使用者只问了一句纯查询「履约保函怎么样」，界面上却冒出两张测试造的停用确认卡）。</p>
     */
    @org.junit.jupiter.api.AfterEach
    void cleanUpProposals() {
        proposalFixture.cleanUp();
    }

    // ==================================================================
    // 一：编造 + 无 PENDING → 追加纠正
    // ==================================================================

    @Test
    @DisplayName("正文声称有提案而会话内无 PENDING：追加纠正，且落库的助手消息里也有这句话")
    void fabricatedClaimShouldBeCorrectedInStreamAndInStorage() {
        long adminId = userId("admin");

        AiConversation conversation = conversationService.resolveOrCreate(
                adminId, null, "把投标保函（标准）停用", "stub-model");

        List<ServerSentEvent<String>> events = streamOnce(adminId, conversation.getId(),
                "把投标保函（标准）停用");

        // 前提自证：本轮确实一次工具调用都没有（真机故障的关键特征）
        Integer toolCalls = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_tool_call WHERE conversation_id = ?", Integer.class,
                conversation.getId());
        assertThat(toolCalls).as("本轮不得有任何工具调用，否则就不是我们要复现的故障形态")
                .isZero();

        // 用户收到的增量里必须有纠正文案（纠正是以 delta 追加的，所以用户当场就能看到）
        String deltaText = deltaContent(events);
        assertThat(deltaText).as("用户必须在同一条回复里看到纠正")
                .contains("系统提示").contains("并未生成");

        // 落库内容同样含纠正：下次进入会话仍然看得到，而不是只有当时在场的人看到
        String stored = lastAssistantMessage(conversation.getId());
        assertThat(stored)
                .as("纠正必须写进助手消息本身（不是另起一条消息）")
                .startsWith(FABRICATED_ANSWER)
                .contains(ProposalClaimGuard.CORRECTION);

        // 反过来确认"只有一条助手消息"：没有把纠正另起一条造成消息重复
        Integer assistantMessages = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_message WHERE conversation_id = ? AND role = 'ASSISTANT'",
                Integer.class, conversation.getId());
        assertThat(assistantMessages).as("一轮对话只应落一条助手消息").isEqualTo(1);
    }

    // ==================================================================
    // 二：确有 PENDING → 不误伤
    // ==================================================================

    @Test
    @DisplayName("写工具确实生成了待确认提案：同样的正文不得被追加任何纠正")
    void realPendingProposalShouldNotBeTouched() {
        long adminId = userId("admin");
        long typeId = jdbcTemplate.queryForObject(
                "SELECT id FROM insurance_type WHERE type_name = ?", Long.class, "投标保函（标准）");

        AiConversation conversation = conversationService.resolveOrCreate(
                adminId, null, "把投标保函（标准）停用", "stub-model");

        // 真实生成一张待确认提案（走 ProposalService 的正规入口，不手工插库）
        proposalFixture.track(proposalService.create(new ProposalService.ProposalDraft(
                conversation.getId(), adminId, "admin", "超级管理员",
                "proposeInsuranceTypeChange", "DISABLE", "INSURANCE_TYPE", typeId, "投标保函（标准）",
                ProposalRequest.builder().id(typeId).targetName("投标保函（标准）")
                        .userText("把投标保函（标准）停用").build(),
                ProposalPreview.of("停用险种：投标保函（标准）",
                        List.of(new ProposalPreview.ChangeItem("status", "状态", "启用", "停用")),
                        List.of("影响面：引用订单数"), List.of("停用后不再出现在新订单可选列表"), true),
                Set.of(Permissions.AI_SYSTEM_WRITE, Permissions.INSURANCE_DISABLE),
                "把投标保函（标准）停用", null, "trace-claim-guard-it")));

        List<ServerSentEvent<String>> events = streamOnce(adminId, conversation.getId(),
                "把投标保函（标准）停用");

        String deltaText = deltaContent(events);
        assertThat(deltaText).as("确有 PENDING 提案时不得追加任何纠正（误伤比不纠更糟）")
                .doesNotContain("系统提示");

        String stored = lastAssistantMessage(conversation.getId());
        assertThat(stored).as("落库正文也不得被改动").isEqualTo(FABRICATED_ANSWER);
        // 收尾统一交给 @AfterEach 的 proposalFixture：原先这里手工把提案置为 REJECTED，
        // 但仍会留下"提案编号存在、业务上没人做过这个变更"的痕迹（而且那张卡一度
        // 在真实使用者的面板里出现过）。现在直接删除，对共享开发库零残留。
    }

    // ==================================================================
    // 三：queryMyProposals 是"提案编号"唯一合法的来源，必须能查到真实编号
    // ==================================================================

    @Test
    @DisplayName("queryMyProposals 走真实库能查到刚生成的提案编号，且编造的编号不可能出现")
    void queryMyProposalsShouldReturnTheRealProposalNo() {
        long adminId = userId("admin");
        long typeId = jdbcTemplate.queryForObject(
                "SELECT id FROM insurance_type WHERE type_name = ?", Long.class, "履约保函（预付款）");

        AiConversation conversation = conversationService.resolveOrCreate(
                adminId, null, "把履约保函（预付款）停用", "stub-model");

        var payload = proposalFixture.track(proposalService.create(new ProposalService.ProposalDraft(
                conversation.getId(), adminId, "admin", "超级管理员",
                "proposeInsuranceTypeChange", "DISABLE", "INSURANCE_TYPE", typeId, "履约保函（预付款）",
                ProposalRequest.builder().id(typeId).targetName("履约保函（预付款）")
                        .userText("把履约保函（预付款）停用").build(),
                ProposalPreview.of("停用险种：履约保函（预付款）",
                        List.of(new ProposalPreview.ChangeItem("status", "状态", "启用", "停用")),
                        List.of("影响面：引用订单数"), List.of("停用后不再出现在新订单可选列表"), true),
                Set.of(Permissions.AI_SYSTEM_WRITE, Permissions.INSURANCE_DISABLE),
                "把履约保函（预付款）停用", null, "trace-my-proposals-it")));

        // 用真实的 ToolContext 键调用工具（与 AiChatService.buildToolContext 写入的键一致）
        MyProposalsToolResult result = myProposalsQueryTool.queryMyProposals(new ToolContext(Map.of(
                AiToolContextKeys.USER_ID, adminId,
                AiToolContextKeys.CONVERSATION_ID, conversation.getId(),
                AiToolContextKeys.PERMISSIONS, List.of(Permissions.AI_CHAT, Permissions.AI_SYSTEM_QUERY))));

        assertThat(result.meta().denied()).as("ADMIN 持有 ai:system:query，不应被拒绝").isFalse();
        assertThat(result.items())
                .as("工具必须返回刚生成的那条提案（否则模型无从得到可引用的真编号）")
                .extracting(MyProposalsToolResult.MyProposalItem::proposalNo)
                .contains(payload.proposalNo());
        assertThat(result.items()).allSatisfy(item -> {
            assertThat(item.status()).isEqualTo("PENDING");
            assertThat(item.proposalId()).isNotNull();
            assertThat(item.expiresAt()).isNotNull();
        });
        // 真机里那个编造的编号绝不可能出现在工具返回值里
        assertThat(result.items())
                .extracting(MyProposalsToolResult.MyProposalItem::proposalNo)
                .doesNotContain("OP202609231200258712");

        // 收尾
        jdbcTemplate.update("UPDATE ai_operation_proposal SET status = 'REJECTED' WHERE id = ?",
                payload.proposalId());
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private List<ServerSentEvent<String>> streamOnce(long userId, long conversationId, String message) {
        AiChatRequest request = new AiChatRequest();
        request.setConversationId(conversationId);
        request.setMessage(message);
        List<ServerSentEvent<String>> events = aiChatService.stream(userId, request)
                .collectList().block(Duration.ofSeconds(60));
        assertThat(events).as("SSE 事件流不应为空").isNotNull().isNotEmpty();
        return events;
    }

    /** 该会话最后一条助手消息的正文。 */
    private String lastAssistantMessage(long conversationId) {
        return jdbcTemplate.queryForObject(
                "SELECT content FROM ai_message WHERE conversation_id = ? AND role = 'ASSISTANT' "
                        + "ORDER BY id DESC LIMIT 1", String.class, conversationId);
    }

    /**
     * 把 SSE 的 delta 事件正文拼起来。
     *
     * <p>刻意用 ObjectMapper 反序列化而不是在原始 JSON 上做字符串包含判断：
     * 后者依赖"Jackson 不转义非 ASCII"这一未写进契约的默认行为，
     * 一旦哪天开启 {@code ESCAPE_NON_ASCII}，断言会以"纠正没出现"的假象失败。</p>
     */
    private String deltaContent(List<ServerSentEvent<String>> events) {
        StringBuilder text = new StringBuilder();
        for (ServerSentEvent<String> event : events) {
            if ("delta".equals(event.event())) {
                text.append(objectMapper.readValue(event.data(), ChatStreamEvents.Delta.class).content());
            }
        }
        return text.toString();
    }

    private long userId(String username) {
        return jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
    }
}
