package com.guarantee.ai.service;

import com.guarantee.ai.tool.ProposalNoFormat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * 提案编号白名单校验（提示词第 32 条的**代码级**落实）。
 *
 * <p><b>为什么必须是确定性校验而不是话术启发式</b>：{@link ProposalClaimGuard} 只能发现
 * "声称已生成提案、而会话内一条 PENDING 都没有"。它在自己的文档里写明了边界——
 * 若会话里**确实存在**一条待确认提案，而模型在正文里引用了另一个编造的编号，
 * 它不会命中。真机事故正是这个形态：正文写
 * {@code 待确认提案：提案编号 OP202609242359135602}，而库里该编号不存在。</p>
 *
 * <p><b>判定规则</b>：正文里出现的每一个提案编号，必须属于"本轮可采信集合"——
 * 即本轮工具真实返回过的编号（见 {@code TurnFacts.proposalNumbers()}）∪ 用户本轮原话里
 * 由用户自己打出的编号（把用户给的串原样回显不是编造）。不在集合内的一律**从正文移除**，
 * 并追加一条系统提示说明"该编号不存在、已移除"。</p>
 *
 * <p><b>为什么不改成追问模型</b>：这是事实校验，不是语义判断。移除 + 明示比让模型
 * "再想一次"更可靠，也不会把一次已经完成的回答变成额外一轮对话。</p>
 */
public final class ProposalNumberGuard {

    /**
     * 移除编造编号后追加的纠正文案。
     *
     * <p>刻意**不回显**那个编号：回显等于把它再写一遍，用户更容易记住错的那个。
     * 措辞也不说"系统中不存在"——那是更强的断言，而本类只证明了"它不是本轮工具返回的编号"
     * （编号可能存在于别的会话或别的用户），说准确比说绝对更重要。</p>
     */
    public static final String CORRECTION =
            "（系统提示：本条回复里出现的提案编号不是本轮工具返回的真实编号，已由系统移除。"
                    + "请以确认卡上的编号为准；如需我核对，也可以让我重新查询待确认提案。）";

    private ProposalNumberGuard() {
    }

    /**
     * 本轮可采信的编号集合 = 工具真实返回的编号 ∪ 用户本轮原话里自己打出的编号。
     *
     * <p><b>为什么用户原话也算</b>：用户可能自己贴一个编号问"这个提案还在吗"，
     * 模型如实回一句"该编号不存在"是正确回答。把用户打出的串当成编造移除，会把一次
     * 如实回答改坏——校验的目标是"模型凭记忆/臆想造出来的编号"，不是用户自己写的字。</p>
     *
     * @param userText     用户本轮原话（可为 null）
     * @param toolNumbers  本轮工具真实返回过的编号（可为 null）
     */
    public static Set<String> trusted(String userText, Set<String> toolNumbers) {
        Set<String> trusted = new LinkedHashSet<>(toolNumbers == null ? Set.of() : toolNumbers);
        trusted.addAll(ProposalNoFormat.findAll(userText));
        return trusted;
    }

    /**
     * 校验结果。
     *
     * @param text    移除编造编号后的正文
     * @param removed 被移除的编号（按出现顺序；非空即代表发生了改写）
     */
    public record Result(String text, List<String> removed) {

        public Result {
            removed = List.copyOf(removed);
        }

        /** 是否发生了改写（决定收尾要不要 reset 重发）。 */
        public boolean changed() {
            return !removed.isEmpty();
        }
    }

    /**
     * 移除正文中不在白名单内的提案编号。
     *
     * @param answer  模型正文
     * @param allowed 本轮可采信的编号集合（工具真实返回 + 用户原话），null 视为空集（全不采信）
     */
    public static Result sanitize(String answer, Set<String> allowed) {
        if (answer == null || answer.isBlank()) {
            return new Result(answer == null ? "" : answer, List.of());
        }
        Set<String> trusted = allowed == null ? Set.of() : allowed;
        Matcher matcher = ProposalNoFormat.PATTERN.matcher(answer);
        StringBuilder out = new StringBuilder(answer.length());
        List<String> removed = new ArrayList<>();
        boolean matched = false;
        while (matcher.find()) {
            matched = true;
            String token = matcher.group();
            if (trusted.contains(token)) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(token));
            } else {
                removed.add(token);
                matcher.appendReplacement(out, "");
            }
        }
        if (!matched) {
            // 正文里根本没有编号：原样返回同一个实例，调用方据此判定"没有改写"，不必重发
            return new Result(answer, removed);
        }
        matcher.appendTail(out);
        return new Result(out.toString(), removed);
    }
}
