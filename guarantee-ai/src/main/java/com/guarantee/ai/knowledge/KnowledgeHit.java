package com.guarantee.ai.knowledge;

import com.guarantee.ai.knowledge.entity.AiKnowledgeItem;

import java.time.LocalDate;

/**
 * 检索命中的一条知识（REQ-RAG-03 的出参形状）。
 *
 * <p>字段与需求逐项对应：{@code knowledgeNo / title / content / version /
 * effectiveRange / sourceRef}（另带 {@code domain} 便于服务端摘要与排查）。
 * 正文里的条目号与标题**必须逐字来自这里**——服务端的来源行由本类产出
 * （见 {@link #sourceLine()}），模型自写的一律剥离（REQ-RAG-04）。</p>
 */
public record KnowledgeHit(
        String knowledgeNo,
        String title,
        String content,
        int version,
        String effectiveRange,
        String sourceRef,
        KnowledgeDomain domain) {

    public static KnowledgeHit from(AiKnowledgeItem item) {
        return new KnowledgeHit(
                item.getKnowledgeNo(),
                item.getTitle(),
                item.getContent(),
                item.getVersion() == null ? 1 : item.getVersion(),
                effectiveRange(item.getEffectiveFrom(), item.getEffectiveTo()),
                item.getSourceRef(),
                item.getDomain());
    }

    /** 生效期展示文案：两端都可空，空表示"不限/长期有效"。 */
    public static String effectiveRange(LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return "长期有效";
        }
        return (from == null ? "不限" : from.toString())
                + " ~ "
                + (to == null ? "不限" : to.toString());
    }

    /**
     * 服务端产出的**单条**来源片段：{@code KB-SYSTEM-0011《停用与删除的区别》v2}。
     *
     * <p>格式与 REQ-RAG-04 的示例逐字一致；调用方负责加上行首的 {@code 知识来源：}
     * 与多条之间的 {@code ；} 分隔（见 {@link KnowledgeSearchResult#sourceLine()}）。</p>
     */
    public String sourceFragment() {
        return knowledgeNo + "《" + title + "》v" + version;
    }

    /** 单条来源行（含行首标签）。 */
    public String sourceLine() {
        return "知识来源：" + sourceFragment();
    }

    /** 字节预算触顶时的"截断副本"（编号/标题/版本不变，只有正文变短）。 */
    public KnowledgeHit withContent(String newContent) {
        return new KnowledgeHit(knowledgeNo, title, newContent, version, effectiveRange, sourceRef, domain);
    }
}
