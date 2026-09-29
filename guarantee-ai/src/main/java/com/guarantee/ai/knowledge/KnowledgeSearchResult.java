package com.guarantee.ai.knowledge;

import java.util.List;

/**
 * 一次知识检索的结果（REQ-RAG-03）。
 *
 * <p>{@code dataSource} 是服务端产出的**事实文本**，会被工具返回、并由提示词要求模型
 * 在"未收录"时如实转述（AC-RAG-03）。文本规则集中在本类的 {@link #of} 里，
 * 好处是只有一处口径：改文案不会出现"工具说未收录、摘要说命中 0 条"这种自相矛盾。</p>
 *
 * <p>{@code truncated} 表示"命中条数多于返回条数"或"字节预算被触顶"。
 * 与既有工具一样，模型必须如实说明"只看了前 N 条"（REQ-RAG-05 的边界规则）。</p>
 */
public record KnowledgeSearchResult(
        List<KnowledgeHit> items,
        String dataSource,
        boolean truncated,
        int totalHits,
        KnowledgeDomain domain) {

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /** 服务端来源行（多条用「；」连接，与 REQ-RAG-04 示例一致）；无命中返回空串。 */
    public String sourceLine() {
        if (items.isEmpty()) {
            return "";
        }
        return "知识来源：" + String.join("；", items.stream().map(KnowledgeHit::sourceFragment).toList());
    }

    /** 按统一的文案规则组装结果。 */
    public static KnowledgeSearchResult of(List<KnowledgeHit> items, boolean truncated,
                                           int totalHits, KnowledgeDomain domain) {
        List<KnowledgeHit> safeItems = List.copyOf(items);
        String suffix = domain == null ? "" : "（域：" + domain.name() + "）";
        String dataSource;
        if (totalHits == 0) {
            dataSource = "知识库：未收录" + suffix;
        } else if (truncated) {
            dataSource = "知识库：命中 " + totalHits + " 条，返回前 " + safeItems.size() + " 条" + suffix;
        } else {
            dataSource = "知识库：命中 " + safeItems.size() + " 条" + suffix;
        }
        return new KnowledgeSearchResult(safeItems, dataSource, truncated, totalHits, domain);
    }

    /** 没有发起有效检索（例如未提供关键词）时的结果，同样"不编造"。 */
    public static KnowledgeSearchResult notSearched(String reason, KnowledgeDomain domain) {
        return new KnowledgeSearchResult(List.of(), "知识库：" + reason, false, 0, domain);
    }
}
