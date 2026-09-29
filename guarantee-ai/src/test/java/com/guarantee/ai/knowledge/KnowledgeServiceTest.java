package com.guarantee.ai.knowledge;

import com.guarantee.ai.knowledge.entity.AiKnowledgeItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TEST-RAG-03 / TEST-RAG-04：检索的排序、边界与权限过滤。
 *
 * <p>这些断言都在**服务端**成立，而不是靠提示词里的"请不要越权"：
 * 越权条目必须根本不出现在工具返回值里，否则任何一次模型不听话就等于越权成功
 * （REQ-RAG-07）。同样，{@code limit} 与字节预算必须由服务端归一，
 * 因为调用方（模型）可以传任何数字。</p>
 */
class KnowledgeServiceTest {

    private InMemoryKnowledgeItemMapper items;
    private KnowledgeProperties properties;
    private KnowledgeService service;

    @BeforeEach
    void setUp() {
        items = new InMemoryKnowledgeItemMapper();
        properties = new KnowledgeProperties();
        service = new KnowledgeService(items, properties);
    }

    // ------------------------------------------------------------------
    // TEST-RAG-03：排序
    // ------------------------------------------------------------------

    @Test
    @DisplayName("排序：标签命中 > 标题命中 > 正文命中（逐条对应需求，不是加权求和）")
    void rankingOrderIsKeywordThenTitleThenContent() {
        items.seed(item("KB-SYSTEM-0001", "SYSTEM", "业务对象的两种操作",
                "正文里没有那两个字的说明。", "停用,删除,暂停,启用,状态"));
        items.seed(item("KB-SYSTEM-0002", "SYSTEM", "停用与删除的区别",
                "正文说明。", "区别,概念,状态,操作,变更"));
        items.seed(item("KB-SYSTEM-0003", "SYSTEM", "状态管理概述",
                "本条目正文里提到停用的含义。", "概述,状态,管理,说明,条目"));

        KnowledgeSearchResult result = service.search(List.of(), "停用和删除有什么区别", null, 5);

        assertThat(result.items()).extracting(KnowledgeHit::knowledgeNo)
                .containsExactly("KB-SYSTEM-0001", "KB-SYSTEM-0002", "KB-SYSTEM-0003");
        assertThat(result.truncated()).isFalse();
        assertThat(result.totalHits()).isEqualTo(3);
    }

    @Test
    @DisplayName("同分兜底：版本新优先，再按编号升序稳定排序")
    void tiesAreResolvedByVersionThenNumber() {
        items.seed(item("KB-SYSTEM-0003", "SYSTEM", "甲", "停用说明。", "停用,甲,乙,丙,丁"));
        items.seed(item("KB-SYSTEM-0001", "SYSTEM", "乙", "停用说明。", "停用,甲,乙,丙,丁"));
        AiKnowledgeItem newer = item("KB-SYSTEM-0002", "SYSTEM", "丙", "停用说明。", "停用,甲,乙,丙,丁");
        newer.setVersion(5);
        items.seed(newer);

        KnowledgeSearchResult result = service.search(List.of(), "停用", null, 5);

        assertThat(result.items()).extracting(KnowledgeHit::knowledgeNo)
                .containsExactly("KB-SYSTEM-0002", "KB-SYSTEM-0001", "KB-SYSTEM-0003");
    }

    // ------------------------------------------------------------------
    // TEST-RAG-03：limit 归一与字节预算
    // ------------------------------------------------------------------

