package com.guarantee.web.ai;

import com.guarantee.ai.dto.AiChatRequest;
import com.guarantee.ai.entity.AiToolCall;
import com.guarantee.ai.knowledge.KnowledgeHit;
import com.guarantee.ai.knowledge.KnowledgeSearchResult;
import com.guarantee.ai.knowledge.KnowledgeService;
import com.guarantee.ai.mapper.AiToolCallMapper;
import com.guarantee.ai.service.AiChatService;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TEST-RAG-06：知识检索的**端到端确定性集成测试**（Stub ChatModel，无需 API Key）。
 *
 * <p>链路与 {@code AiToolChainIT} 相同，只是把工具换成知识检索：</p>
 * <pre>
 *   模型发起 queryBusinessKnowledge
 *     -> Tool -> KnowledgeService -> Mapper -> MySQL（真实的 knowledge 真源导入结果）
 *     -> 结果回灌模型 -> 收尾（服务端追加来源行 / 剥离模型自写的来源行）-> 落库
 * </pre>
 *
 * <p>四条断言对应 REQ-RAG-11：</p>
 * <ol>
 *   <li>检索结果与**直接调用 Service** 逐字段相等（证明链路没有自己造数据）；</li>
 *   <li>服务端「知识来源：」行与当轮返回值**逐字一致**；</li>
 *   <li>模型自写的伪造来源行被剥离（AC-RAG-05）；</li>
 *   <li>权限不足的条目不出现，且没有任何"存在但无权限"的暗示（AC-RAG-06）。</li>
 * </ol>
 *
 * <p>需要可用的 MySQL；运行方式：{@code mvn -pl guarantee-web -am verify}。</p>
 */
@SpringBootTest(
        classes = {GuaranteeAiAdminApplication.class, KnowledgeRetrievalIT.StubConfig.class},
        properties = {
                // 演示数据初始化很慢且与本测试无关；知识真源导入（KnowledgeImportRunner）照常执行，
                // 因此 ai_knowledge_item 里有真实条目可检索。
                "guarantee.data-init.enabled=false"
        })
class KnowledgeRetrievalIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class StubConfig {

