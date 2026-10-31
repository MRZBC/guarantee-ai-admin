package com.guarantee.ai.service;

import java.util.Optional;
import java.util.regex.Pattern;

/** 写操作仅通过独立确认接口执行；聊天正文不能替代执行结果。 */
public final class ExecutionClaimGuard {
    public static final String CORRECTION = "（系统提示：这段回复没有提供可核验的执行结果，请勿据此认为变更已完成。请以确认卡的执行状态和审计记录为准。）";
    private static final Pattern COMPLETED = Pattern.compile(
            "(?:已(?:经|成功)?(?:完成|执行|停用|启用|删除|恢复|修改|变更|改名|授权|处理)|(?:修改|变更|操作|处理|执行)(?:已)?(?:成功|完成|生效))");
    private static final Pattern NEGATED = Pattern.compile("没有|尚未|并未|未成功|未完成|未执行|不能|无法|失败|如果|假如|例如|比如|确认后|执行后|才会|需要先|等待");

    private ExecutionClaimGuard() {}

    public static Optional<String> correctionFor(String answer) {
        return correctionFor(answer, false, false);
    }

    public static Optional<String> correctionFor(String answer, boolean querySucceeded, boolean toolFailed) {
        return correctionFor(answer, querySucceeded, toolFailed, java.util.Set.of());
    }

    public static Optional<String> correctionFor(String answer, boolean querySucceeded, boolean toolFailed,
                                                  java.util.Set<String> literalValues) {
        if (answer == null || answer.isBlank()) return Optional.empty();
        // 只排除本轮工具真实返回的名称/差异值，不能把任意引号内的声明放行。
        String prose = answer.replace("显示已删除", "删除记录筛选");
        for (String value : literalValues.stream().sorted(java.util.Comparator.comparingInt(String::length).reversed()).toList()) {
            String literal = Pattern.quote(value);
            prose = prose.replaceAll("[「『“\"`‘]" + literal + "[」』”\"`’]", "字段值");
            prose = prose.replaceAll("((?:角色|名称|原值|新值)[：:]\\s*|改为\\s*)" + literal, "$1字段值");
        }
        prose = prose.replaceAll("(?:已删除|已停用|已启用|已恢复)(?:的|记录|数据|标记)", "状态描述");
        for (String sentence : prose.split("[。！？!?；;\\n\\r]+|但是|不过|但(?=已|我|本次|此次)")) {
            // 规则描述不是本次执行声明；显式动作完成措辞不能因同句有规则词被放行。
            boolean explicitAction = sentence.matches(".*(?:本次|此次|我已|已成功|操作已|变更已|已修改|已执行|已完成).*?");
            boolean rule = sentence.matches(".*(?:可以(?:既|同时)|可能(?:既|同时)|意味着|表示|是指|区别|规则|定义|被标记为).*?");
            if (rule && !explicitAction) continue;
            // 查询得到的既有状态与“本次已执行”不同；查询工具不能证明完成了变更。
            boolean existingState = sentence.matches(".*(?:当前|目前|查询显示|查询结果|原本|此前).*?(?:状态|已停用|已启用|已删除|已恢复).*?")
                    && !sentence.matches(".*(?:本次|此次|我已|已成功|操作成功|变更已生效).*?");
            if (querySucceeded && !toolFailed && existingState) continue;
            for (String clause : sentence.split("[，,]+")) {
                if (COMPLETED.matcher(clause).find() && !NEGATED.matcher(clause).find()) {
                    return Optional.of(CORRECTION);
                }
            }
        }
        return Optional.empty();
    }
}
