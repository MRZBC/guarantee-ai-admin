package com.guarantee.ai.knowledge;

import com.guarantee.ai.knowledge.entity.AiKnowledgeItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 检索打分与排序（REQ-RAG-03）。
 *
 * <p><b>排序口径（逐字对应需求）</b>：标签命中数 &gt; 标题命中 &gt; 正文命中；
 * 同分时"生效中"优先、版本新优先、{@code knowledge_no} 稳定兜底。</p>
 *
 * <ul>
 *   <li>"生效中优先"由调用方（{@link KnowledgeService}）保证：未在生效期内的条目
 *       根本不进候选集，因此排序器不再重复判定（**若未来要把过期条目纳入候选，
 *       必须在这里补一层生效判定**，否则排序规则会与需求不一致）；</li>
 *   <li>三个命中维度用**元组比较**而不是加权求和：加权求和会出现"标题命中 4 次压过
 *       标签命中 1 次"，与需求规定的优先级相反（这正是同值口径分叉的经典形态：
 *       权重一改，排序语义就悄悄变了）。</li>
 * </ul>
 */
public final class KnowledgeRanking {

    private KnowledgeRanking() {
    }

    /** 一个候选条目的命中明细。 */
    public record Scored(AiKnowledgeItem item, int keywordHits, int titleHits, int contentHits) {
    }

    /** 命中数全为 0 的候选不属于检索结果。 */
    public static List<Scored> rank(List<AiKnowledgeItem> candidates, String query) {
        List<String> terms = KnowledgeTerms.meaningful(query);
        if (terms.isEmpty() || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<Scored> scored = new ArrayList<>(candidates.size());
        for (AiKnowledgeItem item : candidates) {
            Scored hit = score(item, terms);
            if (hit != null) {
                scored.add(hit);
            }
        }
        scored.sort(Comparator
                .comparingInt(Scored::keywordHits).reversed()
                .thenComparing(Comparator.comparingInt(Scored::titleHits).reversed())
                .thenComparing(Comparator.comparingInt(Scored::contentHits).reversed())
                .thenComparing(Comparator.comparingInt((Scored s) -> version(s.item())).reversed())
                .thenComparing((Scored s) -> s.item().getKnowledgeNo()));
        return scored;
    }

    /** 单个条目的命中明细；完全未命中返回 {@code null}。 */
    static Scored score(AiKnowledgeItem item, List<String> terms) {
        if (item == null) {
            return null;
        }
        int keywordHits = 0;
        for (String keyword : normalizeKeywords(item.getKeywords())) {
            if (keyword.length() < 2) {
                continue;
            }
            for (String term : terms) {
                if (contains(keyword, term) || contains(term, keyword)) {
                    keywordHits++;
                    break;
                }
            }
        }
        String title = lower(item.getTitle());
        String content = lower(item.getContent());
        int titleHits = 0;
        int contentHits = 0;
        for (String term : terms) {
            if (title.contains(term)) {
                titleHits++;
            }
            if (content.contains(term)) {
                contentHits++;
            }
        }
        if (keywordHits == 0 && titleHits == 0 && contentHits == 0) {
            return null;
        }
        return new Scored(item, keywordHits, titleHits, contentHits);
    }

    private static int version(AiKnowledgeItem item) {
        return item.getVersion() == null ? 0 : item.getVersion();
    }

    private static List<String> normalizeKeywords(String keywords) {
        if (keywords == null || keywords.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : keywords.split("[,，、;；]")) {
            String tag = lower(part.strip());
            if (!tag.isEmpty()) {
                result.add(tag);
            }
        }
        return result;
    }

    private static boolean contains(String haystack, String needle) {
        return !needle.isEmpty() && haystack.contains(needle);
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