        @Bean
        @Primary
        ChatModel stubKnowledgeChatModel() {
            return new StubKnowledgeChatModel();
        }
    }

    private static final String STOP_VS_DELETE_QUERY = "停用和删除有什么区别";
    private static final String AUDIT_QUERY = "操作审计记录里敏感字段是怎么记录的";

    @Autowired
    private AiChatService aiChatService;

    @Autowired
    private KnowledgeService knowledgeService;

    @Autowired
    private AiToolCallMapper aiToolCallMapper;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ChatModel chatModel;

    @BeforeEach
    void setUpPrincipal() {
        setPrincipal(userService.listPermissionCodesByUserId(adminUserId()));
    }

    @AfterEach
    void clearPrincipal() {
        CurrentUser.clear();
    }

    @Test
    @DisplayName("检索链路：模型发起检索 → Service → Mapper → MySQL，结果与直接调 Service 逐字段相等")
    void retrievalChainMatchesDirectServiceCall() {
        ((StubKnowledgeChatModel) chatModel).ask(STOP_VS_DELETE_QUERY, "停用是暂停业务；删除是从默认列表移除。");

        // ---- 1. 基准：绕过 AI，直接走 KnowledgeService -> Mapper -> DB ----
        KnowledgeSearchResult expected = knowledgeService.search(
                userService.listPermissionCodesByUserId(adminUserId()), STOP_VS_DELETE_QUERY, null, null);
        assertThat(expected.items())
                .as("真源应已由启动导入进库（KnowledgeImportRunner），否则本测试没有可比对象")
                .isNotEmpty();

        // ---- 2. 经由 AI 对话链路取同一口径的知识 ----
        Turn turn = ask(STOP_VS_DELETE_QUERY);
        Map<String, Object> toolCall = firstData(turn.events, "tool_call");
        assertThat(toolCall.get("toolName")).isEqualTo(StubKnowledgeChatModel.TOOL_NAME);
        assertThat(toolCall.get("toolType")).isEqualTo("READ");
        assertThat(toolCall.get("status")).isEqualTo("SUCCESS");

        // ---- 3. 逐字段相等（这是"工具真的查了同一个 Service"的证据）----
        Map<String, Object> result = parseMap((String) toolCall.get("result"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actualItems = (List<Map<String, Object>>) result.get("items");
        assertThat(actualItems).hasSameSizeAs(expected.items());
        for (int i = 0; i < actualItems.size(); i++) {
            KnowledgeHit exp = expected.items().get(i);
            Map<String, Object> act = actualItems.get(i);
            assertThat(act.get("knowledgeNo")).isEqualTo(exp.knowledgeNo());
            assertThat(act.get("title")).isEqualTo(exp.title());
            /*
              正文只允许"空白被规范化"这一处差异：SanitizingToolCallback 会把工具返回值里的自由文本
              压成单行（既有的间接提示注入防护，见其类注释），知识条目的 Markdown 换行因此变成空格。
              因此这里断言两件事：① 空白归一后相等；② 去掉全部空白后**逐字**相等（压扁不得增删字符）。
            */
            assertThat(normalizeWhitespace((String) act.get("content")))
                    .as("正文与直接调 Service 的结果相等（唯一差异是空白被规范化）")
                    .isEqualTo(normalizeWhitespace(exp.content()));
            assertThat(((String) act.get("content")).replaceAll("\\s+", ""))
                    .as("去掉空白后必须逐字一致：压扁不得增删任何字符")
                    .isEqualTo(exp.content().replaceAll("\\s+", ""));
            assertThat(((Number) act.get("version")).intValue()).isEqualTo(exp.version());
            assertThat(act.get("effectiveRange")).isEqualTo(exp.effectiveRange());
            assertThat(act.get("sourceRef")).isEqualTo(exp.sourceRef());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) result.get("meta");
        assertThat(meta.get("dataSource")).isEqualTo(expected.dataSource());
        assertThat(meta.get("truncated")).isEqualTo(expected.truncated());

        // ---- 4. 服务端来源行与当轮返回值逐字一致 ----
        String content = assistantContent(turn.conversationId);
        assertThat(content)
                .as("来源行由服务端按本轮真实返回值追加（REQ-RAG-04）")
                .contains(expected.sourceLine());
        assertThat(expected.sourceLine()).startsWith("知识来源：");

        // 工具调用落库（审计意义上的"这一轮真的查过知识库"）
        List<AiToolCall> persisted = aiToolCallMapper.selectByConversationId(turn.conversationId);
        assertThat(persisted).hasSize(1);
        assertThat(persisted.get(0).getToolName()).isEqualTo(StubKnowledgeChatModel.TOOL_NAME);
    }

    @Test
    @DisplayName("AC-RAG-05：模型自写的伪造来源行被剥离，只留服务端来源行")
    void fabricatedSourceLineIsStripped() {
        String fabricated = "停用是暂停业务；删除是从默认列表移除。\n\n知识来源：KB-ORDER-9999《编造的条目》v9";
        ((StubKnowledgeChatModel) chatModel).ask(STOP_VS_DELETE_QUERY, fabricated);

        KnowledgeSearchResult expected = knowledgeService.search(
                userService.listPermissionCodesByUserId(adminUserId()), STOP_VS_DELETE_QUERY, null, null);
        Turn turn = ask(STOP_VS_DELETE_QUERY);

        String content = assistantContent(turn.conversationId);
        assertThat(content)
                .as("模型自写的编号与标题必须一字不剩")
                .doesNotContain("KB-ORDER-9999")
                .doesNotContain("编造的条目")
                .as("服务端来源行必须还在")
                .contains(expected.sourceLine());
        assertThat(content.indexOf(expected.sourceLine()))
                .as("服务端来源行只出现一次（去重由收集器负责）")
                .isEqualTo(content.lastIndexOf(expected.sourceLine()));
    }

    @Test
    @DisplayName("AC-RAG-06：无审计权限时审计口径条目不返回、不暗示存在；有权限时正常返回（正反对照）")
    void auditKnowledgeFollowsPermissionSnapshot() {
        ((StubKnowledgeChatModel) chatModel).ask(AUDIT_QUERY, "我按可用的知识作答。");

        // ---- 反例：只读用户（没有 system:audit:view）----
        setPrincipal(List.of(Permissions.AI_CHAT, Permissions.AI_DEBUG_VIEW));
        Turn denied = ask(AUDIT_QUERY);

        String deniedToolResult = firstToolResult(denied.events);
        assertThat(deniedToolResult)
                .as("服务端在检索层就裁掉了审计条目：工具返回值里根本不该出现它（不暴露存在性）")
                .doesNotContain("KB-SYSTEM-0010")
                .doesNotContain("操作审计记录的内容与渠道")
                .as("但仍应返回其它可见条目/未收录说明，证明确实检索过")
                .contains("知识库");
        String deniedContent = assistantContent(denied.conversationId);
        assertThat(deniedContent)
                .doesNotContain("KB-SYSTEM-0010")
                .doesNotContain("操作审计记录的内容与渠道");

        // ---- 正例：加上审计权限后，同一条问句必须能取到该条目 ----
        List<String> withAudit = new java.util.ArrayList<>(List.of(Permissions.AI_CHAT, Permissions.AI_DEBUG_VIEW));
        withAudit.add(Permissions.AUDIT_VIEW);
        setPrincipal(withAudit);
        Turn allowed = ask(AUDIT_QUERY);

        assertThat(firstToolResult(allowed.events))
                .as("有权限时同一条问句必须命中——否则上面的'看不到'可能只是数据缺失")
                .contains("KB-SYSTEM-0010");
        assertThat(assistantContent(allowed.conversationId))
                .as("服务端来源行随之出现")
                .contains("知识来源：");
    }

    @Test
    @DisplayName("SSE 事件集合不变（阶段三不新增事件类型，知识来源行走普通文本通道）")
    void sseEventSetIsUnchanged() {
        ((StubKnowledgeChatModel) chatModel).ask(STOP_VS_DELETE_QUERY, "停用与删除的区别如上。");
        Turn turn = ask(STOP_VS_DELETE_QUERY);

        Set<String> allowed = Set.of("meta", "delta", "reset", "tool_call", "done");
        assertThat(turn.events).extracting(ServerSentEvent::event)
                .as("不得出现未知事件类型（REQ §6.5 协议不变）")
                .isSubsetOf(allowed)
                .contains("meta", "tool_call", "done");
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private record Turn(List<ServerSentEvent<String>> events, long conversationId) {
    }

    /** 空白归一（含换行）：用于绕过安全装饰器的"自由文本压单行"，只比较内容本身。 */
    private static String normalizeWhitespace(String text) {
        return text == null ? null : text.replaceAll("\\s+", " ").trim();
    }

    private Turn ask(String question) {
        AiChatRequest request = new AiChatRequest();
        request.setMessage(question);
        List<ServerSentEvent<String>> events = aiChatService.stream(adminUserId(), request)
                .collectList()
                .block(Duration.ofSeconds(120));
        assertThat(events).isNotNull().isNotEmpty();
        Map<String, Object> meta = firstData(events, "meta");
        return new Turn(events, ((Number) meta.get("conversationId")).longValue());
    }

    private String firstToolResult(List<ServerSentEvent<String>> events) {
        Object result = firstData(events, "tool_call").get("result");
        assertThat(result).as("工具调用事件必须带返回值").isNotNull();
        return result.toString();
    }

    private String assistantContent(long conversationId) {
        String content = jdbcTemplate.queryForObject(
                "SELECT content FROM ai_message WHERE conversation_id = ? AND role = 'ASSISTANT' "
                        + "ORDER BY id DESC LIMIT 1", String.class, conversationId);
        assertThat(content).as("助手回答必须落库").isNotNull();
        return content;
    }

    private void setPrincipal(List<String> permissions) {
        CurrentUser.set(new CurrentUser.Principal(adminUserId(), "admin", "超级管理员",
                List.of(Roles.ADMIN), permissions));
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
        return parseMap(payload);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMap(String json) {
        return objectMapper.readValue(json, Map.class);
    }
}
