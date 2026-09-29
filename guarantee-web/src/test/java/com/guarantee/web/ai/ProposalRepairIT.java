package com.guarantee.web.ai;

import com.guarantee.ai.dto.AiChatRequest;
import com.guarantee.ai.entity.AiConversation;
import com.guarantee.ai.entity.AiToolCall;
import com.guarantee.ai.mapper.AiToolCallMapper;
import com.guarantee.ai.service.AiChatService;
import com.guarantee.ai.service.AiConversationService;
import com.guarantee.common.security.CurrentUser;
import com.guarantee.common.security.Roles;
import com.guarantee.system.service.UserService;
import com.guarantee.web.GuaranteeAiAdminApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「模型只用文字假装生成了提案」→ 后端**自动重试一轮**让它真的调用写工具（修复而非仅提示）。
 *
 * <p><b>真机故障（2026-09-24 23:55，第三次复现同一类编造）</b>：用户在助手说"确认后我立即发起提案"
 * 之后回了一句「确定」，助手回了「我已生成变更提案…提案编号 OP202609242359135602，
 * 需要在确认卡上点击『确认执行』后才会生效」——而 {@code ai_tool_call} 里一条都没有，
 * 库里也不存在这个编号。当时系统只做了一件事：在正文末尾补一句
 * 「本次回复提到的提案并未生成」。用户看到的是一条**自相矛盾**的回复（抬头写着编号、
 * 结尾说没生成），而且他真正要的变更**根本没发生**。</p>
 *
 * <p>本用例证明现在的行为：检测到"声称有提案但会话内没有 PENDING 提案"时，
 * 系统**先自动重试一轮**要求模型调用写工具；模型真的调用后，卡片正常出现、
 * 落库正文是修复后的回答（含编造编号的那段既不展示也不落库）。</p>
 *
 * <p>假模型刻意做成"第一次编造、被要求重做后才真的调工具"——这正是真机上模型的形态。
 * 若模型第二次仍然编造，则退回"追加纠正提示"（该分支由 {@code ProposalClaimGuardIT} 覆盖）。</p>
 */
@SpringBootTest(
        classes = {GuaranteeAiAdminApplication.class, ProposalRepairIT.FabricateThenRepairModelConfig.class},
        properties = {
                "guarantee.data-init.enabled=false",
                "guarantee.ai.proposal-expire-interval-ms=3600000",
                "guarantee.ai.audit-inspect-interval-ms=3600000"
        })
class ProposalRepairIT {

    /** 真机那次编造的正文形态（编号同样是编的）。 */
    private static final String FABRICATED =
            "我已生成变更提案，需要在确认卡上点击『确认执行』后才会生效。"
                    + "提案编号：OP202609242359135602，有效期至 2026-09-25 00:14。";

    /**
     * 修复轮之后的正文。
     *
     * <p>它**故意也写"已生成变更提案"**：此时库里确实有 PENDING 提案，
     * 兜底校验必须放行——否则就成了"修复成功后又被贴一句没生成"。</p>
     */
    private static final String REPAIRED =
            "已生成变更提案，请在确认卡上点击『确认执行』后生效。"
                    + "本次只停用「履约保函（标准）」，历史订单不受影响。";

    private static final String TOOL_NAME = "proposeInsuranceTypeChange";
    private static final String TOOL_ARGUMENTS =
            "{\"action\":\"DISABLE\",\"typeName\":\"履约保函（标准）\",\"userText\":\"确定\"}";

    /**
     * 只会"先编造、被点破后才动手"的假模型。
     *
     * <p>三种情形按优先级判定：</p>
     * <ol>
     *   <li>Prompt 里已有工具执行结果 → 输出最终正文（工具循环的正常收尾）；</li>
     *   <li>Prompt 里出现 {@link AiChatService#REPAIR_MARKER} → 说明这是自动重试轮，**发起写工具调用**；</li>
     *   <li>其余（第一次回答）→ 输出编造的正文，且**不发起任何工具调用**。</li>
     * </ol>
     */
    static class FabricateThenRepairChatModel implements ChatModel {

        private final AtomicInteger modelInvocations = new AtomicInteger();

        int modelInvocations() {
            return modelInvocations.get();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return respond(prompt);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(respond(prompt));
        }

        private ChatResponse respond(Prompt prompt) {
            modelInvocations.incrementAndGet();
            if (hasToolResult(prompt)) {
                return textResponse(REPAIRED);
            }
            if (isRepairRound(prompt)) {
                return writeToolCallResponse();
            }
            return textResponse(FABRICATED);
        }

