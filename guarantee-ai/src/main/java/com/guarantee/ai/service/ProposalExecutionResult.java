package com.guarantee.ai.service;

import java.util.Map;

/**
 * 提案执行结果。
 *
 * @param success       是否成功
 * @param partial       是否部分成功（批量场景）
 * @param message       面向用户的结果说明
 * @param errorMessage  失败原因（成功时为 null）
 * @param before        变更前结构化快照（用于操作审计，SYS-A-02）
 * @param after         变更后结构化快照
 * @param affectedUsers 受影响的用户（需要撤销令牌，SYS-C-07）
 * @param impactNotes   需要向操作者明示的补充说明（例如"该用户需重新登录"）
 */
public record ProposalExecutionResult(
        boolean success,
        boolean partial,
        String message,
        String errorMessage,
        Map<String, Object> before,
        Map<String, Object> after,
        java.util.List<Long> affectedUsers,
        java.util.List<String> impactNotes) {

    public static ProposalExecutionResult ok(String message, Map<String, Object> before,
                                             Map<String, Object> after) {
        return new ProposalExecutionResult(true, false, message, null, before, after,
                java.util.List.of(), java.util.List.of());
    }

    public static ProposalExecutionResult ok(String message, Map<String, Object> before,
                                             Map<String, Object> after,
                                             java.util.List<Long> affectedUsers,
                                             java.util.List<String> impactNotes) {
        return new ProposalExecutionResult(true, false, message, null, before, after,
                affectedUsers, impactNotes);
    }

    public static ProposalExecutionResult failed(String errorMessage) {
        return new ProposalExecutionResult(false, false, null, errorMessage, null, null,
                java.util.List.of(), java.util.List.of());
    }
}
