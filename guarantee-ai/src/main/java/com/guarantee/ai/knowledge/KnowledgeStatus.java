package com.guarantee.ai.knowledge;

import java.util.Locale;
import java.util.Optional;

/**
 * 条目状态（REQ-RAG-01）。
 *
 * <p>只有 {@link #PUBLISHED} 参与检索：{@code DRAFT} 是草稿（真源里保留但不上线），
 * {@code RETIRED} 是"真源文件已消失"或人工停用的条目——**不物理删除**，
 * 保留编号、版本与导入留痕以便追溯（审计要能回答"这条口径什么时候消失的"）。</p>
 */
public enum KnowledgeStatus {

    DRAFT,
    PUBLISHED,
    RETIRED;

    public static Optional<KnowledgeStatus> fromCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (KnowledgeStatus status : values()) {
            if (status.name().equals(normalized)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}
