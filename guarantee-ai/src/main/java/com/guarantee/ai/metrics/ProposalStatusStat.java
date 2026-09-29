package com.guarantee.ai.metrics;

import lombok.Data;

/**
 * 提案状态计数（兑现 {@code SYS-NF-08}：提案数按状态 / 确认率 / 拒绝率 / 过期率 / 执行失败率）。
 *
 * <p>数据来自 {@code ai_operation_proposal}（提案的唯一真源），指标只做聚合与趋势，
 * 不替代审计、也不新建一份"提案状态"。</p>
 */
@Data
public class ProposalStatusStat {

    /** PENDING / EXECUTING / EXECUTED / REJECTED / EXPIRED / INVALIDATED / FAILED。 */
    private String status;
    /** 该状态下的提案数（列别名 status_count）。 */
    private Long statusCount;
}
