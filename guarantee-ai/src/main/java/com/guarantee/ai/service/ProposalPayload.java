package com.guarantee.ai.service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 提案的确认卡载荷（SSE {@code proposal} 事件与 GET 提案详情共用，SYS-C-11）。
 *
 * <p>确认卡必须来自**后端返回的结构化载荷**，不允许前端自行拼装参数——
 * 否则"用户确认的到底是什么"就失去了唯一来源。</p>
 */
public record ProposalPayload(
        Long proposalId,
        String proposalNo,
        /** 来源会话：刷新页面/切回历史会话时用于把确认卡归位到对应会话（SYS-C-14）。 */
        Long conversationId,
        String toolName,
        /** CREATE / UPDATE / ENABLE / DISABLE / ASSIGN_ROLES / ASSIGN_PERMISSIONS */
        String action,
        /** 动作中文名，用于卡片标题。 */
        String actionName,
        String targetType,
        String targetTypeName,
        Long targetId,
        String targetName,
        String summary,
        /** 字段级差异；新增动作只展示"新值"。 */
        List<ChangeItem> changes,
        /** 影响面（高亮文案）。 */
        List<String> impact,
        /** 风险提示。 */
        List<String> warnings,
        /** 是否危险动作（需要二次确认弹窗）。 */
        boolean dangerous,
        /** 用户在对话里说的原话，让用户核对模型有没有理解错（SYS-C-15）。 */
        String userText,
        LocalDateTime expiresAt,
        /** 状态：PENDING / EXECUTED / REJECTED / EXPIRED / INVALIDATED / FAILED。 */
        String status) {

    /** 单个字段的变更明细。 */
    public record ChangeItem(
            String field,
            String label,
            /** 变更前；新增动作时为 null。 */
            String before,
            /** 变更后；停用/清空时为 null。 */
            String after) {
    }
}
