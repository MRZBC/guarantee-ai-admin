package com.guarantee.ai.knowledge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 知识层配置（{@code guarantee.ai.knowledge.*}，REQ §6.4）。
 *
 * <p>承载机制是第四阶段的事（T4-00 的配置底座），本阶段先用 Spring 的配置绑定，
 * 键名与默认值按 REQ §6.4 固定；第四阶段接管后这些键的读取来源会变，
 * 但**键名与默认值不变**，因此本类不需要跟着改。</p>
 *
 * <p>默认值逐项：{@code enabled=true}（关掉后不注册检索工具）、
 * {@code import-on-startup=true}、{@code max-items=5}（单次检索条数硬上限）、
 * {@code max-bytes=6144}（单次检索结果字节上限）、{@code embedding.enabled=false}
 * （M3.5 向量检索预留）。</p>
 */
@Component
public class KnowledgeProperties {

    /** REQ-RAG-03：{@code limit} 上限 5——即使用户/模型要求更多也不放行。 */
    public static final int HARD_MAX_ITEMS = 5;

    /** REQ-RAG-03：默认返回 3 条。 */
    public static final int DEFAULT_MAX_ITEMS = 3;

    /** 结果字节下限：防止配置写成 0 导致永远返回空。 */
    public static final int MIN_BYTES = 256;

    @Value("${guarantee.ai.knowledge.enabled:true}")
    private boolean enabled = true;

    @Value("${guarantee.ai.knowledge.import-on-startup:true}")
    private boolean importOnStartup = true;

    @Value("${guarantee.ai.knowledge.max-items:5}")
    private int maxItems = HARD_MAX_ITEMS;

    @Value("${guarantee.ai.knowledge.max-bytes:6144}")
    private int maxBytes = 6144;

    @Value("${guarantee.ai.knowledge.embedding.enabled:false}")
    private boolean embeddingEnabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isImportOnStartup() {
        return importOnStartup;
    }

    public void setImportOnStartup(boolean importOnStartup) {
        this.importOnStartup = importOnStartup;
    }

    public int getMaxItems() {
        return maxItems;
    }

    public void setMaxItems(int maxItems) {
        this.maxItems = maxItems;
    }

    public int getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(int maxBytes) {
        this.maxBytes = maxBytes;
    }

    public boolean isEmbeddingEnabled() {
        return embeddingEnabled;
    }

    public void setEmbeddingEnabled(boolean embeddingEnabled) {
        this.embeddingEnabled = embeddingEnabled;
    }

    /** 生效的条数硬上限：{@code min(配置值, 5)}，且至少 1。 */
    public int effectiveMaxItems() {
        return Math.max(1, Math.min(maxItems, HARD_MAX_ITEMS));
    }

    /** 生效的字节预算：至少 {@link #MIN_BYTES}。 */
    public int effectiveMaxBytes() {
        return Math.max(MIN_BYTES, maxBytes);
    }
}
