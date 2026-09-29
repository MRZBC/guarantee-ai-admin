package com.guarantee.web.ai;

import com.guarantee.ai.dto.AiChatRequest;
import com.guarantee.ai.entity.AiToolCall;
import com.guarantee.ai.mapper.AiToolCallMapper;
import com.guarantee.ai.service.AiChatService;
import com.guarantee.ai.tool.AiToolRegistry;
import com.guarantee.common.security.CurrentUser;
import com.guarantee.common.security.Permissions;
import com.guarantee.common.security.Roles;
import com.guarantee.system.service.UserService;
import com.guarantee.web.GuaranteeAiAdminApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-RAG-07 的确定性证据：**关掉知识层之后数字类问答不受影响**。
 *
 * <p>开关是 {@code guarantee.ai.knowledge.enabled=false}，效果是
 * {@code queryBusinessKnowledge} 根本**不进注册集**（模型看不到它），
 * 而不是"注册了但执行时报错"——后者会让模型反复重试并把拒绝文案写进回答。</p>
 *
 * <p>本类替换的模型是阶段二的 {@link StubToolCallingChatModel}（它调 {@code queryOrderSummary}），
 * 因此这一条同时是"知识层开关不影响既有取数链路"的回归护栏。
 * 真机上"如实说明知识层不可用"的话术属模型行为，由 GQ-25 覆盖（需要关闭知识层的实例，见
 * {@code docs/TEST-助手黄金问题集.md} §4.1）。</p>
 */
@SpringBootTest(
        classes = {GuaranteeAiAdminApplication.class, KnowledgeDisabledIT.StubConfig.class},
        properties = {
                "guarantee.data-init.enabled=false",
                "guarantee.ai.knowledge.enabled=false"
        })
class KnowledgeDisabledIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class StubConfig {

        @Bean
        @Primary
        ChatModel stubToolCallingChatModel() {
            return new StubToolCallingChatModel();
        }
    }

    private static final String QUESTION = "2026年第三季度投标订单有多少？";

    @Autowired
    private AiChatService aiChatService;

    @Autowired
    private AiToolRegistry aiToolRegistry;

    @Autowired
    private AiToolCallMapper aiToolCallMapper;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUpPrincipal() {
        CurrentUser.set(new CurrentUser.Principal(adminUserId(), "admin", "超级管理员",
                List.of(Roles.ADMIN), userService.listPermissionCodesByUserId(adminUserId())));
    }

    @AfterEach
    void clearPrincipal() {
        CurrentUser.clear();
    }

    @Test
    @DisplayName("知识层关闭：检索工具不入注册集（连 ADMIN 也看不到），数字类工具与链路完全不受影响")
    void knowledgeToolIsNotRegisteredAndNumbersStillWork() {
        List<String> adminPermissions = userService.listPermissionCodesByUserId(adminUserId());

        assertThat(aiToolRegistry.availableToolNames(adminPermissions))
                .as("关闭知识层后连 ADMIN 也看不到检索工具：模型不会尝试调用、不会拿到拒绝文案")
                .doesNotContain(StubKnowledgeChatModel.TOOL_NAME);
        assertThat(aiToolRegistry.availableToolNames(adminPermissions))
                .as("数字类工具必须全部还在")
                .contains("queryOrderSummary", "queryOrderDistribution", "queryOrderTrend", "queryOperationAudit");
        assertThat(aiToolRegistry.availableToolNames(List.of(Permissions.AI_CHAT)))
                .as("最小权限用户同样不受影响（订单域只读工具本就登录可用）")
                .contains("queryOrderSummary")
                .doesNotContain(StubKnowledgeChatModel.TOOL_NAME);

        // 数字类问答照常走完整链路
        AiChatRequest request = new AiChatRequest();
        request.setMessage(QUESTION);
        List<ServerSentEvent<String>> events = aiChatService.stream(adminUserId(), request)
                .collectList()
                .block(Duration.ofSeconds(120));
        assertThat(events).isNotNull().isNotEmpty();

        Map<String, Object> toolCall = firstData(events, "tool_call");
        assertThat(toolCall.get("toolName")).isEqualTo(StubToolCallingChatModel.TOOL_NAME);
        assertThat(toolCall.get("status")).isEqualTo("SUCCESS");

        long conversationId = ((Number) firstData(events, "meta").get("conversationId")).longValue();
        String content = jdbcTemplate.queryForObject(
                "SELECT content FROM ai_message WHERE conversation_id = ? AND role = 'ASSISTANT' "
                        + "ORDER BY id DESC LIMIT 1", String.class, conversationId);
        assertThat(content)
                .as("数字类回答不退化：正文与口径行都在")
                .contains(StubToolCallingChatModel.ANSWER_PART_1)
                .contains("口径：订单统计")
                .as("知识层关闭不得产生任何知识来源行")
                .doesNotContain("知识来源：");

        List<AiToolCall> persisted = aiToolCallMapper.selectByConversationId(conversationId);
        assertThat(persisted).hasSize(1);
        assertThat(persisted.get(0).getToolName()).isEqualTo(StubToolCallingChatModel.TOOL_NAME);
    }

    private Long adminUserId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = 'admin'", Long.class);
    }

    private Map<String, Object> firstData(List<ServerSentEvent<String>> events, String eventName) {
        String payload = events.stream()
                .filter(e -> eventName.equals(e.event()))
                .map(ServerSentEvent::data)
                .findFirst()
                .orElseThrow(() -> new AssertionError("未收到 SSE 事件: " + eventName));
        return objectMapper.readValue(payload, Map.class);
    }
}
