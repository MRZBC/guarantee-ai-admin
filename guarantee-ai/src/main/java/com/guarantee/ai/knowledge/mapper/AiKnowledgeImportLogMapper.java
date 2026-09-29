package com.guarantee.ai.knowledge.mapper;

import com.guarantee.ai.knowledge.entity.AiKnowledgeImportLog;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 知识导入留痕 Mapper（{@code ai_knowledge_import_log}）：只追加，不修改、不删除。
 *
 * <p>除了审计，它还有一个结构性用途：{@link #selectLatestImportedSourceFiles()} 给出
 * "每个编号最后一次是由哪个真源文件导入的"，导入器据此判断该文件是否还存在。
 * 这使得"真源消失 → 置 RETIRED"只作用于**被导入过**的条目，
 * 手工写进库的条目不会被误停用。</p>
 */
@Mapper
public interface AiKnowledgeImportLogMapper {

    int insert(AiKnowledgeImportLog entity);

    /**
     * 每个编号最后一次带来源文件的导入记录（{@code knowledgeNo} / {@code sourceFile} 有值）。
     */
    List<AiKnowledgeImportLog> selectLatestImportedSourceFiles();
}
