package com.guarantee.ai.service;

import com.guarantee.ai.entity.AiAuditLog;
import com.guarantee.ai.entity.AiConversation;
import com.guarantee.ai.entity.AiMessage;
import com.guarantee.ai.entity.AiToolCall;
import com.guarantee.ai.mapper.AiAuditLogMapper;
import com.guarantee.ai.mapper.AiConversationMapper;
import com.guarantee.ai.mapper.AiMessageMapper;
import com.guarantee.ai.mapper.AiToolCallMapper;
import com.guarantee.ai.vo.ConversationDetailVO;
import com.guarantee.ai.vo.ConversationVO;
import com.guarantee.ai.vo.MessageVO;
import com.guarantee.ai.vo.ToolCallVO;
import com.guarantee.common.exception.BizException;
import com.guarantee.common.trace.TraceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * AI 会话与消息的持久化服务。
 */
@Service
public class AiConversationService {

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int TITLE_MAX = 30;
    private static final int DEFAULT_LIST_LIMIT = 50;

    private final AiConversationMapper conversationMapper;
    private final AiMessageMapper messageMapper;
    private final AiToolCallMapper toolCallMapper;
    private final AiAuditLogMapper auditLogMapper;

    public AiConversationService(AiConversationMapper conversationMapper,
                                 AiMessageMapper messageMapper,
                                 AiToolCallMapper toolCallMapper,
                                 AiAuditLogMapper auditLogMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.toolCallMapper = toolCallMapper;
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * 取已有会话；conversationId 为空则新建。会校验会话归属，防止越权读取他人会话。
     */
    @Transactional
    public AiConversation resolveOrCreate(Long userId, Long conversationId, String firstMessage, String model) {
        if (conversationId != null) {
            AiConversation existing = conversationMapper.selectById(conversationId);
            if (existing == null) {
                throw BizException.notFound("会话不存在: " + conversationId);
            }
            if (!existing.getUserId().equals(userId)) {
                throw new BizException("无权访问该会话");
            }
            return existing;
        }
        AiConversation conversation = new AiConversation();
        conversation.setConversationNo(generateNo());
        conversation.setUserId(userId);
        conversation.setTitle(buildTitle(firstMessage));
        conversation.setModel(model);
        conversation.setStatus("ACTIVE");
        conversation.setMessageCount(0);
        conversationMapper.insert(conversation);
        return conversation;
    }

    @Transactional
    public AiMessage appendMessage(Long conversationId, String role, String content) {
        AiMessage message = new AiMessage();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content == null ? "" : content);
        message.setTokenCount(estimateTokens(content));
        messageMapper.insert(message);
        conversationMapper.incrementMessageCount(conversationId, 1);
        return message;
    }

    @Transactional(readOnly = true)
    public List<AiMessage> recentMessages(Long conversationId, int limit) {
        List<AiMessage> reversed = messageMapper.selectRecentByConversationId(conversationId, limit);
        return reversed.reversed();
    }

    @Transactional
    public void bindToolCallsToMessage(Long conversationId, Long messageId) {
        toolCallMapper.bindMessageId(conversationId, messageId);
    }

    @Transactional(readOnly = true)
    public List<ConversationVO> listConversations(Long userId) {
        return conversationMapper.selectByUserId(userId, DEFAULT_LIST_LIMIT).stream()
                .map(AiConversationService::toVO)
                .toList();
    }

    @Transactional(readOnly = true)
    public ConversationDetailVO detail(Long conversationId, Long userId) {
        AiConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw BizException.notFound("会话不存在: " + conversationId);
        }
        if (!conversation.getUserId().equals(userId)) {
            throw new BizException("无权访问该会话");
        }
        List<MessageVO> messages = messageMapper.selectByConversationId(conversationId).stream()
                .map(AiConversationService::toMessageVO)
                .toList();
        return new ConversationDetailVO(toVO(conversation), messages);
    }

    @Transactional(readOnly = true)
    public List<ToolCallVO> listToolCalls(Long conversationId) {
        return toolCallMapper.selectByConversationId(conversationId).stream()
                .map(AiConversationService::toToolCallVO)
                .toList();
    }

    /** 写审计日志；失败不影响主流程。 */
    @Transactional
    public void audit(Long conversationId, Long userId, String action, String detail) {
        try {
            AiAuditLog log = new AiAuditLog();
            log.setConversationId(conversationId);
            log.setUserId(userId);
            log.setAction(action);
            log.setDetail(detail);
            log.setTraceId(TraceContext.currentTraceId());
            auditLogMapper.insert(log);
        } catch (RuntimeException ignored) {
            // 审计失败不应中断对话
        }
    }

    private static String generateNo() {
        return "CV" + LocalDateTime.now().format(NO_FMT)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10_000));
    }

    private static String buildTitle(String message) {
        if (message == null || message.isBlank()) {
            return "新会话";
        }
        String flat = message.replaceAll("\\s+", " ").trim();
        return flat.length() <= TITLE_MAX ? flat : flat.substring(0, TITLE_MAX) + "…";
    }

    /** 粗略估算：中文按 1 token/字，英文按 1 token/4 字符。仅用于展示。 */
    private static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        long cjk = text.codePoints().filter(cp -> cp >= 0x4E00 && cp <= 0x9FFF).count();
        long other = text.length() - cjk;
        return (int) (cjk + other / 4);
    }

    private static ConversationVO toVO(AiConversation c) {
        return new ConversationVO(c.getId(), c.getConversationNo(), c.getTitle(), c.getModel(),
                c.getStatus(), c.getMessageCount(), c.getCreatedAt(), c.getUpdatedAt());
    }

    private static MessageVO toMessageVO(AiMessage m) {
        return new MessageVO(m.getId(), m.getConversationId(), m.getRole(), m.getContent(),
                m.getTokenCount(), m.getCreatedAt());
    }

    private static ToolCallVO toToolCallVO(AiToolCall t) {
        return new ToolCallVO(t.getId(), t.getConversationId(), t.getMessageId(), t.getToolName(),
                t.getToolType(), t.getArguments(), t.getResult(), t.getStatus(), t.getDurationMs(),
                t.getErrorMessage(), t.getCreatedAt());
    }
}
