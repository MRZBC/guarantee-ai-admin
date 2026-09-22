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

    /**
     * 某会话下的工具调用记录（页面展示用）。
     *
     * <p><b>必须校验会话归属</b>：该接口返回 {@code arguments} 与 {@code result} 原文，
     * 若不校验归属，任何登录用户都能通过遍历 conversationId 读到他人的工具入参与结果，
     * 这会让 SYS-P-11 与 D-4 的收窄全部失效。因此这里复用 {@link #detail} 的归属判定。</p>
     */
    @Transactional(readOnly = true)
    public List<ToolCallVO> listToolCalls(Long conversationId, Long userId) {
        // 借用 detail 的越权校验（会话不存在/不属于当前用户都会抛异常）
        detail(conversationId, userId);
        return toolCallMapper.selectByConversationId(conversationId).stream()
                .map(AiConversationService::toToolCallVO)
                .toList();
    }

    // ------------------------------------------------------------------
    // queryMyToolCalls 的数据源（SYS-Q-06a）
    // ------------------------------------------------------------------

    /**
     * 当前用户自己的工具调用记录。
     *
     * <p><b>强制范围</b>：{@code userId} 由服务端从 ToolContext/登录态取得，
     * 本方法**不接受**任何"要查哪个用户"的参数——一旦接受，越权就只是传错一个参数的事。</p>
     *
     * <p><b>字段收窄</b>：只返回 {@code resultSummary}（例如"命中 12 个机构"），
     * 不返回 {@code arguments} 与 {@code result} 原文。理由是 ANALYST 对自己的会话有完整查询权，
     * 而写工具入参可能含手机号：若自查接口返回原文，SYS-P-11 与 D-4 会被绕过（SYS-A-09）。</p>
     */
    @Transactional(readOnly = true)
    public ToolCallMinePage listMyToolCalls(Long userId, LocalDateTime startDate, LocalDateTime endDate,
                                            String toolName, String status, int limit) {
        long total = toolCallMapper.countMine(userId, startDate, endDate, toolName, status);
        if (total == 0) {
            return new ToolCallMinePage(0L, List.of());
        }
        List<MyToolCallItem> items = toolCallMapper
                .selectMine(userId, startDate, endDate, toolName, status, limit).stream()
                .map(AiConversationService::toMyToolCallItem)
                .toList();
        return new ToolCallMinePage(total, items);
    }

    /**
     * 我的工具调用记录（收窄后的视图）。
     *
     * <p>刻意不含 {@code arguments} / {@code result} 原文。</p>
     */
    public record MyToolCallItem(Long id, Long conversationId, String toolName, String toolType,
                                 String status, Long durationMs, LocalDateTime createdAt,
                                 String resultSummary) {
    }

    /** 分页结果。 */
    public record ToolCallMinePage(long total, List<MyToolCallItem> items) {
    }

    private static MyToolCallItem toMyToolCallItem(AiToolCall t) {
        return new MyToolCallItem(t.getId(), t.getConversationId(), t.getToolName(), t.getToolType(),
                t.getStatus(), t.getDurationMs(), t.getCreatedAt(),
                summarizeResult(t.getStatus(), t.getErrorMessage(), t.getResult()));
    }

    /**
     * 生成一行结果摘要。
     *
     * <p>只从结果 JSON 中提取"命中了多少条"这类计数，不做自由文本截取——
     * 截取原文可能把敏感片段带出去，而计数天然不含个人信息。</p>
     */
    static String summarizeResult(String status, String errorMessage, String result) {
        if ("FAILED".equalsIgnoreCase(status)) {
            return errorMessage == null || errorMessage.isBlank()
                    ? "执行失败" : "执行失败：" + truncateText(errorMessage, 120);
        }
        if (result == null || result.isBlank()) {
            return "已执行（无返回内容）";
        }
        Long total = extractFirstCount(result);
        if (total != null) {
            return "命中 " + total + " 条记录";
        }
        // 提案类工具：摘要里带 proposalId 更有用
        String proposalId = extractField(result, "proposalId");
        if (proposalId != null) {
            return "已生成提案 " + proposalId + "（待确认）";
        }
        return "已执行";
    }

    /** 从结果 JSON 中提取第一个出现的计数字段。 */
    private static Long extractFirstCount(String json) {
        for (String key : new String[]{"\"total\"", "\"orderCount\"", "\"count\"", "\"items\""}) {
            String value = extractField(json, key.replace("\"", ""));
            if (value != null && value.matches("\\d+")) {
                return Long.parseLong(value);
            }
        }
        return null;
    }

    /** 极简 JSON 字段提取：只认字符串或数字字面量，避免为一行摘要引入完整解析。 */
    private static String extractField(String json, String field) {
        String needle = "\"" + field + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) {
            return null;
        }
        int colon = json.indexOf(':', idx + needle.length());
        if (colon < 0) {
            return null;
        }
        int cursor = colon + 1;
        while (cursor < json.length() && Character.isWhitespace(json.charAt(cursor))) {
            cursor++;
        }
        if (cursor >= json.length()) {
            return null;
        }
        char first = json.charAt(cursor);
        if (first == '"') {
            int end = json.indexOf('"', cursor + 1);
            return end < 0 ? null : json.substring(cursor + 1, end);
        }
        int end = cursor;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        return end == cursor ? null : json.substring(cursor, end);
    }

    private static String truncateText(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
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

    /**
     * 写审计日志（显式 traceId）。
     *
     * <p>提案生命周期（PROPOSAL_CREATED / CONFIRMED / REJECTED / EXPIRED）与操作执行
     * 发生在不同线程与不同 HTTP 请求里，MDC 已失效，因此必须显式传入 traceId，
     * 才能把"会话消息 → 工具调用 → 提案 → 审计"四条记录串起来（SYS-A-01 / T-07）。</p>
     */
    @Transactional
    public void audit(Long conversationId, Long userId, String action, String detail, String traceId) {
        try {
            AiAuditLog log = new AiAuditLog();
            log.setConversationId(conversationId);
            log.setUserId(userId);
            log.setAction(action);
            log.setDetail(detail);
            log.setTraceId(traceId != null ? traceId : TraceContext.currentTraceId());
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
