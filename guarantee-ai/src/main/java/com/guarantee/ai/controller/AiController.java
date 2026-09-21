package com.guarantee.ai.controller;

import com.guarantee.ai.dto.AiChatRequest;
import com.guarantee.ai.service.AiChatService;
import com.guarantee.ai.service.AiConversationService;
import com.guarantee.ai.vo.ConversationDetailVO;
import com.guarantee.ai.vo.ConversationVO;
import com.guarantee.ai.vo.ToolCallVO;
import com.guarantee.common.api.Result;
import com.guarantee.common.api.ResultCode;
import com.guarantee.common.exception.BizException;
import com.guarantee.common.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * AI 助手接口。
 *
 * <p>第一阶段只提供对话与查询：POST /api/ai/chat 以及会话/工具调用查询。</p>
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiChatService aiChatService;
    private final AiConversationService aiConversationService;

    public AiController(AiChatService aiChatService, AiConversationService aiConversationService) {
        this.aiChatService = aiChatService;
        this.aiConversationService = aiConversationService;
    }

    /**
     * 流式对话。返回 {@code text/event-stream}，事件见 {@code ChatStreamEvents}。
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@Valid @RequestBody AiChatRequest request) {
        return aiChatService.stream(requireUserId(), request);
    }

    @GetMapping("/conversations")
    public Result<List<ConversationVO>> conversations() {
        return Result.ok(aiConversationService.listConversations(requireUserId()));
    }

    @GetMapping("/conversations/{id}")
    public Result<ConversationDetailVO> conversationDetail(@PathVariable Long id) {
        return Result.ok(aiConversationService.detail(id, requireUserId()));
    }

    @GetMapping("/tool-calls/{conversationId}")
    public Result<List<ToolCallVO>> toolCalls(@PathVariable Long conversationId) {
        return Result.ok(aiConversationService.listToolCalls(conversationId));
    }

    private static Long requireUserId() {
        Long userId = CurrentUser.userId();
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED, "未登录或登录已过期");
        }
        return userId;
    }
}
