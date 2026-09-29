package com.guarantee.ai.knowledge.mapper;

import com.guarantee.ai.knowledge.entity.AiKnowledgeItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 知识条目 Mapper（{@code ai_knowledge_item}）。
 *
 * <p>分层铁律：本接口只被 {@code KnowledgeService} / {@code KnowledgeImporter} 调用，
 * Tool 永不接触它（Tool → Service → Mapper → DB）。SQL 全部在
 * {@code resources/mapper/knowledge/AiKnowledgeItemMapper.xml}，Java 侧不拼 SQL。</p>
 *
 * <p><b>扫描说明</b>：本模块不在启动类 {@code @MapperScan} 的四个包内
 * （那是既有共享文件，本阶段不动它），因此由 {@code KnowledgeMapperConfig}
 * 在本包内声明自己的 {@code @MapperScan}。</p>
 */
@Mapper
public interface AiKnowledgeItemMapper {

    /** 全部未逻辑删除的条目（含 DRAFT / RETIRED：导入器需要看到它们才能判定状态迁移）。 */
    List<AiKnowledgeItem> selectAllActive();

    /** 按稳定编号取未删除条目；不存在返回 {@code null}。 */
    AiKnowledgeItem selectByKnowledgeNo(@Param("knowledgeNo") String knowledgeNo);

    /**
     * 检索候选集：指定状态（检索恒传 PUBLISHED）+ 可选域过滤，按编号稳定排序。
     *
     * <p>打分与排序刻意放在应用侧（REQ-RAG-03：{@code keywords} + {@code title/content}
     * 命中打分）：条目规模 &lt;1000 时毫秒级，且排序规则可被单测逐条断言；
     * 生效期与权限裁剪同样不放进 SQL——权限必须由 Service 层统一裁剪，
     * 两个地方各写一套过滤条件必然分叉。</p>
     */
    List<AiKnowledgeItem> selectSearchCandidates(@Param("domain") String domain,
                                                 @Param("status") String status);

    int insert(AiKnowledgeItem item);

    /** 内容更新（同时把版本与状态改成导入器算出的值）。 */
    int updateContent(AiKnowledgeItem item);

    /** 状态更新（真源文件消失 → RETIRED）。 */
    int updateStatus(@Param("knowledgeNo") String knowledgeNo,
                     @Param("status") String status);
}
