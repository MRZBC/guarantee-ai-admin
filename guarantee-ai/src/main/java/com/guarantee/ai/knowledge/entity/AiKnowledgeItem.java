package com.guarantee.ai.knowledge.entity;

import com.guarantee.ai.knowledge.KnowledgeDomain;
import com.guarantee.ai.knowledge.KnowledgeStatus;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 业务知识条目（{@code ai_knowledge_item}）——Markdown 真源在运行期的投影。
 *
 * <p><b>为什么真源不在库里</b>（REQ-RAG-02）：知识要像代码一样被评审与 diff，
 * 所以真源是仓库里的 Markdown；本表是检索面（生效期、版本、权限码、状态、
 * 逻辑删除），供运行期按权限裁剪与审计使用。二者分工见 REQ §5.1.2 的设计理由。</p>
 *
 * <p><b>编号不能自动生成</b>：{@code knowledgeNo} 人工固定在 front-matter
 * （{@code KB-<DOMAIN>-NNNN}），导入器只做 upsert。自动生成会让编号在多次导入间漂移，
 * 而编号是溯源与引用的唯一凭据。</p>
 *
 * <p><b>版本语义</b>：内容变化即 {@code version + 1}，编号不变；真源文件消失时置
 * {@link KnowledgeStatus#RETIRED} 而非物理删除，保证"哪一版在什么时候消失"可追溯。</p>
 */
@Data
public class AiKnowledgeItem {

    private Long id;
    /** 稳定编号 {@code KB-<DOMAIN>-NNNN}，全生命周期不变。 */
    private String knowledgeNo;
    private KnowledgeDomain domain;
    /** 中文标题（≤60 字）；溯源行里逐字使用。 */
    private String title;
    /** 正文（Markdown 纯文本，≤2KB）。 */
    private String content;
    /** 检索标签，逗号分隔（5~15 个）。 */
    private String keywords;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private Integer version;
    private KnowledgeStatus status;
    /** 可见所需权限码；空 = 登录即可见。 */
    private String permissionCode;
    /** 来源说明（原文出处 / 对应代码类 / 文档章节）。 */
    private String sourceRef;
    private Integer isDeleted;
    private LocalDateTime deletedAt;
    private String deletedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
