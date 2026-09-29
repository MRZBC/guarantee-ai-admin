package com.guarantee.ai.knowledge;

import com.guarantee.ai.knowledge.entity.AiKnowledgeImportLog;
import com.guarantee.ai.knowledge.mapper.AiKnowledgeImportLogMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单测用的内存版导入留痕 Mapper：只追加，不修改、不删除（与生产实现同语义）。
 */
final class InMemoryKnowledgeImportLogMapper implements AiKnowledgeImportLogMapper {

    private final List<AiKnowledgeImportLog> rows = new ArrayList<>();
    private long nextId = 1;

    @Override
    public int insert(AiKnowledgeImportLog entity) {
        AiKnowledgeImportLog copy = new AiKnowledgeImportLog();
        copy.setId(nextId++);
        copy.setKnowledgeNo(entity.getKnowledgeNo());
        copy.setOldVersion(entity.getOldVersion());
        copy.setNewVersion(entity.getNewVersion());
        copy.setContentHash(entity.getContentHash());
        copy.setAction(entity.getAction());
        copy.setSourceFile(entity.getSourceFile());
        copy.setImportedAt(entity.getImportedAt());
        rows.add(copy);
        return 1;
    }

    @Override
    public List<AiKnowledgeImportLog> selectLatestImportedSourceFiles() {
        Map<String, AiKnowledgeImportLog> latest = new LinkedHashMap<>();
        for (AiKnowledgeImportLog row : rows) {
            if (row.getSourceFile() != null) {
                latest.put(row.getKnowledgeNo(), row);
            }
        }
        return List.copyOf(latest.values());
    }

    int size() {
        return rows.size();
    }

    List<AiKnowledgeImportLog> all() {
        return List.copyOf(rows);
    }

    /** 最近一条留痕（按插入顺序）。 */
    AiKnowledgeImportLog last() {
        return rows.isEmpty() ? null : rows.get(rows.size() - 1);
    }

    List<String> actions() {
        return rows.stream().map(AiKnowledgeImportLog::getAction).sorted(Comparator.naturalOrder()).toList();
    }
}
