package com.guarantee.ai.knowledge.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识真源导入留痕（{@code ai_knowledge_import_log}）——只追加，不修改。
 *
 * <p>存在的意义有两个：</p>
 * <ol>
 *   <li><b>可审计</b>：回答里的知识来源行带版本号，本表回答"这一版是什么时候、由哪个
 *       真源文件、从哪一版变成的"；</li>
 *   <li><b>让"真源消失 → 置 RETIRED"可判定</b>：条目表本身不记录来源文件，导入器靠本表
 *       里每个编号最后一次导入的 {@code source_file} 判断该文件是否还存在。
 *       <b>只有被导入过的条目才会被停用</b>——手工写进库、没有导入留痕的条目不受影响。</li>
 * </ol>
 */
@Data
public class AiKnowledgeImportLog {

    private Long id;
    private String knowledgeNo;
    /** 变更前版本；首次导入为 {@code null}。 */
    private Integer oldVersion;
    private Integer newVersion;
    /** 变更后内容的 SHA-256（规范化后的内容哈希）。 */
    private String contentHash;
    /** CREATED / UPDATED / RESTORED / RETIRED。 */
    private String action;
    /** 真源文件（classpath 相对路径，形如 {@code knowledge/SYSTEM/KB-SYSTEM-0001-xxx.md}）。 */
    private String sourceFile;
    private LocalDateTime importedAt;
}
