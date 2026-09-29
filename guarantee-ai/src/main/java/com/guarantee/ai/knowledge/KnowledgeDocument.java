package com.guarantee.ai.knowledge;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/**
 * 一个真源 Markdown 文件解析后的结果（REQ-RAG-02）。
 *
 * <p>{@code content} 是 front-matter 之后的正文，已按**统一口径**规范化：
 * 去掉首尾空白（{@code strip()}）、换行统一为 {@code \n}。规范化是幂等导入的前提——
 * 否则"{@code 内容末尾多一个空行}"会被判定为内容变化，白白涨一次版本号。</p>
 *
 * @param knowledgeNo   人工固定的稳定编号
 * @param domain        知识域
 * @param title         标题（≤60 字）
 * @param content       正文（≤2KB，已 strip）
 * @param keywords      检索标签（5~15 个，已去重、去空白）
 * @param effectiveFrom 生效起始日（可空）
 * @param effectiveTo   生效截止日（可空）
 * @param version       真源声明的版本（仅用于首次导入的基线；更新时由导入器 +1）
 * @param status        状态
 * @param permissionCode 可见所需权限码（可空）
 * @param sourceRef     来源说明（可空）
 * @param sourcePath    真源文件路径（classpath 相对路径）
 */
public record KnowledgeDocument(
        String knowledgeNo,
        KnowledgeDomain domain,
        String title,
        String content,
        List<String> keywords,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        int version,
        KnowledgeStatus status,
        String permissionCode,
        String sourceRef,
        String sourcePath) {

    /** 入库用的标签串（逗号分隔、无空格）。 */
    public String keywordsText() {
        return String.join(",", keywords);
    }

    /** 内容哈希（与数据库一侧共用同一口径，见 {@link KnowledgeContentHash}）。 */
    public String contentHash() {
        return KnowledgeContentHash.of(domain, title, content, keywordsText(),
                effectiveFrom, effectiveTo, status, permissionCode, sourceRef);
    }

    /** 正文字节数（UTF-8），用于单条 ≤2KB 的预算校验。 */
    public int contentBytes() {
        return content.getBytes(StandardCharsets.UTF_8).length;
    }
}