        /**
         * 构造一次写工具调用响应。
         *
         * <p>必须带 {@code finishReason = "tool_calls"}：Spring AI 据此判断是否进入工具执行循环，
         * 缺少该元数据时工具不会被真正执行。</p>
         */
        private static ChatResponse writeToolCallResponse() {
            AssistantMessage message = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            "call_repair_1", "function", TOOL_NAME, TOOL_ARGUMENTS)))
                    .build();
            ChatGenerationMetadata metadata = ChatGenerationMetadata.builder()
                    .finishReason("tool_calls")
                    .build();
            return new ChatResponse(List.of(new Generation(message, metadata)));
        }

        private static ChatResponse textResponse(String text) {
            ChatGenerationMetadata metadata = ChatGenerationMetadata.builder()
                    .finishReason("stop")
                    .build();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text), metadata)));
        }

        /** 是否处于"自动重试轮"：靠服务端暴露的识别前缀，不猜措辞。 */
        private static boolean isRepairRound(Prompt prompt) {
            for (Message message : prompt.getInstructions()) {
                if (message instanceof UserMessage userMessage
                        && userMessage.getText() != null
                        && userMessage.getText().contains(AiChatService.REPAIR_MARKER)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean hasToolResult(Prompt prompt) {
            for (Message message : prompt.getInstructions()) {
                if (message instanceof ToolResponseMessage toolMessage
                        && !toolMessage.getResponses().isEmpty()) {
                    return true;
                }
            }
            return false;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FabricateThenRepairModelConfig {

        @Bean
        @Primary
        ChatModel fabricateThenRepairChatModel() {
            return new FabricateThenRepairChatModel();
        }
    }

    @Autowired
    private AiChatService aiChatService;

    @Autowired
    private AiConversationService conversationService;

    @Autowired
    private AiToolCallMapper aiToolCallMapper;

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 本用例新建的会话；收尾按它精确清理测试造的提案。 */
    private Long conversationId;

    @org.junit.jupiter.api.BeforeEach
    void setUpPrincipal() {
        // 直接调 Service 不走 HTTP，必须自己建主体：权限快照决定工具注册与写工具是否放行
        Long adminId = adminUserId();
        CurrentUser.set(new CurrentUser.Principal(adminId, "admin", "超级管理员",
                List.of(Roles.ADMIN), userService.listPermissionCodesByUserId(adminId)));
    }

    @org.junit.jupiter.api.AfterEach
    void cleanUp() {
        CurrentUser.clear();
        if (conversationId == null) {
            return;
        }
        // 测试造的 PENDING 提案必须清掉：共享开发库上它会出现在真实使用者的会话里
        jdbcTemplate.update("DELETE FROM ai_operation_secret WHERE proposal_id IN "
                + "(SELECT id FROM ai_operation_proposal WHERE conversation_id = ?)", conversationId);
        jdbcTemplate.update("DELETE FROM ai_operation_proposal WHERE conversation_id = ?", conversationId);
    }

    @Test
    @DisplayName("模型只写文字假装生成提案：自动重试一轮后真的调用写工具、卡片与提案都到位")
    void fabricatedClaimIsRepairedByRealToolCall() {
        long adminId = adminUserId();
        AiConversation conversation = conversationService.resolveOrCreate(
                adminId, null, "把履约保函（标准）停用", "stub-model");
        conversationId = conversation.getId();

        List<ServerSentEvent<String>> events = aiChatService
                .stream(adminId, request(conversationId, "确定"))
                .collectList()
                .block(Duration.ofSeconds(60));
        assertThat(events).as("SSE 事件流不应为空").isNotNull().isNotEmpty();

        // ① 修复轮**真的**调用了写工具（真机上这一步是缺失的）
        List<AiToolCall> calls = aiToolCallMapper.selectByConversationId(conversationId);
        assertThat(calls).as("第一次编造不该只落一句提示，必须真的补上一次写工具调用").hasSize(1);
        assertThat(calls.get(0).getToolName()).isEqualTo(TOOL_NAME);
        assertThat(calls.get(0).getToolType()).isEqualTo("WRITE");
        assertThat(calls.get(0).getStatus()).isEqualTo("SUCCESS");

        // ② 提案真的落库为 PENDING，且归属本会话（确认卡的来源）
        Map<String, Object> proposal = jdbcTemplate.queryForMap(
                "SELECT status, target_name, conversation_id FROM ai_operation_proposal "
                        + "WHERE conversation_id = ? ORDER BY id DESC LIMIT 1", conversationId);
        assertThat(proposal.get("status")).isEqualTo("PENDING");
        assertThat(proposal.get("target_name")).isEqualTo("履约保函（标准）");

        // ③ 前端拿到 reset（丢掉那段编造的正文）与 proposal（渲染确认卡）
        assertThat(events).as("编造的那段正文已经流给用户了，必须发 reset 让前端丢掉它")
                .anyMatch(event -> "reset".equals(event.event()));
        assertThat(events).as("修复轮生成的提案必须推给前端，否则用户仍然看不到卡片")
                .anyMatch(event -> "proposal".equals(event.event()));

        // ④ 落库正文 = 修复后的正文 + 服务端生成的口径行（口径不再由模型产出，见 DataSourceClaimGuard）
        String stored = lastAssistantMessage(conversationId);
        assertThat(stored)
                .as("落库的必须是修复后的回答，后面接服务端口径")
                .startsWith(REPAIRED)
                .contains("口径：变更提案 · 停用险种：履约保函（标准）")
                .doesNotContain("OP202609242359135602")
                .doesNotContain("系统提示");

        // ⑤ 模型被调用 3 次：编造 → 修复轮（发起工具调用）→ 基于工具结果作答
        assertThat(((FabricateThenRepairChatModel) chatModel).modelInvocations())
                .as("自动重试只允许有界的一轮，不能无限重试")
                .isEqualTo(3);

        // ⑥ 编造的那条正文不进入历史：只有一条助手消息，且它就是修复后的内容
        Integer assistantMessages = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_message WHERE conversation_id = ? AND role = 'ASSISTANT'",
                Integer.class, conversationId);
        assertThat(assistantMessages).as("一轮对话只应落一条助手消息").isEqualTo(1);
    }

    private static AiChatRequest request(long conversationId, String message) {
        AiChatRequest request = new AiChatRequest();
        request.setConversationId(conversationId);
        request.setMessage(message);
        return request;
    }

    private String lastAssistantMessage(long conversationId) {
        return jdbcTemplate.queryForObject(
                "SELECT content FROM ai_message WHERE conversation_id = ? AND role = 'ASSISTANT' "
                        + "ORDER BY id DESC LIMIT 1", String.class, conversationId);
    }

    private long adminUserId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = ?", Long.class, "admin");
    }
}
