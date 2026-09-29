package com.guarantee.ai.knowledge;

import com.guarantee.ai.knowledge.entity.AiKnowledgeImportLog;
import com.guarantee.ai.knowledge.entity.AiKnowledgeItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TEST-RAG-02：知识真源的**幂等导入**。
 *
 * <p>这是本阶段最容易被"看起来对"骗过去的地方：一个不幂等的导入器在第一次运行时
 * 完全正常，只有第二次启动才会暴露——版本号自己往上涨、留痕表被无变化的内容刷屏，
 * 于是回答里的 {@code vN} 变成噪声，审计再也说不清"到底改没改过"。
 * 因此这里用**写调用计数**做断言，而不是只看最终数据形态。</p>
 */
class KnowledgeImporterTest {

    private InMemoryKnowledgeItemMapper items;
    private InMemoryKnowledgeImportLogMapper logs;
    private KnowledgeImporter importer;

    @BeforeEach
    void setUp() {
        items = new InMemoryKnowledgeItemMapper();
        logs = new InMemoryKnowledgeImportLogMapper();
        importer = new KnowledgeImporter(items, logs);
    }

    @Test
    @DisplayName("首次导入：按真源编号逐条 CREATED，版本取真源声明值")
    void firstImportCreatesEverything() {
        KnowledgeImporter.ImportSummary summary = importer.importAll(load(
                doc("KB-SYSTEM-0001", "机构的层级与编码规则", "机构是一棵三层树。", "knowledge/SYSTEM/a.md"),
                doc("KB-ORDER-0001", "保额区间的口径", "不填等于不限。", "knowledge/ORDER/b.md")));

        assertThat(summary.created()).isEqualTo(2);
        assertThat(summary.updated()).isZero();
        assertThat(summary.retired()).isZero();
        assertThat(summary.unchanged()).isZero();
        assertThat(items.insertCount).isEqualTo(2);
        assertThat(logs.size()).isEqualTo(2);
        assertThat(logs.actions()).containsExactly("CREATED", "CREATED");
        assertThat(items.get("KB-SYSTEM-0001").getVersion()).isEqualTo(1);
        assertThat(items.get("KB-SYSTEM-0001").getStatus()).isEqualTo(KnowledgeStatus.PUBLISHED);
        assertThat(logs.all().get(0).getOldVersion()).as("首次导入没有旧版本").isNull();
        assertThat(logs.all().get(0).getContentHash()).hasSize(64);
    }

    @Test
    @DisplayName("连续两次导入：第二次全部 unchanged，且**一次写都没有**（无新版本、无留痕）")
    void secondImportIsFullyIdempotent() {
        List<KnowledgeDocument> documents = List.of(
                doc("KB-SYSTEM-0001", "机构的层级与编码规则", "机构是一棵三层树。", "knowledge/SYSTEM/a.md"),
                doc("KB-SYSTEM-0002", "机构的业务定位", "机构是外部出函机构。", "knowledge/SYSTEM/b.md"));

        importer.importAll(load(documents));
        int insertsAfterFirst = items.insertCount;
        int updatesAfterFirst = items.updateContentCount;
        int logsAfterFirst = logs.size();
        Integer versionAfterFirst = items.get("KB-SYSTEM-0001").getVersion();

        KnowledgeImporter.ImportSummary second = importer.importAll(load(documents));

        assertThat(second.created()).isZero();
        assertThat(second.updated()).isZero();
        assertThat(second.restored()).isZero();
        assertThat(second.retired()).isZero();
        assertThat(second.unchanged()).as("第二条导入必须整体判定为未变化").isEqualTo(2);
        assertThat(second.noChanges()).isTrue();
        assertThat(items.insertCount).as("不得再插入").isEqualTo(insertsAfterFirst);
        assertThat(items.updateContentCount).as("不得再更新内容").isEqualTo(updatesAfterFirst);
        assertThat(logs.size()).as("未变化不得写留痕（否则留痕表会被启动次数刷屏）").isEqualTo(logsAfterFirst);
        assertThat(items.get("KB-SYSTEM-0001").getVersion()).as("版本不得自行上涨").isEqualTo(versionAfterFirst);
    }

