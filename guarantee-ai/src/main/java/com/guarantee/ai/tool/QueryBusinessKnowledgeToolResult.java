package com.guarantee.ai.tool;

import com.guarantee.ai.knowledge.KnowledgeHit;
import com.guarantee.ai.knowledge.KnowledgeSearchResult;

import java.util.List;

/**
 * {@code queryBusinessKnowledge} 的返回值（SYS-Q-07：工具返回值必须是 record）。
 *
 * <p>形状与 REQ-RAG-03 一致：{@code {items:[{knowledgeNo,title,content,version,effectiveRange,sourceRef}],
 * meta:{dataSource,truncated,…}}}。{@code meta} 复用既有的 {@link ToolResultMeta}，
 * 因此 {@code denied} / {@code truncatedHint} / {@code dataSource} 的语义与其它工具完全相同
 * ——模型不需要学第二套约定。</p>
 *
 * <p><b>为什么沿用 {@code dataSource} 这个键名</b>：{@code TurnFacts} 会按既有规则
 * 自动抓取任意层级的 {@code dataSource}，这样知识工具无需改共享的 {@code TurnFacts}
 * 就能进入"本轮工具事实"；而收尾时服务端会把这些知识类 dataSource 从**口径行**里
 * 精确剔除（口径 = 数据来源），交给「知识来源：」行单独呈现（REQ-RAG-04-5）。</p>
 *
 * <p><b>空结果不编造</b>：未命中时 {@code items} 为空、{@code meta.dataSource} 为
 * 「知识库：未收录」，工具不回填任何内容——模型据此如实说"未收录"（AC-RAG-03）。</p>
 */
public record QueryBusinessKnowledgeToolResult(List<KnowledgeHit> items, ToolResultMeta meta) {

    public static QueryBusinessKnowledgeToolResult of(KnowledgeSearchResult result) {
        List<KnowledgeHit> items = result.items();
        ToolResultMeta meta = result.truncated()
                ? ToolResultMeta.truncated(result.dataSource(),
                "命中条数多于返回条数，或被结果的字节预算截断：请如实说明只看了前 "
                        + items.size() + " 条，不要把未返回的条目当成不存在或全部。")
                : ToolResultMeta.ok(result.dataSource());
        return new QueryBusinessKnowledgeToolResult(items, meta);
    }
}
