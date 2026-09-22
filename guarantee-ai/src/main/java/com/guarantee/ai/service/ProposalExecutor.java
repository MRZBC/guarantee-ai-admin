package com.guarantee.ai.service;

import com.guarantee.ai.entity.AiOperationProposal;

/**
 * 提案执行器（SYS-W-00 的 ⑥ 步）。
 *
 * <p><b>为什么单独抽一个端口</b>：{@code ProposalService} 负责提案的状态机与审计，
 * 各域的"怎么改数据"分散在 guarantee-system 的 Service 里。抽成端口后，
 * 新增域（例如 D-2a 的"新建账号"）只需要新增一个实现，不需要改动提案机制——
 * 这正是需求 5.2.2 所要求的"预留扩展点"。</p>
 */
public interface ProposalExecutor {

    /** 本执行器支持的目标类型：USER / ORG / DEPT / ROLE / INSURANCE_TYPE。 */
    String targetType();

    /**
     * 执行提案。
     *
     * <p>实现必须是**幂等安全**的：调用方已经用条件更新抢占过执行权，
     * 但实现内部仍应使用条件更新（T-08/T-09），避免并发覆盖。</p>
     *
     * <p>执行期必须**重新判定**业务状态（SYS-C-05）：例如"停用最后一个 ADMIN"
     * 要在确认时重新算一遍，不能沿用生成提案时的结论。</p>
     *
     * @param proposal 已抢占执行权的提案（状态为 EXECUTING）
     * @param request  模型解析后的参数（反序列化自 request_payload）
     * @param context  操作者身份与数据范围
     */
    ProposalExecutionResult execute(AiOperationProposal proposal,
                                    ProposalRequest request,
                                    ProposalExecutionContext context);
}
