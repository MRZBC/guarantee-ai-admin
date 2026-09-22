package com.guarantee.ai.mapper;

import com.guarantee.ai.entity.AiOperationProposal;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 变更提案 Mapper。
 *
 * <p>{@link #claimForExecution} 是防重复执行的核心（SYS-C-03 / SYS-NF-06）：
 * 用数据库条件更新抢占 {@code PENDING -> EXECUTING}，只有 {@code affectedRows=1}
 * 的那一次请求才能继续执行。前端禁用按钮只是体验优化，真正的唯一性由这里保证。</p>
 */
@Mapper
public interface AiOperationProposalMapper {

    int insert(AiOperationProposal entity);

    AiOperationProposal selectById(@Param("id") Long id);

    AiOperationProposal selectByNo(@Param("proposalNo") String proposalNo);

    List<AiOperationProposal> selectByUserId(@Param("userId") Long userId,
                                             @Param("status") String status,
                                             @Param("limit") int limit);

    /** 同一会话内是否已存在同目标同动作的 PENDING 提案（SYS-W-12）。 */
    List<AiOperationProposal> selectPendingSameTarget(@Param("conversationId") Long conversationId,
                                                      @Param("targetType") String targetType,
                                                      @Param("targetId") Long targetId,
                                                      @Param("action") String action);

    /**
     * 当前用户在指定会话下的待确认提案（SYS-Q-06b）。
     *
     * <p>两个消费方：只读工具 {@code queryMyProposals}（让模型有据可依地引用提案编号）
     * 与回复结束时的兜底校验（声称有提案却没有 PENDING 时追加纠正）。
     * <b>刻意不过滤 {@code expires_at}</b>：过期但未清理的 PENDING 仍要能被看见，
     * 否则"提案确实生成过、只是过期了"会被误判成"从未生成"。</p>
     *
     * @param conversationId 为 null 时退化为该用户的全部待确认提案（防御性分支）
     */
    List<AiOperationProposal> selectPendingByConversation(@Param("userId") Long userId,
                                                          @Param("conversationId") Long conversationId,
                                                          @Param("limit") int limit);

    /**
     * 抢占执行权：仅当状态仍为 PENDING 时置为 EXECUTING。
     *
     * @return affectedRows，必须为 1 才允许继续执行
     */
    int claimForExecution(@Param("id") Long id, @Param("confirmedAt") LocalDateTime confirmedAt);

    /** 终态回写（EXECUTED / FAILED / REJECTED / EXPIRED / INVALIDATED）。 */
    int updateResult(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("resultMessage") String resultMessage,
                     @Param("rejectReason") String rejectReason,
                     @Param("executedAt") LocalDateTime executedAt,
                     @Param("auditId") Long auditId);

    /** 目标版本指纹回写（t9：执行前比对用）。 */
    int updateFingerprint(@Param("id") Long id, @Param("fingerprint") String fingerprint);

    /** 过期清理（T-11）：把到期的 PENDING 置为 EXPIRED，返回受影响行数。 */
    int expireOverdue(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /** 取出一批已过期的提案（用于逐条写审计，SYS-C-10）。 */
    List<AiOperationProposal> selectOverdue(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
