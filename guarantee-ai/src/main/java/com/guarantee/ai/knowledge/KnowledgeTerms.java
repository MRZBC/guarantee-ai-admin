package com.guarantee.ai.knowledge;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 中文/英文混合问句的**无分词**词项提取（RK-RAG-02 的应对）。
 *
 * <p><b>问题</b>：MySQL 与应用侧都没有中文分词器（本机无 IK/jieba，也没有 ngram 全文索引，
 * 见 REQ-RAG-03 的可选优化）。若只按空格切词，{@code 停用和删除有什么区别} 会变成一个
 * 十几字的整串，永远匹配不上 {@code keywords=停用,删除}。</p>
 *
 * <p><b>做法</b>：把问句切成"连续的汉字串"与"连续的字母数字串"，汉字串再展开为
 * 2-gram 与 3-gram。这样 {@code 停用和删除有什么区别} 会产生 {@code 停用}、
 * {@code 删除} 等词项，稳定命中人工标签。单个汉字不作为词项（噪声远大于收益）。</p>
 *
 * <p>这是**召回**手段，不是语义理解：它不引入任何外部依赖、结果完全确定，
 * 因此可以被单测逐条断言。真源里的 {@code keywords} 仍是主力命中面（REQ-RAG-01）。</p>
 */
public final class KnowledgeTerms {

    /** 最短词项长度：单个汉字/字母太宽泛，只制造噪声。 */
    private static final int MIN_TERM_LENGTH = 2;

    /** 汉字串展开的最大 gram 长度（2-gram 与 3-gram 足够，再长收益递减）。 */
    private static final int MAX_GRAM = 3;

    private KnowledgeTerms() {
    }

    /** 提取词项（去重、保持出现顺序）。 */
    public static List<String> extract(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        StringBuilder run = new StringBuilder();
        boolean cjkRun = false;
        for (int i = 0; i <= query.length(); i++) {
            char c = i < query.length() ? query.charAt(i) : ' ';
            boolean cjk = isCjk(c);
            boolean word = !cjk && Character.isLetterOrDigit(c);
            if (cjk || word) {
                if (run.length() > 0 && cjk != cjkRun) {
                    emit(run.toString(), cjkRun, terms);
                    run.setLength(0);
                }
                cjkRun = cjk;
                run.append(Character.toLowerCase(c));
            } else if (run.length() > 0) {
                emit(run.toString(), cjkRun, terms);
                run.setLength(0);
            }
        }
        return List.copyOf(terms);
    }

    /** 提取并只保留达到最小长度的词项（检索打分用；单元测试也用它断言）。 */
    public static List<String> meaningful(String query) {
        return extract(query).stream().filter(term -> term.length() >= MIN_TERM_LENGTH).toList();
    }

    private static void emit(String run, boolean cjk, Set<String> terms) {
        if (run.isEmpty()) {
            return;
        }
        if (!cjk) {
            terms.add(run);
            return;
        }
        // 汉字串：整串 + 全部 2~3 gram。
        // 短串（如"保费"）整串即 2-gram，展开是幂等的；三字串（如"是多少"）展开后才有"多少"，
        // 否则"某条目标签＝多少"这类命中会凭空丢掉。
        terms.add(run);
        for (int gram = MIN_TERM_LENGTH; gram <= MAX_GRAM; gram++) {
            for (int i = 0; i + gram <= run.length(); i++) {
                terms.add(run.substring(i, i + gram));
            }
        }
    }

    /** 是否为汉字（基本区 + 扩展 A + 兼容区）。 */
    static boolean isCjk(char c) {
        return (c >= '\u4e00' && c <= '\u9fff')
                || (c >= '\u3400' && c <= '\u4dbf')
                || (c >= '\uf900' && c <= '\ufaff');
    }
}