    @Test
    @DisplayName("内容变化：version+1、编号不变、写一条 UPDATED 留痕（含旧/新版本与哈希）")
    void contentChangeBumpsVersionAndWritesLog() {
        String path = "knowledge/SYSTEM/a.md";
        importer.importAll(load(doc("KB-SYSTEM-0001", "机构的层级与编码规则", "旧正文。", path)));

        KnowledgeImporter.ImportSummary summary = importer.importAll(load(
                doc("KB-SYSTEM-0001", "机构的层级与编码规则", "新正文（口径已修订）。", path)));

        assertThat(summary.updated()).isEqualTo(1);
        assertThat(summary.created()).isZero();
        AiKnowledgeItem item = items.get("KB-SYSTEM-0001");
        assertThat(item.getKnowledgeNo()).as("编号是溯源凭据，内容变化不得改编号").isEqualTo("KB-SYSTEM-0001");
        assertThat(item.getVersion()).isEqualTo(2);
        assertThat(item.getContent()).isEqualTo("新正文（口径已修订）。");

        AiKnowledgeImportLog log = logs.last();
        assertThat(log.getAction()).isEqualTo("UPDATED");
        assertThat(log.getOldVersion()).isEqualTo(1);
        assertThat(log.getNewVersion()).isEqualTo(2);
        assertThat(log.getSourceFile()).isEqualTo(path);
        assertThat(log.getContentHash())
                .isEqualTo(doc("KB-SYSTEM-0001", "机构的层级与编码规则", "新正文（口径已修订）。", path).contentHash());
    }

