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
 * <h2>两段式：宽口径扫描 + 归一化判定（R2 修"形态逃逸"）</h2>
 * <p>只用一条严格正则（{@code \bOP\d{8,}\b}）会漏掉大量"看起来就是同一个编号"的写法：
 * 小写 {@code op…}、全角 {@code ＯＰ…}、{@code OP-2026-…}、{@code OP 2026…}、
 * 零宽字符插入、词边界被破坏等——实测 20 种写法里只有 10 种被识别，另外 10 种**逃逸存活**。
 * 直接放宽正则会带来另一个风险：**误删模型如实回显的真编号**。</p>
 *
 * <p>因此改成两段式：</p>
 * <ol>
 *   <li><b>{@link #CANDIDATE_PATTERN} 宽口径扫描</b>：先把"大小写/全角/零宽/分隔符"这些
 *       **表面差异**都收进来，拿到**原文里的 span**；</li>
 *   <li><b>{@link #canonical} 归一化判定</b>：比对与形态判定都用归一值
 *       （全角转半角、统一大写、丢掉零宽/空白/分隔符）；
 *       **删除永远用第 1 步拿到的原文 span**——绝不能用归一值去替换文本，否则会错删。</li>
 * </ol>
 *
 * <p><b>为什么比对上界是"归一化后完全相等"而不是"包含"</b>：等于才说明模型写的就是那一个编号；
 * 包含会把"真编号后多打了几个数字"也放行，那正是编造的常见形态。</p>
 */
public final class ProposalNoFormat {

    /**
     * 严格形态（历史口径）：只有半角大写 {@code OP} + 紧邻的 8 位以上数字才算。
     *
     * <p><b>保留它只为对照与反证</b>（测试里用它证明"退回旧口径这条断言会红"），
     * 生产判定已改用 {@link #CANDIDATE_PATTERN} + {@link #canonical}。</p>
     */
    public static final Pattern STRICT_PATTERN = Pattern.compile("\\bOP\\d{8,}\\b");

    /**
     * 候选形态（宽口径扫描）：{@code OP} 的大小写/全角写法 + 允许的零宽与分隔符 + 至少 8 位数字。
     *
     * <p>允许的"表面差异"：数字/字母的全角写法、零宽字符（{@code U+200B..U+200D}、{@code U+FEFF}）、
     * 空白、以及 {@code - _ – — ·} 这类分隔符。**刻意不含**：省略号 {@code …}
     * （{@code OP2026…258712} 是"省略中间"的写法，不等于任何编号）与小数点 {@code .}
     * （否则正文里的 {@code OP 3.14159265} 会被拼成 9 位数字而误判成编号）。</p>
     *
     * <p>扫描出来只是"候选"：是否真算编号由 {@link #isProposalNumberShape} 用归一值判定。</p>
     */
    public static final Pattern CANDIDATE_PATTERN = Pattern.compile(
            "[OoＯｏ][PpＰｐ][\\s\\u200B\\u200C\\u200D\\uFEFF\\-‐‑‒–—_\\u00B7]*"
                    + "[0-9０-９](?:[\\s\\u200B\\u200C\\u200D\\uFEFF\\-‐‑‒–—_·]*[0-9０-９]){7,}");

    /**
     * 归一化后的编号形态：{@code OP} + 至少 8 位数字（真实形态 18 位）。
     *
     * <p>8 位下限是**既有决策**（第三阶段）：真实编号 18 位，但模型偶尔会写出截断形态，
     * 放宽下限是为了把"看起来像编号"的串也纳入校验——宁可多校验不可漏。</p>
     */
    public static final Pattern CANONICAL_PATTERN = Pattern.compile("OP\\d{8,}");

    private ProposalNoFormat() {
    }

    /**
     * 归一化：全角 → 半角、统一大写、**只保留字母与数字**（丢掉零宽、空白、各类分隔符）。
     *
     * <p>它只用于**比对与形态判定**，绝不能拿它的结果去替换原文（那会把用户看到的分隔符、
     * 全角写法一起改掉，还可能错删）。</p>
     */
    public static String canonical(String token) {
        if (token == null || token.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(token.length());
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c >= '０' && c <= '９') {
                c = (char) ('0' + (c - '０'));          // 全角数字
            } else if (c >= 'Ａ' && c <= 'Ｚ') {
                c = (char) ('A' + (c - 'Ａ'));          // 全角大写字母
            } else if (c >= 'ａ' && c <= 'ｚ') {
                c = (char) ('A' + (c - 'ａ'));          // 全角小写字母
            }
            char upper = Character.toUpperCase(c);
            if ((upper >= 'A' && upper <= 'Z') || (upper >= '0' && upper <= '9')) {
                out.append(upper);
            }
        }
        return out.toString();
    }

    /** 候选串是否**真的是**提案编号形态（用归一值判定，见 {@link #CANONICAL_PATTERN}）。 */
    public static boolean isProposalNumberShape(String token) {
        return CANONICAL_PATTERN.matcher(canonical(token)).matches();
    }

    /**
     * 提取文本里出现的全部提案编号（**原文写法**，保持出现顺序、去重）。
     *
     * <p>返回原文而不是归一值：调用方（工具返回值扫描 / 用户原话扫描）把它当"可信集合"用，
     * 而可信集合在 {@link com.guarantee.ai.service.ProposalNumberGuard#trusted} 里会统一归一化。</p>
     *
     * @param text 任意文本（null / 空白返回空集合）
     */
    public static Set<String> findAll(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = CANDIDATE_PATTERN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            if (isProposalNumberShape(token)) {
                found.add(token);
            }
        }
        return found;
    }

    /** 文本里全部提案编号的**归一值**（比对用；顺序去重）。 */
    public static Set<String> findAllCanonical(String text) {
        Set<String> canonical = new LinkedHashSet<>();
        for (String token : findAll(text)) {
            canonical.add(canonical(token));
        }
        return canonical;
    }
}
