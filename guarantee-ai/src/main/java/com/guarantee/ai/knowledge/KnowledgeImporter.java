package com.guarantee.ai.knowledge;

import com.guarantee.ai.knowledge.entity.AiKnowledgeImportLog;
import com.guarantee.ai.knowledge.entity.AiKnowledgeItem;
import com.guarantee.ai.knowledge.mapper.AiKnowledgeImportLogMapper;
import com.guarantee.ai.knowledge.mapper.AiKnowledgeItemMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 知识真源的**幂等导入器**（REQ-RAG-02）。
 *
 * <p><b>判据</b>：按 {@code knowledge_no} upsert，内容哈希（{@link KnowledgeContentHash}）
 * 未变 → 不动；内容变化 → {@code version + 1} 并写一条留痕；真源文件消失 → 置
 * {@code RETIRED}，**不物理删除**。</p>
 *
 * <p><b>为什么"幂等"必须是硬指标</b>：导入在每次启动都会跑。若判据不稳，
 * 会出现两种坏结果——版本号每次启动都涨（来源行里的 vN 变成噪声，审计再也说不清
 * "到底改没改"），或者内容真的变了却判定未变（知识静默过期，最坏）。因此哈希口径
 * 与真源解析都在同一处定义（{@link KnowledgeContentHash}），并有单测连续两次导入断言
 * 第二次全部 unchanged、且没有写任何留痕。</p>
 *
 * <p><b>编号不会漂移</b>：导入器只认 front-matter 里人工固定的编号，不生成、不改写编号。</p>
 */