    @Test
    @DisplayName("仅改 title/标签也算内容变化（哈希覆盖全部知识字段），但只改换行不算")
    void hashCoversWholeKnowledgeNotOnlyBody() {
        String path = "knowledge/SYSTEM/a.md";
        importer.importAll(load(doc("KB-SYSTEM-0001", "标题甲", "正文。", path)));

        // 换行变化：假变化，必须判定 unchanged
        importer.importAll(loadWithRaw(path, """
                ---
                knowledge_no: KB-SYSTEM-0001
                domain: SYSTEM
                title: 标题甲
                keywords: 甲,乙,丙,丁,戊
                version: 1
                status: PUBLISHED
                ---



                正文。
                """));
        assertThat(items.get("KB-SYSTEM-0001").getVersion()).as("空白变化不该涨版本").isEqualTo(1);

        // 标题变化：真变化，必须涨版本
        importer.importAll(load(doc("KB-SYSTEM-0001", "标题乙", "正文。", path)));
        assertThat(items.get("KB-SYSTEM-0001").getVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("真源文件消失：置 RETIRED 而**不物理删除**，并写 RETIRED 留痕；重复导入不再重复停用")
    void missingSourceFileRetiresItem() {
        String path = "knowledge/SYSTEM/a.md";
        importer.importAll(load(doc("KB-SYSTEM-0001", "机构的层级与编码规则", "正文。", path)));

        KnowledgeImporter.ImportSummary summary = importer.importAll(empty());

        assertThat(summary.retired()).isEqualTo(1);
        assertThat(items.size()).as("行必须还在（不物理删除），状态改为 RETIRED").isEqualTo(1);
        assertThat(items.get("KB-SYSTEM-0001").getStatus()).isEqualTo(KnowledgeStatus.RETIRED);
        assertThat(items.get("KB-SYSTEM-0001").getContent()).as("停止检索不等于丢内容").isEqualTo("正文。");
        assertThat(logs.last().getAction()).isEqualTo("RETIRED");
        assertThat(logs.last().getSourceFile()).isEqualTo(path);

        KnowledgeImporter.ImportSummary again = importer.importAll(empty());
        assertThat(again.retired()).as("已停用的条目再次导入必须幂等").isZero();
    }

    @Test
    @DisplayName("解析失败但文件仍在：不得判为\"真源消失\"（一次笔误不能静默停用一条知识）")
    void parseFailureDoesNotRetire() {
        String path = "knowledge/SYSTEM/a.md";
        importer.importAll(load(doc("KB-SYSTEM-0001", "机构的层级与编码规则", "正文。", path)));

        KnowledgeSourceLoader.LoadResult broken = new KnowledgeSourceLoader.LoadResult(
                List.of(), List.of(new KnowledgeSourceLoader.Failure(path, path + "：title 缺失")), List.of(path));
        KnowledgeImporter.ImportSummary summary = importer.importAll(broken);

        assertThat(summary.retired()).isZero();
        assertThat(summary.parseFailures()).isEqualTo(1);
        assertThat(items.get("KB-SYSTEM-0001").getStatus()).isEqualTo(KnowledgeStatus.PUBLISHED);
    }

    @Test
    @DisplayName("手工写进库、没有导入留痕的条目不会被导入器停用")
    void manuallyCreatedRowsAreNotRetired() {
        AiKnowledgeItem manual = new AiKnowledgeItem();
        manual.setKnowledgeNo("KB-SYSTEM-9999");
        manual.setDomain(KnowledgeDomain.SYSTEM);
        manual.setTitle("人工维护的条目");
        manual.setContent("正文。");
        manual.setKeywords("甲,乙,丙,丁,戊");
        manual.setVersion(1);
        manual.setStatus(KnowledgeStatus.PUBLISHED);
        items.seed(manual);

        KnowledgeImporter.ImportSummary summary = importer.importAll(empty());

        assertThat(summary.retired()).isZero();
        assertThat(items.get("KB-SYSTEM-9999").getStatus()).isEqualTo(KnowledgeStatus.PUBLISHED);
    }

    @Test
    @DisplayName("真源回来：RETIRED 条目恢复为 PUBLISHED、版本 +1、写 RESTORED 留痕")
    void retiredItemIsRestoredWhenSourceReturns() {
        String path = "knowledge/SYSTEM/a.md";
        KnowledgeDocument document = doc("KB-SYSTEM-0001", "机构的层级与编码规则", "正文。", path);

        importer.importAll(load(document));
        importer.importAll(empty());
        KnowledgeImporter.ImportSummary summary = importer.importAll(load(document));

        assertThat(summary.restored()).isEqualTo(1);
        AiKnowledgeItem item = items.get("KB-SYSTEM-0001");
        assertThat(item.getStatus()).isEqualTo(KnowledgeStatus.PUBLISHED);
        assertThat(item.getVersion()).as("恢复是一次状态变更，必须留痕到版本号上").isEqualTo(2);
        assertThat(logs.last().getAction()).isEqualTo("RESTORED");
    }

    @Test
    @DisplayName("DRAFT 条目参与导入但不参与检索；导入器不改变真源声明的状态")
    void draftStatusIsRespected() {
        String path = "knowledge/SYSTEM/a.md";
        String raw = """
                ---
                knowledge_no: KB-SYSTEM-0042
                domain: SYSTEM
                title: 草稿条目
                keywords: 甲,乙,丙,丁,戊
                status: DRAFT
                ---

                还没上线的口径。
                """;
        importer.importAll(new KnowledgeSourceLoader.LoadResult(
                List.of(KnowledgeDocumentParser.parse(raw, path)), List.of(), List.of(path)));

        assertThat(items.get("KB-SYSTEM-0042").getStatus()).isEqualTo(KnowledgeStatus.DRAFT);
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private static KnowledgeDocument doc(String knowledgeNo, String title, String content, String path) {
        String domain = knowledgeNo.startsWith("KB-ORDER") ? "ORDER" : "SYSTEM";
        String raw = """
                ---
                knowledge_no: %s
                domain: %s
                title: %s
                keywords: 甲,乙,丙,丁,戊
                version: 1
                status: PUBLISHED
                ---

                %s
                """.formatted(knowledgeNo, domain, title, content);
        return KnowledgeDocumentParser.parse(raw, path);
    }

    private static KnowledgeSourceLoader.LoadResult loadWithRaw(String path, String raw) {
        return new KnowledgeSourceLoader.LoadResult(
                List.of(KnowledgeDocumentParser.parse(raw, path)), List.of(), List.of(path));
    }

    private static KnowledgeSourceLoader.LoadResult load(KnowledgeDocument... documents) {
        return load(List.of(documents));
    }

    private static KnowledgeSourceLoader.LoadResult load(List<KnowledgeDocument> documents) {
        List<String> paths = documents.stream().map(KnowledgeDocument::sourcePath).toList();
        return new KnowledgeSourceLoader.LoadResult(documents, List.of(), paths);
    }

    private static KnowledgeSourceLoader.LoadResult empty() {
        return new KnowledgeSourceLoader.LoadResult(List.of(), List.of(), List.of());
    }
}
