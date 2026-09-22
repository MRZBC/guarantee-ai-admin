package com.guarantee.ai.service;

import com.guarantee.ai.entity.AiOperationProposal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 提案执行失败的痕迹记录（独立事务）。
 *
 * <p><b>为什么必须独立成 Bean</b>：{@code ProposalService#confirm} 在业务执行失败时会把
 * **自己所在的事务**标记为回滚，以保证"失败不留半成品"（例如停用只改了一半）。
 * 但 SYS-A-01 又要求失败也必须可追溯——如果 FAILED 审计也写在同一个事务里，
 * 它会随业务回滚一起消失，审计上就只剩"这个提案曾生成过"，看不出它执行过并失败了。</p>
 *
 * <p>解决方式：本类用 {@code REQUIRES_NEW} 开启独立事务，挂在
 * {@code ProposalService} 之外的 Bean 上（Spring 的事务代理对同类内部调用不生效，
 * 因此不能写成私有方法）。这样业务回滚、失败痕迹保留，两者互不干扰。</p>
 */
@Component
public class ProposalFailureRecorder {

    private static final Logger log = LoggerFactory.getLogger(ProposalFailureRecorder.class);

    private final OperationAuditService auditService;
    private final AiConversationService conversationService;

    public ProposalFailureRecorder(OperationAuditService auditService,
                                   AiConversationService conversationService) {
        this.auditService = auditService;
        this.conversationService = conversationService;
    }

    /**
     * 记录一次执行失败的**失败痕迹**：写 FAILED 审计 + 审计日志。
     *
     * <p><b>刻意不在这里改写提案行</b>：调用方的事务虽然被标记为 rollback-only，
     * 但尚未回滚，仍持有 {@code ai_operation_proposal} 该行的行锁。若本方法（独立事务）
     * 去 UPDATE 同一行，就会与调用方互等直到锁超时——表现为确认接口卡住 30 秒以上。
     * 因此提案终态由调用方在回滚后自己回写（那时锁已释放），本方法只负责"留下痕迹"
     * 这件必须独立事务完成的事。</p>
     *
     * @param proposal 已抢占执行权的提案
     * @param context  操作者上下文
     * @param result   失败结果（含 errorMessage）
     * @return 写入的审计 id；失败时返回 null
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long recordFailure(AiOperationProposal proposal, ProposalExecutionContext context,
                              ProposalExecutionResult result) {
        String message = result.errorMessage() == null ? "执行失败" : result.errorMessage();
        try {
            Long auditId = auditService.record(
                    new OperationAuditService.AuditEntry("AI", proposal.getAction(), proposal.getTargetType(),
                            proposal.getTargetId(), proposal.getTargetName(), null, null,
                            "FAILED", message, Set.of()),
                    new OperationAuditService.OperatorContext(context.userId(), context.username(),
                            context.realName()),
                    proposal.getId(), proposal.getConversationId(), proposal.getTraceId());

            conversationService.audit(proposal.getConversationId(), context.userId(), "OPERATION_FAILED",
                    "proposalNo=" + proposal.getProposalNo() + " error=" + message, proposal.getTraceId());
            log.info("提案失败痕迹已记录 id={} auditId={}", proposal.getId(), auditId);
            return auditId;
        } catch (RuntimeException ex) {
            // 失败痕迹记录本身失败时不能抛出：否则会把"业务失败"变成"接口 500"，
            // 掩盖真正的原因
            log.error("记录提案失败痕迹时出错 proposalId={}", proposal.getId(), ex);
            return null;
        }
    }
}