    @Test
    @DisplayName("limit 归一：默认 3、上限 5、非法值回落默认；配置 max-items 再收一层")
    void limitNormalization() {
        assertThat(service.normalizeLimit(null)).isEqualTo(3);
        assertThat(service.normalizeLimit(0)).isEqualTo(3);
        assertThat(service.normalizeLimit(-2)).isEqualTo(3);
        assertThat(service.normalizeLimit(2)).isEqualTo(2);
        assertThat(service.normalizeLimit(10)).as("需求硬上限 5，调用方要 10 也只给 5").isEqualTo(5);
        assertThat(service.normalizeLimit(5)).isEqualTo(5);

        properties.setMaxItems(2);
        assertThat(service.normalizeLimit(10)).as("配置比硬上限更严时以配置为准").isEqualTo(2);
        assertThat(service.normalizeLimit(null)).isEqualTo(2);

        properties.setMaxItems(0);
        assertThat(service.normalizeLimit(null)).as("配置写坏也要给得出结果").isEqualTo(1);
    }

    @Test
    @DisplayName("命中多于 limit：截断并标记 truncated，dataSource 说明命中总数")
    void moreHitsThanLimitIsTruncated() {
        for (int i = 1; i <= 4; i++) {
            items.seed(item("KB-ORDER-000" + i, "ORDER", "保额区间" + i, "保额区间的说明。", "保额区间,保额,上下限,不限,险种"));
        }

        KnowledgeSearchResult result = service.search(List.of(), "保额区间怎么规定", null, 2);

        assertThat(result.items()).hasSize(2);
        assertThat(result.truncated()).isTrue();
        assertThat(result.totalHits()).isEqualTo(4);
        assertThat(result.dataSource()).contains("命中 4 条").contains("返回前 2 条");
    }

