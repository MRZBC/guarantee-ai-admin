package com.guarantee.ai.tool;

import java.util.List;

/**
 * {@code queryMyProposals} 的工具返回值（SYS-Q-06b）。
 *
 * <p><b>它存在的唯一理由：让"有没有待确认提案"成为可查的事实</b>。
 * 真机证据：助手正文写「待确认提案：提案编号 OP2026...…请在确认卡上点击」，
 * 而该编号在库里不存在、当轮一次工具调用都没有——根因是模型没有任何手段知道
 * 提案是否存在，只能照抄上一轮的真实编号并改尾数。</p>
 *
 * <p>字段刻意与确认卡/提案表同构（{@code proposalId} / {@code proposalNo} / {@code action} /
 * {@code targetType} / {@code targetName} / {@code status} / {@code expiresAt}），
 * 使模型在正文里引用时不需要任何换算或推断：直接抄，抄错就会被照抄成假编号。</p>
 *
 * <p>不含 {@code requestPayload} / {@code previewPayload}：前者可能含敏感参数占位符，
 * 后者是给卡片渲染的结构化明细，模型只需要"是哪一条、什么动作、对谁、还能不能确认"。</p>
 */
public record MyProposalsToolResult(
        /** 本次返回的待确认提案条数。 */
        long total,
        /** 是否按会话收敛（false 只在会话号缺失的防御性场景出现）。 */
        boolean conversationScoped,
        List<MyProposalItem> items,
        ToolResultMeta meta) {

    /** 单条待确认提案。 */
    public record MyProposalItem(
            Long proposalId,
            String proposalNo,
            /** CREATE / UPDATE / ENABLE / DISABLE / DELETE / RESTORE / ASSIGN_ROLES / ASSIGN_PERMISSIONS */
            String action,
            /** 动作中文名，正文可直接引用。 */
            String actionName,
            /** USER / ORG / DEPT / ROLE / INSURANCE_TYPE */
            String targetType,
            /** 目标类型中文名。 */
            String targetTypeName,
            Long targetId,
            String targetName,
            /** 恒为 PENDING（本工具只返回待确认提案）。 */
            String status,
            String expiresAt,
            /** 已过期：此时确认会被拒绝，必须重新发起，不能再说"请在确认卡上点击"。 */
            boolean expired,
            /** 生成该提案的写工具名，例如 proposeInsuranceTypeChange。 */
            String toolName) {
    }

    public static MyProposalsToolResult denied(String reason) {
        return new MyProposalsToolResult(0L, true, List.of(), ToolResultMeta.denied(reason));
    }
}
