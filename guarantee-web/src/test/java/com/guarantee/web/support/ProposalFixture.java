package com.guarantee.web.support;

import com.guarantee.ai.service.ProposalPayload;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 测试创建的提案 fixture：登记 + 结束时清理。
 *
 * <p><b>为什么必须有它</b>：集成测试跑在**共享的开发库**上。接口
 * {@code GET /ai/proposals?status=PENDING} 不带 {@code conversationId} 时仍是一份跨会话的
 * 待办清单，而**助手面板现在只按当前会话拉取**（见
 * {@code DEC-助手回答的可见性与口径呈现.md} §7.6）——面板这条泄漏路径已堵住，
 * 但测试造的提案留在库里本身就是脏数据，仍必须精确清理。</p>
 *
 * <p>真机故障（本类的由来）：跑完 {@code mvn verify} 之后，使用者只问了一句
 * 「履约保函怎么样」——一个纯查询——界面上却突然冒出两张「停用险种」确认卡。
 * 查库可见这两张提案 {@code conversation_id} 为 {@code NULL}（只有测试会走
 * "无会话"的直接创建路径），{@code user_id} 是 admin，创建时间正好是 IT 运行的那一分钟；
 * 卡片里的「你的原话」与「影响面」都是测试夹具的字面量。用户在那一轮什么变更都没提过。</p>
 *
 * <p><b>为什么用精确登记 id，而不是按条件批量删</b>：批量删（例如"删掉该用户全部 PENDING 提案"）
 * 会连使用者自己真正待确认的提案一起删掉。只删本测试登记的 id，语义无歧义。</p>
 *
 * <p>{@code ai_operation_secret} 一并清理：它是提案的密文暂存（物理删除，见 LD-EX-01），
 * 留下会变成指向已不存在提案的孤儿行。</p>
 */
public class ProposalFixture {

    private final JdbcTemplate jdbcTemplate;
    private final List<Long> createdIds = new ArrayList<>();

    public ProposalFixture(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 登记一张测试创建的提案。
     *
     * <p>用法是把创建调用整个包起来：{@code fixture.track(proposalService.create(draft))}——
     * 这样"创建了但忘了登记"在视觉上就很难发生。</p>
     *
     * @return 原样返回入参，便于链式书写
     */
    public ProposalPayload track(ProposalPayload payload) {
        if (payload != null && payload.proposalId() != null) {
            createdIds.add(payload.proposalId());
        }
        return payload;
    }

    /** 删除本测试创建的全部提案。幂等，可重复调用。 */
    public void cleanUp() {
        if (createdIds.isEmpty()) {
            return;
        }
        String placeholders = createdIds.stream().map(id -> "?").collect(Collectors.joining(","));
        Object[] ids = createdIds.toArray();
        jdbcTemplate.update("DELETE FROM ai_operation_secret WHERE proposal_id IN ("
                + placeholders + ")", ids);
        jdbcTemplate.update("DELETE FROM ai_operation_proposal WHERE id IN ("
                + placeholders + ")", ids);
        createdIds.clear();
    }
}
