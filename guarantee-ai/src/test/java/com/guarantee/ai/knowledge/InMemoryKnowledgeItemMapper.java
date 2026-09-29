package com.guarantee.ai.knowledge;

import com.guarantee.ai.knowledge.entity.AiKnowledgeItem;
import com.guarantee.ai.knowledge.mapper.AiKnowledgeItemMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单测用的内存版知识条目 Mapper。
 *
 * <p>不碰数据库，但保留"按 {@code knowledge_no} upsert"的语义、SQL 里已有的
 * 状态/域过滤、以及调用计数——导入器的幂等性断言（"第二次导入一次写都没有"）
 * 正是靠<b>计数</b>来判定的，mock 掉计数这个测试就没有意义了。</p>
 */
final class InMemoryKnowledgeItemMapper implements AiKnowledgeItemMapper {

    private final Map<String, AiKnowledgeItem> rows = new LinkedHashMap<>();
    private long nextId = 1;

    int insertCount;
    int updateContentCount;
    int updateStatusCount;

    /** 预置一行（模拟"库里已有"）。 */
    void seed(AiKnowledgeItem item) {
        AiKnowledgeItem copy = copyOf(item);
        if (copy.getId() == null) {
            copy.setId(nextId++);
        }
        rows.put(copy.getKnowledgeNo(), copy);
    }

    AiKnowledgeItem get(String knowledgeNo) {
        return rows.get(knowledgeNo);
    }

    int size() {
        return rows.size();
    }

    @Override
    public List<AiKnowledgeItem> selectAllActive() {
        return rows.values().stream().map(InMemoryKnowledgeItemMapper::copyOf).toList();
    }

    @Override
    public AiKnowledgeItem selectByKnowledgeNo(String knowledgeNo) {
        AiKnowledgeItem item = rows.get(knowledgeNo);
        return item == null ? null : copyOf(item);
    }

    @Override
    public List<AiKnowledgeItem> selectSearchCandidates(String domain, String status) {
        KnowledgeStatus expected = status == null ? null : KnowledgeStatus.valueOf(status);
        return rows.values().stream()
                .filter(item -> expected == null || item.getStatus() == expected)
                .filter(item -> domain == null || domain.equals(item.getDomain() == null ? null : item.getDomain().name()))
                .sorted(Comparator.comparing(AiKnowledgeItem::getKnowledgeNo))
                .map(InMemoryKnowledgeItemMapper::copyOf)
                .toList();
    }

    @Override
    public int insert(AiKnowledgeItem item) {
        insertCount++;
        AiKnowledgeItem copy = copyOf(item);
        copy.setId(nextId++);
        rows.put(copy.getKnowledgeNo(), copy);
        return 1;
    }

    @Override
    public int updateContent(AiKnowledgeItem item) {
        updateContentCount++;
        AiKnowledgeItem copy = copyOf(item);
        AiKnowledgeItem existing = rows.get(item.getKnowledgeNo());
        copy.setId(existing == null ? nextId++ : existing.getId());
        rows.put(copy.getKnowledgeNo(), copy);
        return 1;
    }

    @Override
    public int updateStatus(String knowledgeNo, String status) {
        updateStatusCount++;
        AiKnowledgeItem existing = rows.get(knowledgeNo);
        if (existing == null) {
            return 0;
        }
        existing.setStatus(KnowledgeStatus.valueOf(status));
        return 1;
    }

    static AiKnowledgeItem copyOf(AiKnowledgeItem source) {
        AiKnowledgeItem copy = new AiKnowledgeItem();
        copy.setId(source.getId());
        copy.setKnowledgeNo(source.getKnowledgeNo());
        copy.setDomain(source.getDomain());
        copy.setTitle(source.getTitle());
        copy.setContent(source.getContent());
        copy.setKeywords(source.getKeywords());
        copy.setEffectiveFrom(source.getEffectiveFrom());
        copy.setEffectiveTo(source.getEffectiveTo());
        copy.setVersion(source.getVersion());
        copy.setStatus(source.getStatus());
        copy.setPermissionCode(source.getPermissionCode());
        copy.setSourceRef(source.getSourceRef());
        copy.setIsDeleted(source.getIsDeleted());
        copy.setDeletedAt(source.getDeletedAt());
        copy.setDeletedBy(source.getDeletedBy());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    /** 供测试断言：按编号升序的全部行。 */
    List<AiKnowledgeItem> all() {
        List<AiKnowledgeItem> result = new ArrayList<>(rows.values());
        result.sort(Comparator.comparing(AiKnowledgeItem::getKnowledgeNo));
        return result;
    }
}