    @Test
    @DisplayName("字节预算触顶：正文按 UTF-8 边界截断，不产生半个汉字，并置 truncated")
    void byteBudgetTruncatesOnUtf8Boundary() {
        properties.setMaxBytes(300);
        items.seed(item("KB-ORDER-0001", "ORDER", "保额区间", "保".repeat(400), "保额区间,保额,上下限,不限,险种"));

        KnowledgeSearchResult result = service.search(List.of(), "保额区间", null, 3);

        assertThat(result.items()).hasSize(1);
        assertThat(result.truncated()).isTrue();
        String content = result.items().get(0).content();
        assertThat(content.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(300);
        assertThat(content).as("不能把多字节字符切成半个（替换字符会暴露截断位置）").doesNotContain("\uFFFD");
    }

    @Test
    @DisplayName("空结果：返回空列表 + 「知识库：未收录」，不回填任何内容")
    void emptyResultIsHonest() {
        items.seed(item("KB-SYSTEM-0001", "SYSTEM", "机构的层级", "机构是一棵树。", "机构,层级,总部,省级,市级"));

        KnowledgeSearchResult result = service.search(List.of(), "量子保函的保证金比例", null, 3);

        assertThat(result.items()).isEmpty();
        assertThat(result.totalHits()).isZero();
        assertThat(result.truncated()).isFalse();
        assertThat(result.dataSource()).isEqualTo("知识库：未收录");
        assertThat(result.sourceLine()).as("没命中就没有来源行").isEmpty();
    }

    @Test
    @DisplayName("未提供关键词：明确说未检索，不猜内容")
    void blankQueryIsNotSearched() {
        KnowledgeSearchResult result = service.search(List.of(), "   ", "SYSTEM", 3);

        assertThat(result.items()).isEmpty();
        assertThat(result.dataSource()).contains("未提供检索关键词");
    }

    // ------------------------------------------------------------------
    // TEST-RAG-08：生效期
    // ------------------------------------------------------------------

    @Test
    @DisplayName("生效期：已失效与未生效的条目都不返回；生效中正常返回并给出区间")
    void effectiveWindowIsEnforced() {
        AiKnowledgeItem expired = item("KB-ORDER-0001", "ORDER", "已失效的保额区间口径", "正文。", "保额区间,保额,上限,下限,不限");
        expired.setEffectiveTo(LocalDate.now().minusDays(1));
        AiKnowledgeItem future = item("KB-ORDER-0002", "ORDER", "尚未生效的保额区间口径", "正文。", "保额区间,保额,上限,下限,不限");
        future.setEffectiveFrom(LocalDate.now().plusDays(1));
        AiKnowledgeItem active = item("KB-ORDER-0003", "ORDER", "生效中的保额区间口径", "正文。", "保额区间,保额,上限,下限,不限");
        active.setEffectiveFrom(LocalDate.now().minusDays(30));
        active.setEffectiveTo(LocalDate.now().plusDays(30));
        items.seed(expired);
        items.seed(future);
        items.seed(active);

        KnowledgeSearchResult result = service.search(List.of(), "保额区间", null, 5);

        assertThat(result.items()).extracting(KnowledgeHit::knowledgeNo).containsExactly("KB-ORDER-0003");
        assertThat(result.items().get(0).effectiveRange()).contains("~").doesNotContain("长期有效");
    }

    @Test
    @DisplayName("长期有效条目：生效期为空，展示为「长期有效」")
    void longLivedItemShowsLongTermRange() {
        items.seed(item("KB-ORDER-0001", "ORDER", "保额区间", "正文。", "保额区间,保额,上限,下限,不限"));

        KnowledgeSearchResult result = service.search(List.of(), "保额区间", null, 3);

        assertThat(result.items().get(0).effectiveRange()).isEqualTo("长期有效");
    }

    // ------------------------------------------------------------------
    // TEST-RAG-03：域过滤与出参形状
    // ------------------------------------------------------------------

    @Test
    @DisplayName("域过滤：指定域只返回该域；域写入 dataSource 供用户核对")
    void domainFilterAndDataSource() {
        items.seed(item("KB-SYSTEM-0011", "SYSTEM", "停用与删除的区别", "正文。", "停用,删除,区别,状态,操作"));
        items.seed(item("KB-ORDER-0001", "ORDER", "停用险种的口径", "正文。", "停用,险种,状态,操作,口径"));

        KnowledgeSearchResult result = service.search(List.of(), "停用", "ORDER", 5);

        assertThat(result.items()).extracting(KnowledgeHit::knowledgeNo).containsExactly("KB-ORDER-0001");
        assertThat(result.domain()).isEqualTo(KnowledgeDomain.ORDER);
        assertThat(result.dataSource()).isEqualTo("知识库：命中 1 条（域：ORDER）");
    }

    @Test
    @DisplayName("非法域按不限处理，不把用户卡在一个拼错的域上")
    void invalidDomainFallsBackToAll() {
        items.seed(item("KB-SYSTEM-0011", "SYSTEM", "停用与删除的区别", "正文。", "停用,删除,区别,状态,操作"));

        KnowledgeSearchResult result = service.search(List.of(), "停用", "订单域", 3);

        assertThat(result.domain()).isNull();
        assertThat(result.items()).hasSize(1);
    }

    @Test
    @DisplayName("来源行逐字来自本轮返回值（编号 + 标题 + 版本；多条用「；」连接）")
    void sourceLineIsDerivedFromRetrievedItemsExactly() {
        AiKnowledgeItem first = item("KB-SYSTEM-0011", "SYSTEM", "停用与删除的区别", "正文。", "停用,删除,区别,状态,操作");
        first.setVersion(2);
        AiKnowledgeItem second = item("KB-ORDER-0001", "ORDER", "保额区间的口径", "正文。", "保额区间,保额,上限,下限,不限");
        items.seed(first);
        items.seed(second);

        KnowledgeSearchResult result = service.search(List.of(), "停用 保额区间", null, 5);

        // 顺序即排序结果（保额区间这条标签命中更多，排在前面；同分再比版本），
        // 来源行必须与返回顺序逐字一致
        assertThat(result.items()).extracting(KnowledgeHit::knowledgeNo)
                .containsExactly("KB-ORDER-0001", "KB-SYSTEM-0011");
        assertThat(result.sourceLine())
                .isEqualTo("知识来源：KB-ORDER-0001《保额区间的口径》v1；KB-SYSTEM-0011《停用与删除的区别》v2");
    }

    // ------------------------------------------------------------------
    // TEST-RAG-04：权限过滤（AC-RAG-06）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("越权：只读用户检索审计口径类知识 → 0 条，且不暴露\"存在但无权限\"")
    void auditKnowledgeIsInvisibleWithoutPermission() {
        AiKnowledgeItem audit = item("KB-SYSTEM-0010", "SYSTEM", "操作审计记录的内容与渠道",
                "审计记录谁在何时通过什么渠道改了什么。", "操作审计,审计,变更,渠道,记录");
        audit.setPermissionCode("system:audit:view");
        items.seed(audit);

        KnowledgeSearchResult result = service.search(List.of("ai:chat", "system:org:view"), "操作审计记录了什么", null, 5);

        assertThat(result.items()).isEmpty();
        assertThat(result.totalHits()).isZero();
        assertThat(result.dataSource()).as("如实说未收录，不暗示存在").isEqualTo("知识库：未收录");
        assertThat(result.dataSource()).doesNotContain("权限", "audit", "KB-");
    }

    @Test
    @DisplayName("有权限：审计口径类知识正常返回")
    void auditKnowledgeIsVisibleWithPermission() {
        AiKnowledgeItem audit = item("KB-SYSTEM-0010", "SYSTEM", "操作审计记录的内容与渠道",
                "审计记录谁在何时通过什么渠道改了什么。", "操作审计,审计,变更,渠道,记录");
        audit.setPermissionCode("system:audit:view");
        items.seed(audit);

        KnowledgeSearchResult result = service.search(List.of("system:audit:view"), "操作审计记录了什么", null, 5);

        assertThat(result.items()).extracting(KnowledgeHit::knowledgeNo).containsExactly("KB-SYSTEM-0010");
        assertThat(result.sourceLine()).isEqualTo("知识来源：KB-SYSTEM-0010《操作审计记录的内容与渠道》v1");
    }

    @Test
    @DisplayName("无需权限的条目：权限快照为空也可见（登录即可见）")
    void publicKnowledgeNeedsNoPermission() {
        items.seed(item("KB-ORDER-0001", "ORDER", "保额区间的口径", "正文。", "保额区间,保额,上限,下限,不限"));

        assertThat(service.search(List.of(), "保额区间", null, 3).items()).hasSize(1);
        assertThat(service.search(null, "保额区间", null, 3).items()).hasSize(1);
    }

    @Test
    @DisplayName("检索只取 PUBLISHED：DRAFT / RETIRED 一律不出现在结果里")
    void onlyPublishedIsSearchable() {
        AiKnowledgeItem draft = item("KB-SYSTEM-0001", "SYSTEM", "保额区间的草稿", "正文。", "保额区间,保额,上限,下限,不限");
        draft.setStatus(KnowledgeStatus.DRAFT);
        AiKnowledgeItem retired = item("KB-SYSTEM-0002", "SYSTEM", "保额区间的历史版", "正文。", "保额区间,保额,上限,下限,不限");
        retired.setStatus(KnowledgeStatus.RETIRED);
        items.seed(draft);
        items.seed(retired);

        KnowledgeSearchResult result = service.search(List.of(), "保额区间", null, 5);

        assertThat(result.items()).isEmpty();
        assertThat(result.dataSource()).isEqualTo("知识库：未收录");
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private static AiKnowledgeItem item(String knowledgeNo, String domain, String title,
                                        String content, String keywords) {
        String raw = """
                ---
                knowledge_no: %s
                domain: %s
                title: %s
                keywords: %s
                version: 1
                status: PUBLISHED
                ---

                %s
                """.formatted(knowledgeNo, domain, title, keywords, content);
        return KnowledgeImporter.toEntity(KnowledgeDocumentParser.parse(raw, "test/" + knowledgeNo + ".md"), 1);
    }
}
