package com.guarantee.ai.tool;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提案编号的形态定义与提取（服务端唯一真值判定的公共部分）。
 *
 * <p><b>为什么需要它</b>：编号由 {@code ProposalService.generateNo()} 生成，形如
 * {@code OP + yyyyMMddHHmmss（14 位）+ 4 位随机数}。模型编造提案编号是已复现过的真机事故
 * （正文写了一个库里不存在的 {@code OP2026…} 编号），而"这条编号是不是真的"只能由
 * 服务端用"本轮工具真实返回过没有"来判定——判定两侧（工具返回值扫描 / 正文扫描）
 * 必须用同一套形态定义，否则会出现"提取口径不一致 → 真编号被判成假"的误伤。</p>
 *
 * <p><b>为什么放宽到 8 位以上数字</b>：真实编号是 18 位数字，但模型偶尔会写出截断形态
 * （例如少写几位）。放宽下限是为了把这类"看起来像编号"的串也纳入校验，宁可多校验不可漏。
 * 上限不设：编号形态固定，多一位数字只可能来自模型的自由发挥。</p>
 */
public final class ProposalNoFormat {

    /** 提案编号：{@code OP} + 至少 8 位数字（真实形态为 18 位）。 */
    public static final Pattern PATTERN = Pattern.compile("\\bOP\\d{8,}\\b");

    private ProposalNoFormat() {
    }

    /**
     * 提取文本里出现的全部提案编号（保持出现顺序，去重）。
     *
     * @param text 任意文本（null / 空白返回空集合）
     */
    public static Set<String> findAll(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = PATTERN.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }
}
