package com.guarantee.ai.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时的知识真源幂等导入（REQ-RAG-02）。
 *
 * <p><b>为什么不放在 schema.sql 里</b>：schema 只管建表；知识内容是"数据"，
 * 且需要内容哈希比较与版本推进，SQL 做不到（也不该做——真源是 Markdown）。</p>
 *
 * <p><b>失败不阻断启动</b>（RK-RAG-08）：解析失败的文件逐个记 ERROR 并跳过其余文件；
 * 连库不可用也只记 ERROR。数字类问答与知识层是两条独立的链路，
 * 知识层挂掉不能让整个助手不可用。</p>
 *
 * <p>开关：{@code guarantee.ai.knowledge.import-on-startup=false} 时完全跳过
 * （运维手工控制），与 {@code guarantee.ai.knowledge.enabled}（是否注册检索工具）
 * 是两件事。</p>
 */
@Component
public class KnowledgeImportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeImportRunner.class);

    private final KnowledgeProperties properties;
    private final KnowledgeSourceLoader sourceLoader;
    private final KnowledgeImporter importer;

    public KnowledgeImportRunner(KnowledgeProperties properties,
                                 KnowledgeSourceLoader sourceLoader,
                                 KnowledgeImporter importer) {
        this.properties = properties;
        this.sourceLoader = sourceLoader;
        this.importer = importer;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isImportOnStartup()) {
            log.info("guarantee.ai.knowledge.import-on-startup=false，跳过知识真源导入");
            return;
        }
        try {
            KnowledgeSourceLoader.LoadResult loadResult = sourceLoader.load();
            for (KnowledgeSourceLoader.Failure failure : loadResult.failures()) {
                log.error("知识真源解析失败（该文件本轮跳过，不影响其余条目）：{}", failure.reason());
            }
            KnowledgeImporter.ImportSummary summary = importer.importAll(loadResult);
            log.info("知识真源导入完成：真源 {} 个（解析失败 {}），新增 {}，更新 {}，恢复 {}，停用 {}，未变 {}",
                    loadResult.documentCount(), loadResult.failureCount(),
                    summary.created(), summary.updated(), summary.restored(),
                    summary.retired(), summary.unchanged());
            if (loadResult.documentCount() == 0 && loadResult.failureCount() == 0) {
                log.warn("未发现任何知识真源（classpath:knowledge/**/*.md）——检索工具将只能回答「未收录」");
            }
        } catch (Exception ex) {
            log.error("知识真源导入失败：知识层本轮不可用（数字类问答不受影响）", ex);
        }
    }
}
