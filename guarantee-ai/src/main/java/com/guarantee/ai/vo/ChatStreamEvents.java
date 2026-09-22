package com.guarantee.ai.vo;

import com.guarantee.ai.service.ProposalPayload;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SSE 事件载荷定义。
 *
 * <p>与前端 {@code src/utils/chatStream.ts} 约定的事件协议一一对应：
 * meta / delta / tool_call / reset / proposal / proposal_result / done / error。</p>
 *
 * <p>⚠️ 新增事件必须同步修改前端 {@code chatStream.ts} 的 {@code switch}：
 * 当前实现的 {@code default} 分支会把未知事件丢到 {@code onUnknown} 静默忽略，
 * 不改前端就会出现"后端推了、界面没反应"（SYS-NF-10 要求旧前端不崩溃，
 * 但这不代表新前端可以漏接）。</p>
 */
public final class ChatStreamEvents {

    private ChatStreamEvents() {
    }

    /** 首帧：告知会话标识。 */
    public record Meta(Long conversationId, String conversationNo, String title) {
    }

    /** 正文增量。 */
    public record Delta(String content) {
    }

    /**
     * 丢弃本轮已流式显示的正文。
     *
     * <p>模型在发起工具调用前常会先说一句「我这就去查…」式的前言（例如
     * {@code I'll query the tender order statistics for August 2026}）。
     * 这段正文属于中间过程，已实时转发给了前端，但不计入最终回答；
     * 因此进入下一轮前补发本事件，让前端把这段前言清掉。</p>
     */
    public record Reset() {
    }

    /**
     * 写工具成功生成提案（5.3.1）。
     *
     * <p>载荷与 {@link ProposalPayload} 同构，前端直接用结构化数据渲染确认卡，
     * 不允许自行拼装参数（SYS-C-11）。</p>
     */
    public record Proposal(
            Long proposalId,
            String proposalNo,
            /** 来源会话：前端据此把确认卡归位到对应会话（SYS-C-14）。 */
            Long conversationId,
            String toolName,
            String action,
            String actionName,
            String targetType,
            String targetTypeName,
            Long targetId,
            String targetName,
            String summary,
            List<ProposalPayload.ChangeItem> changes,
            List<String> impact,
            List<String> warnings,
            boolean dangerous,
            String userText,
            LocalDateTime expiresAt) {

        public static Proposal from(ProposalPayload payload) {
            return new Proposal(payload.proposalId(), payload.proposalNo(), payload.conversationId(),
                    payload.toolName(), payload.action(), payload.actionName(), payload.targetType(),
                    payload.targetTypeName(), payload.targetId(), payload.targetName(),
                    payload.summary(), payload.changes(), payload.impact(), payload.warnings(),
                    payload.dangerous(), payload.userText(), payload.expiresAt());
        }
    }

    /**
     * 提案被确认/拒绝/过期/失效后（5.3.1）。
     *
     * <p>若提案确认时 SSE 流已关闭，服务端会把同样的信息落成一条会话消息，
     * 用户下次进入会话仍能看到结果（SYS-C-08）。</p>
     */
    public record ProposalResult(
            Long proposalId,
            /** EXECUTED / FAILED / REJECTED / EXPIRED / INVALIDATED */
            String status,
            String message,
            Long auditId,
            LocalDateTime executedAt) {
    }

    /** 正常结束。 */
    public record Done(Long conversationId, Long messageId) {
    }

    /** 异常结束。 */
    public record Error(String message) {
    }
}

