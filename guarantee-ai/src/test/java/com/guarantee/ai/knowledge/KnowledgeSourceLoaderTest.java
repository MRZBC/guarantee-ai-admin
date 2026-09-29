package com.guarantee.ai.knowledge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 随包发布的**真源自检**：把 {@code classpath:knowledge/**&#47;*.md} 全部解析一遍。
 *
 * <p>为什么必须有：真源是数据，改错一个字（编号写歪、正文抄超 2KB、标签少一个）
 * 在生产上表现为"这条知识检索不到"或"启动日志里一条 ERROR"，而不是编译失败。
 * 有了这个测试，18 条真源在 {@code mvn test} 阶段就会被逐条校验。</p>
 */
class KnowledgeSourceLoaderTest {

    /** REQ §5.1.5「迁出清单」要求的编号；少一条就说明迁移没做完。 */
    private static final Set<String> REQUIRED_NUMBERS = Set.of(
            "KB-SYSTEM-0001", "KB-SYSTEM-0002", "KB-SYSTEM-0003", "KB-SYSTEM-0004",
            "KB-SYSTEM-0005", "KB-SYSTEM-0006", "KB-SYSTEM-0007", "KB-SYSTEM-0008",
            "KB-SYSTEM-0009", "KB-SYSTEM-0010", "KB-SYSTEM-0011", "KB-SYSTEM-0012",
            "KB-ORDER-0001", "KB-ORDER-0002", "KB-ORDER-0003", "KB-ORDER-0004",
            "KB-ORDER-0005",
            "KB-CONCEPT-0001");

    @Test
    @DisplayName("全部真源都能解析：无失败、无重复编号、路径可追溯")
    void allTruthSourcesParse() {
        KnowledgeSourceLoader.LoadResult result = new KnowledgeSourceLoader().load();

        assertThat(result.failures()).as("真源解析失败：%s", result.failures()).isEmpty();
        assertThat(result.documentCount()).isGreaterThanOrEqualTo(REQUIRED_NUMBERS.size());
        assertThat(result.discoveredPaths()).as("发现的文件数必须等于解析成功的文档数")
                .hasSize(result.documentCount());

        Set<String> numbers = new TreeSet<>();
        for (KnowledgeDocument doc : result.documents()) {
            assertThat(numbers.add(doc.knowledgeNo()))
                    .as("编号必须唯一（它是溯源凭据）：%s", doc.knowledgeNo()).isTrue();
            assertThat(doc.sourcePath()).as("%s 的路径必须带域目录", doc.knowledgeNo())
                    .contains("/" + doc.domain().name() + "/");
            assertThat(doc.sourceRef()).as("%s 必须写 source_ref（可人工核对来源）", doc.knowledgeNo())
                    .isNotBlank();
        }
        assertThat(numbers).as("REQ §5.1.5 迁出清单的编号必须齐全").containsAll(REQUIRED_NUMBERS);
    }

    @Test
    @DisplayName("预算与格式：单条正文 ≤2KB、标题 ≤60 字、标签 5~15 且不重复")
    void budgetsAndFormatHold() {
        for (KnowledgeDocument doc : new KnowledgeSourceLoader().load().documents()) {
            assertThat(doc.contentBytes()).as("%s 正文超 2KB", doc.knowledgeNo())
                    .isLessThanOrEqualTo(KnowledgeDocumentParser.MAX_CONTENT_BYTES);
            assertThat(doc.title().length()).as("%s 标题超 60 字", doc.knowledgeNo())
                    .isLessThanOrEqualTo(KnowledgeDocumentParser.MAX_TITLE_CHARS);
            assertThat(doc.keywords()).as("%s 标签数量必须在 5~15", doc.knowledgeNo())
                    .hasSizeBetween(KnowledgeDocumentParser.MIN_KEYWORDS, KnowledgeDocumentParser.MAX_KEYWORDS);
            assertThat(doc.keywords()).as("%s 标签不得重复", doc.knowledgeNo())
                    .doesNotHaveDuplicates();
        }
    }

    @Test
    @DisplayName("审计口径类知识必须挂 system:audit:view（AC-RAG-06 的越权用例依赖它）")
    void auditKnowledgeIsPermissionGuarded() {
        List<KnowledgeDocument> documents = new KnowledgeSourceLoader().load().documents();

        assertThat(documents).filteredOn(doc -> "KB-SYSTEM-0010".equals(doc.knowledgeNo()))
                .singleElement()
                .satisfies(doc -> assertThat(doc.permissionCode()).isEqualTo("system:audit:view"));

        assertThat(documents).filteredOn(doc -> doc.domain() == KnowledgeDomain.POLICY)
                .as("制度类本期无语料，只留通道")
                .isEmpty();
    }

    @Test
    @DisplayName("知识条目不得把「示例数字」写成业务统计（红线 §2.3-1）：保额区间条目不含具体区间数值")
    void amountRangeEntryCarriesNoNumbers() {
        KnowledgeDocument doc = new KnowledgeSourceLoader().load().documents().stream()
                .filter(d -> "KB-ORDER-0001".equals(d.knowledgeNo()))
                .findFirst()
                .orElseThrow();

        assertThat(doc.content())
                .as("保额区间的具体数值是险种配置，条目只解释口径")
                .contains("不填")
                .doesNotContain("%", "4%", "14%");
    }
}
