package com.guarantee.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 「声称有提案但没有提案」的后端兜底校验（SYS-Q-06b 的第二道防线）。
 *
 * <p><b>为什么不能只靠提示词</b>：真机已复现两次——助手正文写
 * 「待确认提案：提案编号 OP2026...…请在确认卡上点击」，而该编号在库里不存在、
 * 当轮也没有任何写工具调用。提示词是"希望模型遵守"，而这类编造一旦发生，
 * 用户会去找一张不存在的卡片、甚至以为变更已经生效。<b>这类错误必须由后端拦住。</b></p>
 *
 * <p><b>判定窗口</b>：本轮回复结束时（{@code AiChatService} 的流收尾），
 * 若正文命中"声称已生成/存在待确认提案"的话术，而当前会话**没有任何 PENDING 提案**，
 * 就追加一条纠正提示。</p>
 *
 * <p><b>匹配必须窄</b>：只在"提案"与明确的完成态动词同句、或明确指向确认卡点击时命中，
 * 并且同一句里出现否定词（"没有/未生成/无法…"）就**不**命中——
 * 宁可漏报（模型换个说法绕过），也不能把"当前没有待确认提案"这类如实回答误伤成编造。</p>
 *
 * <p><b>已知边界（不隐瞒）</b>：本校验只能发现"会话内一条 PENDING 都没有"这一种情况。
 * 若会话里确实存在一条 PENDING 提案、而模型在正文里引用了**另一个**编造的编号，
 * 本校验不会命中——那种情况由只读工具 {@code queryMyProposals} 与提示词
 * "编号只能来自工具返回值"来约束。本校验是兜底，不是完备的编号校验器。</p>
 */
@Component
public class ProposalClaimGuard {

    private static final Logger log = LoggerFactory.getLogger(ProposalClaimGuard.class);

    /**
     * 追加的纠正文案。措辞必须如实、可操作，且不指责用户。
     *
     * <p><b>为什么要把"别拿它当事实"写进去</b>：这段文字不只是给用户看的，它还会**留在会话历史里**
     * 被模型读回去。真机事故（2026-09-24 23:55 → 00:08）：模型编造了一条"角色改名已生成提案"，
     * 系统补了纠正，但下一轮模型**把自己那条编造当成了既成事实**，回复用户
     * 「这个角色现在名称是「行政」（编码 OPER_NO_SYS）」——而库里从未改过名，
     * 用户看到的就是"编码一致、名称不一致"。所以纠正必须同时做两件事：
     * ① 告诉用户它没发生；② 明确禁止把本条回复里的变更描述当作事实，并要求用只读工具重查现状。</p>
     */
    public static final String CORRECTION =
            "（系统提示：本条回复里描述的变更**并未生成**，系统里没有产生任何提案。"
                    + "请不要把本条回复中“已生成 / 已变更 / 已改名 / 已授权”这类说法当成事实；"
                    + "需要确认某个角色、用户或险种现在是什么状态时，必须用只读工具重新查询。"
                    + "当前没有待确认的变更。请重新说明你要做的变更。）";

    /**
     * 声称"提案已完成某个动作"的动词，必须与「提案」同句才计入。
     *
     * <p>不把"生成"单独作为词根：那样会命中"我会生成提案"这类尚未发生的表述。</p>
     */
    private static final List<String> CLAIM_CUES = List.of(
            "已生成", "已经生成", "生成了", "生成了一张", "已创建", "已经创建",
            "已发起", "已经发起", "待确认");

    /** 指向确认卡并要求点击的表述（卡片只有提案生成后才存在）。 */
    private static final List<String> CARD_CUES = List.of("确认卡", "确认卡片");

    /** 与卡片同句出现的动作词。 */
    private static final List<String> CARD_ACTIONS = List.of("点击", "确认执行");

    /** 否定/未完成线索：出现即认定该句是如实回答，不触发纠正。 */
    private static final List<String> NEGATION_CUES = List.of(
            "没有", "未生成", "没生成", "并未", "尚未", "未创建", "没创建",
            "未发起", "没发起", "无法生成", "不能生成", "生成失败", "未成功",
            "不存在", "未调用", "没调用", "不会生成", "不需要生成");

    private final ProposalService proposalService;

    public ProposalClaimGuard(ProposalService proposalService) {
        this.proposalService = proposalService;
    }

    /**
     * 判断本轮回复是否需要追加纠正提示。
     *
     * @return 需要纠正时返回纠正文案，否则返回 {@link Optional#empty()}
     */
    public Optional<String> correctionFor(Long userId, Long conversationId, String answer) {
        if (!claimsProposal(answer)) {
            return Optional.empty();
        }
        boolean hasPending;
        try {
            hasPending = !proposalService.listPendingInConversation(userId, conversationId, 1).isEmpty();
        } catch (RuntimeException ex) {
            // 兜底校验本身失败绝不能影响正常回复（它是补救措施，不是主流程）。
            // 这里选择"不追加纠正"：宁可放过一次编造，也不要因为查库异常让用户看到
            // 一条与本次回复无关的系统提示。
            log.warn("兜底校验查询待确认提案失败，本次跳过纠正 conversationId={}", conversationId, ex);
            return Optional.empty();
        }
        if (hasPending) {
            return Optional.empty();
        }
        log.warn("回复声称有提案但会话内无 PENDING 提案，追加纠正提示 conversationId={} userId={}",
                conversationId, userId);
        return Optional.of(CORRECTION);
    }

    /**
     * 正文是否声称"已生成 / 存在待确认提案"。
     *
     * <p>按句判定而不是整段判定：一段话里既有如实说明又有编造时，
     * 整段判定会因为一个否定词而整体放过。</p>
     *
     * <p><b>两个分支都必须带完成态动词</b>。曾经"确认卡 + 点击"单独就能命中，
     * 依据是"卡片只有提案生成后才存在"——但真机证明该假设不成立：模型**解释机制**时会写
     * 「我再去核对具体权限项并生成变更提案（提案需要在确认卡上点击「确认执行」后才会生效）」，
     * 这是将来时、并未声称生成任何东西，却被判为编造并追加了一句
     * "本次回复提到的提案并未生成"，在**正常回复**后面贴了误导性提示（2026-09-24 实测两轮均误报）。</p>
     *
     * <p>真实故障形如「**我已生成**变更提案，需要在确认卡上点击『确认执行』后才会生效」，
     * 同句含"已生成"，仅靠第一个分支即可命中——因此确认卡分支补上完成态要求后，
     * 对真实故障并无损失，只是不再对"将来会生成"的表述误报。</p>
     */
    static boolean claimsProposal(String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        for (String sentence : answer.split("[。！？!?；;\\n\\r]+")) {
            if (sentence.isBlank() || isNegated(sentence)) {
                continue;
            }
            boolean completed = containsAny(sentence, CLAIM_CUES);
            if (!completed) {
                // 没有完成态动词 = 只是"将要/可以/需要"生成，不是"已生成"。
                // 提前跳过可同时挡住两个分支，避免"确认卡"分支被将来时表述命中。
                continue;
            }
            if (sentence.contains("提案")) {
                return true;
            }
            if (containsAny(sentence, CARD_CUES) && containsAny(sentence, CARD_ACTIONS)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNegated(String sentence) {
        return containsAny(sentence, NEGATION_CUES);
    }

    private static boolean containsAny(String text, List<String> cues) {
        for (String cue : cues) {
            if (text.contains(cue)) {
                return true;
            }
        }
        return false;
    }
}