@Component
public class KnowledgeImporter {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeImporter.class);

    /** 留痕动作（与 {@code ai_knowledge_import_log.action} 取值一致）。 */
    static final String ACTION_CREATED = "CREATED";
    static final String ACTION_UPDATED = "UPDATED";
    static final String ACTION_RESTORED = "RESTORED";
    static final String ACTION_RETIRED = "RETIRED";

    private final AiKnowledgeItemMapper itemMapper;
    private final AiKnowledgeImportLogMapper importLogMapper;

    public KnowledgeImporter(AiKnowledgeItemMapper itemMapper,
                            AiKnowledgeImportLogMapper importLogMapper) {
        this.itemMapper = itemMapper;
        this.importLogMapper = importLogMapper;
    }

    /** 一次导入的结果统计（供启动日志与单测断言）。 */
    public record ImportSummary(int created,
                                int updated,
                                int restored,
                                int retired,
                                int unchanged,
                                int parseFailures) {

        public int total() {
            return created + updated + restored + retired + unchanged;
        }

        public boolean noChanges() {
            return created == 0 && updated == 0 && restored == 0 && retired == 0;
        }
    }

    /**
     * 执行一次幂等导入。
     *
     * @param loadResult 真源加载结果（含解析失败的清单与"发现到的路径"）
     */
    @Transactional
    public ImportSummary importAll(KnowledgeSourceLoader.LoadResult loadResult) {
        Map<String, AiKnowledgeItem> existing = new HashMap<>();
        for (AiKnowledgeItem item : itemMapper.selectAllActive()) {
            existing.put(item.getKnowledgeNo(), item);
        }

        int created = 0;
        int updated = 0;
        int restored = 0;
        int unchanged = 0;

        for (KnowledgeDocument document : loadResult.documents()) {
            AiKnowledgeItem current = existing.get(document.knowledgeNo());
            if (current == null) {
                int version = document.version();
                itemMapper.insert(toItem(document, version));
                writeLog(document.knowledgeNo(), null, version, document.contentHash(),
                        ACTION_CREATED, document.sourcePath());
                created++;
                continue;
            }
            if (current.getStatus() == KnowledgeStatus.RETIRED) {
                // 真源回来了：恢复为真源声明的状态，并显式涨一版（RETIRED → PUBLISHED 是状态变更）
                int version = nextVersion(current);
                itemMapper.updateContent(toItem(document, version));
                writeLog(document.knowledgeNo(), current.getVersion(), version, document.contentHash(),
                        ACTION_RESTORED, document.sourcePath());
                restored++;
                continue;
            }
            if (KnowledgeContentHash.of(current).equals(document.contentHash())) {
                unchanged++;
                continue;
            }
            int version = nextVersion(current);
            if (document.version() != version) {
                // 真源里写的 version 只用于首次导入的基线；更新时以库内 +1 为准。
                // 不一致说明作者手工改过 front-matter 的 version，记一条 WARN 便于排查。
                log.warn("知识条目 {} 的 front-matter version={} 与库里推算的 {} 不一致，以库内 +1 为准",
                        document.knowledgeNo(), document.version(), version);
            }
            itemMapper.updateContent(toItem(document, version));
            writeLog(document.knowledgeNo(), current.getVersion(), version, document.contentHash(),
                    ACTION_UPDATED, document.sourcePath());
            updated++;
        }

        int retired = retireMissingSources(loadResult, existing);

        return new ImportSummary(created, updated, restored, retired, unchanged,
                loadResult.failureCount());
    }

    /**
     * 真源文件消失 → 置 RETIRED。
     *
     * <p>只处理**有导入留痕**的编号：留痕里记录了它最后一次是由哪个文件导入的，
     * 该文件不在本轮发现的路径里即判定消失。手工写进库、没有留痕的条目不会被误停用。</p>
     *
     * <p>已 RETIRED 的条目重复导入时什么也不做（幂等）。</p>
     */
    private int retireMissingSources(KnowledgeSourceLoader.LoadResult loadResult,
                                     Map<String, AiKnowledgeItem> existing) {
        Set<String> discovered = new HashSet<>(loadResult.discoveredPaths());
        int retired = 0;
        for (AiKnowledgeImportLog last : importLogMapper.selectLatestImportedSourceFiles()) {
            String knowledgeNo = last.getKnowledgeNo();
            String sourceFile = last.getSourceFile();
            if (knowledgeNo == null || sourceFile == null || discovered.contains(sourceFile)) {
                continue;
            }
            AiKnowledgeItem current = existing.get(knowledgeNo);
            if (current == null || current.getStatus() == KnowledgeStatus.RETIRED) {
                continue;
            }
            itemMapper.updateStatus(knowledgeNo, KnowledgeStatus.RETIRED.name());
            writeLog(knowledgeNo, current.getVersion(), current.getVersion(),
                    KnowledgeContentHash.of(current), ACTION_RETIRED, sourceFile);
            log.info("知识条目 {} 的真源文件 {} 已消失，置为 RETIRED（不物理删除）",
                    knowledgeNo, sourceFile);
            retired++;
        }
        return retired;
    }

    private void writeLog(String knowledgeNo, Integer oldVersion, int newVersion,
                          String contentHash, String action, String sourceFile) {
        AiKnowledgeImportLog entity = new AiKnowledgeImportLog();
        entity.setKnowledgeNo(knowledgeNo);
        entity.setOldVersion(oldVersion);
        entity.setNewVersion(newVersion);
        entity.setContentHash(contentHash);
        entity.setAction(action);
        entity.setSourceFile(sourceFile);
        importLogMapper.insert(entity);
    }

    private static int nextVersion(AiKnowledgeItem current) {
        return (current.getVersion() == null ? 1 : current.getVersion()) + 1;
    }

    private static AiKnowledgeItem toItem(KnowledgeDocument document, int version) {
        AiKnowledgeItem item = new AiKnowledgeItem();
        item.setKnowledgeNo(document.knowledgeNo());
        item.setDomain(document.domain());
        item.setTitle(document.title());
        item.setContent(document.content());
        item.setKeywords(document.keywordsText());
        item.setEffectiveFrom(document.effectiveFrom());
        item.setEffectiveTo(document.effectiveTo());
        item.setVersion(version);
        item.setStatus(document.status());
        item.setPermissionCode(document.permissionCode());
        item.setSourceRef(document.sourceRef());
        return item;
    }

    /** 真源文档 → 未入库的实体（版本由调用方给出）；供单测与运维工具复用。 */
    public static AiKnowledgeItem toEntity(KnowledgeDocument document, int version) {
        return toItem(document, version);
    }
}
