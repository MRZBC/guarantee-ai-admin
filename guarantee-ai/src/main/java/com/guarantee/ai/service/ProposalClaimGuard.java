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

    /** 追加的纠正文案。措辞必须如实、可操作，且不指责用户。 */
    public static final String CORRECTION =
            "（系统提示：本次回复提到的提案并未生成，当前没有待确认的变更。请重新说明你要做的变更。）";

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
     */
    static boolean claimsProposal(String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        for (String sentence : answer.split("[。！？!?；;\\n\\r]+")) {
            if (sentence.isBlank() || isNegated(sentence)) {
                continue;
            }
            if (sentence.contains("提案") && containsAny(sentence, CLAIM_CUES)) {
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
