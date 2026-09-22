package com.guarantee.ai.service;

import com.guarantee.ai.vo.ChatStreamEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提案事件的会话级推送通道（SYS-C-08 / T-07）。
 *
 * <p><b>为什么用通道注册表而不是直接持有 SSE 流</b>：
 * {@code proposal} 事件在工具线程上产生，{@code proposal_result} 事件在**另一个 HTTP 请求**
 * （确认接口）的处理线程上产生。后者根本没有原始 SSE 流的引用，只能靠"会话 id → 通道"
 * 的注册表找回。因此 {@code AiChatService} 在开始对话时注册通道、结束时注销。</p>
 *
 * <p><b>流已关闭怎么办</b>：SYS-C-08 要求"若流已关闭，则通过会话消息落库，
 * 前端下次进入会话可见"。本类只负责尝试推送，落库由
 * {@link ProposalService} 在推送失败时完成。</p>
 */
@Component
public class ProposalEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ProposalEventPublisher.class);

    private final Map<Long, Sinks.Many<ChatStreamEvents.Proposal>> proposalSinks = new ConcurrentHashMap<>();
    private final Map<Long, Sinks.Many<ChatStreamEvents.ProposalResult>> resultSinks = new ConcurrentHashMap<>();

    /** 对话开始时注册通道。 */
    public void register(Long conversationId,
                         Sinks.Many<ChatStreamEvents.Proposal> proposalSink,
                         Sinks.Many<ChatStreamEvents.ProposalResult> resultSink) {
        if (conversationId == null) {
            return;
        }
        if (proposalSink != null) {
            proposalSinks.put(conversationId, proposalSink);
        }
        if (resultSink != null) {
            resultSinks.put(conversationId, resultSink);
        }
    }

    /** 对话结束时注销，避免通道泄漏（SSE 流关闭后 sink 不再有用）。 */
    public void unregister(Long conversationId) {
        if (conversationId == null) {
            return;
        }
        proposalSinks.remove(conversationId);
        resultSinks.remove(conversationId);
    }

    /**
     * 推送提案事件。
     *
     * <p>{@code conversationId} 可能为 null（例如内部/测试路径直接构造提案，没有会话上下文）。
     * {@code ConcurrentHashMap} 不接受 null key，会抛 NPE，因此这里先判空：
     * 没有会话就没有可推送的目标，直接按"仅落库"处理。</p>
     *
     * @return 是否成功推送到活跃流
     */
    public boolean publishProposal(Long conversationId, ChatStreamEvents.Proposal payload) {
        if (conversationId == null) {
            log.debug("提案 {} 没有关联会话，仅落库", payload.proposalId());
            return false;
        }
        Sinks.Many<ChatStreamEvents.Proposal> sink = proposalSinks.get(conversationId);
        if (sink == null) {
            // 必须是 warn：这条日志是"模型正文说已生成提案、但用户看不到确认卡"的唯一线索。
            // debug 级别默认不输出，出问题时会被完全淹没（真机排查踩过）。
            log.warn("会话 {} 没有活跃的提案通道，提案 {} 仅落库，本轮前端不会出现确认卡"
                    + "（依赖进入会话 / 本轮结束后的待确认列表兜底）", conversationId, payload.proposalId());
            return false;
        }
        Sinks.EmitResult result = sink.tryEmitNext(payload);
        if (result.isFailure()) {
            log.warn("推送提案事件失败（{}），提案 {} 仅落库，本轮前端不会出现确认卡",
                    result, payload.proposalId());
            return false;
        }
        return true;
    }

    /** 推送提案结果事件。 */
    public boolean publishResult(Long conversationId, Long proposalId, String status,
                                 String message, Long auditId, LocalDateTime executedAt) {
        ChatStreamEvents.ProposalResult payload =
                new ChatStreamEvents.ProposalResult(proposalId, status, message, auditId, executedAt);
        if (conversationId == null) {
            log.debug("提案 {} 没有关联会话，结果仅落库", proposalId);
            return false;
        }
        Sinks.Many<ChatStreamEvents.ProposalResult> sink = resultSinks.get(conversationId);
        if (sink == null) {
            log.debug("会话 {} 没有活跃的结果通道，提案 {} 结果仅落库", conversationId, proposalId);
            return false;
        }
        Sinks.EmitResult result = sink.tryEmitNext(payload);
        if (result.isFailure()) {
            log.debug("推送提案结果事件失败（{}），提案 {} 仅落库", result, proposalId);
            return false;
        }
        return true;
    }
}
