package com.guarantee.ai.service;

import com.guarantee.ai.knowledge.KnowledgeDomain;
import com.guarantee.ai.knowledge.KnowledgeHit;
import com.guarantee.ai.knowledge.KnowledgeSearchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TEST-RAG-05：「知识来源」行只能由服务端产出。
 *
 * <p>三类断言对应三条规则：追加（本轮真实命中）、剥离（模型自写）、未调用不追加
 * （零检索却有来源行 = 编造）。</p>
 */
class KnowledgeClaimGuardTest {

    private static final KnowledgeHit STOP_VS_DELETE = new KnowledgeHit(
            "KB-SYSTEM-0011", "停用与删除的区别", "正文。", 2,
            "长期有效", "prompts/business-assistant.st L146-L147", KnowledgeDomain.SYSTEM);

    @Test
    @DisplayName("页脚：逐字来自本轮返回值，多条用「；」连接，单条也带行首标签")
    void footerRendersServerSideSourceLine() {
        assertThat(KnowledgeClaimGuard.footer(List.of(STOP_VS_DELETE.sourceFragment())))
                .isEqualTo("\n\n知识来源：KB-SYSTEM-0011《停用与删除的区别》v2");

        assertThat(KnowledgeClaimGuard.footer(List.of(
                "KB-ORDER-0001《保额区间的口径》v1", "KB-SYSTEM-0011《停用与删除的区别》v2")))
                .isEqualTo("\n\n知识来源：KB-ORDER-0001《保额区间的口径》v1；KB-SYSTEM-0011《停用与删除的区别》v2");
    }

    @Test
    @DisplayName("页脚：没有命中就不追加（未收录不得出现来源行，AC-RAG-03）")
    void footerIsEmptyWithoutHits() {
        assertThat(KnowledgeClaimGuard.footer(List.of())).isEmpty();
        assertThat(KnowledgeClaimGuard.footer(null)).isEmpty();
        assertThat(KnowledgeClaimGuard.footer(java.util.Arrays.asList((String) null))).isEmpty();
    }

    @Test
    @DisplayName("剥离：模型自写的来源行（全角/半角冒号、带缩进）一律移除")
    void stripsModelWrittenSourceLines() {
        String answer = """
                停用是暂停业务，删除是从默认列表移除。

                知识来源：KB-SYSTEM-0001《机构的层级与编码规则》v9
                知识来源: KB-FAKE-9999《编造的条目》v1
                以上，知识来源以系统追加为准。
                """;

        String stripped = KnowledgeClaimGuard.stripSourceLines(answer);

        assertThat(stripped)
                .doesNotContain("KB-SYSTEM-0001")
                .doesNotContain("KB-FAKE-9999")
                .contains("知识来源以系统追加为准")
                .contains("停用是暂停业务");
        assertThat(stripped).doesNotEndWith("\n");
    }

    @Test
    @DisplayName("剥离：Markdown 装饰变体一律移除（粗体/列表/引用/标题/表格/行内代码/标签后置粗体）")
    void stripsDecoratedSourceLines() {
        // 这一组是外部验证发现的绕过反例：正则只认裸行时，模型"换个写法"就能把编造的来源行留下
        List<String> variants = List.of(
                "**知识来源：KB-FAKE-0001《粗体》v1**",
                "- 知识来源：KB-FAKE-0002《列表》v1",
                "> 知识来源：KB-FAKE-0003《引用》v1",
                "知识来源 ：KB-FAKE-0004《冒号前有空格》v1",
                "**知识来源**: KB-FAKE-0005《标签后置粗体》v1",
                "`知识来源：KB-FAKE-0006《行内代码》v1`",
                "## 知识来源：KB-FAKE-0007《标题》v1",
                "| 知识来源：KB-FAKE-0008《表格》v1 |",
                "  * 知识来源: KB-FAKE-0009《嵌套列表半角》v1");
        for (String variant : variants) {
            String answer = "结论如下。\n" + variant + "\n以上。";

            assertThat(KnowledgeClaimGuard.claimsKnowledgeSource(answer))
                    .as("必须识别为\"声明来源\"：%s", variant).isTrue();
            assertThat(KnowledgeClaimGuard.stripSourceLines(answer))
                    .as("必须剥离：%s", variant)
                    .doesNotContain("KB-FAKE-")
                    .contains("结论如下。");
            assertThat(KnowledgeClaimGuard.correctionFor(answer, false))
                    .as("零检索时必须触发纠正：%s", variant).isPresent();
            // 检索过就不再纠正（引用错误 ≠ 编造），但剥离照旧
            assertThat(KnowledgeClaimGuard.correctionFor(answer, true))
                    .as("检索过不纠正：%s", variant).isEmpty();
            assertThat(KnowledgeClaimGuard.stripSourceLines(answer))
                    .as("检索过也必须剥离：%s", variant).doesNotContain("KB-FAKE-");
        }
    }

    @Test
    @DisplayName("剥离：没有来源行时原样返回（调用方据此判断要不要 reset 重发）")
    void stripIsNoOpWhenNothingToStrip() {
        String answer = "停用与删除的区别是：停用是暂停业务。";
        assertThat(KnowledgeClaimGuard.stripSourceLines(answer)).isSameAs(answer);
        assertThat(KnowledgeClaimGuard.stripSourceLines("")).isEmpty();
        assertThat(KnowledgeClaimGuard.stripSourceLines(null)).isEmpty();
    }

    @Test
    @DisplayName("纠正：零检索却出现来源行 → 追加系统提示（说明那行不是系统回显）")
    void correctionOnlyWhenNoRetrievalHappened() {
        String fabricated = "投标保函费率是 1.3%。\n\n知识来源：KB-ORDER-0003《险种分类与基准费率口径》v1";

        assertThat(KnowledgeClaimGuard.correctionFor(fabricated, false))
                .contains(KnowledgeClaimGuard.CORRECTION);

        assertThat(KnowledgeClaimGuard.correctionFor(fabricated, true))
                .as("检索过就放过：这属于引用错误，不在零检索兜底的职责内")
                .isEmpty();
        assertThat(KnowledgeClaimGuard.correctionFor("普通回答，没有来源行。", false)).isEmpty();
        assertThat(KnowledgeClaimGuard.correctionFor("", false)).isEmpty();
        assertThat(KnowledgeClaimGuard.correctionFor(null, false)).isEmpty();
    }

    @Test
    @DisplayName("行中出现「知识来源」不算声明来源（避免误伤正常表述）")
    void midLineMentionIsNotAClaim() {
        assertThat(KnowledgeClaimGuard.claimsKnowledgeSource("这条知识的来源是系统提示词，不是知识库。")).isFalse();
        assertThat(KnowledgeClaimGuard.claimsKnowledgeSource("  知识来源：KB-X《Y》v1")).isTrue();
    }

    @Test
    @DisplayName("本轮收集器：记录真实检索的条目片段与 dataSource，并去重")
    void turnKnowledgeCollectsFacts() {
        KnowledgeClaimGuard.TurnKnowledge turn = new KnowledgeClaimGuard.TurnKnowledge();
        assertThat(turn.retrieved()).isFalse();
        assertThat(turn.sourceFragments()).isEmpty();

        turn.record(KnowledgeSearchResult.of(
                List.of(STOP_VS_DELETE), false, 1, KnowledgeDomain.SYSTEM));
        turn.record(KnowledgeSearchResult.of(
                List.of(STOP_VS_DELETE), false, 1, KnowledgeDomain.SYSTEM));

        assertThat(turn.retrieved()).isTrue();
        assertThat(turn.sourceFragments()).containsExactly("KB-SYSTEM-0011《停用与删除的区别》v2");
        assertThat(turn.dataSources()).containsExactly("知识库：命中 1 条（域：SYSTEM）");
    }

    @Test
    @DisplayName("本轮收集器：检索到 0 条也算「检索过」，但没有任何来源片段")
    void emptyRetrievalStillCountsAsRetrieval() {
        KnowledgeClaimGuard.TurnKnowledge turn = new KnowledgeClaimGuard.TurnKnowledge();
        turn.record(KnowledgeSearchResult.of(List.of(), false, 0, null));

        assertThat(turn.retrieved()).isTrue();
        assertThat(turn.sourceFragments()).isEmpty();
        assertThat(KnowledgeClaimGuard.footer(turn.sourceFragments())).as("未收录不追加来源行").isEmpty();
        assertThat(turn.dataSources()).containsExactly("知识库：未收录");
    }

    @Test
    @DisplayName("口径净化：按原值精确剔除知识 dataSource；\"含知识库字样\"的业务口径不受影响")
    void knowledgeDataSourcesAreExcludedFromAlignmentFooter() {
        KnowledgeClaimGuard.TurnKnowledge turn = new KnowledgeClaimGuard.TurnKnowledge();
        turn.record(KnowledgeSearchResult.of(List.of(STOP_VS_DELETE), false, 1, KnowledgeDomain.SYSTEM));

        List<String> result = KnowledgeClaimGuard.excludingKnowledgeDataSources(List.of(
                "订单统计 · 时间区间：2026-07-01 ~ 2026-09-30",
                "知识库：命中 1 条（域：SYSTEM）",
                "知识库使用量统计 · 近 7 天：3 次"), turn);

        assertThat(result)
                .as("知识工具返回的那条被剔除；业务工具的相似文本必须保留（不做子串猜测）")
                .containsExactly("订单统计 · 时间区间：2026-07-01 ~ 2026-09-30",
                        "知识库使用量统计 · 近 7 天：3 次");
        assertThat(KnowledgeClaimGuard.excludingKnowledgeDataSources(
                List.of("订单统计 · 全量"), null)).containsExactly("订单统计 · 全量");
        assertThat(KnowledgeClaimGuard.excludingKnowledgeDataSources(null, turn)).isEmpty();
    }

    @Test
    @DisplayName("从 ToolContext 取收集器：没挂载时返回 null（工具必须容忍）")
    void turnKnowledgeComesFromToolContext() {
        KnowledgeClaimGuard.TurnKnowledge turn = new KnowledgeClaimGuard.TurnKnowledge();
        ToolContext withCollector = new ToolContext(Map.of(KnowledgeClaimGuard.CONTEXT_KEY, turn));

        assertThat(KnowledgeClaimGuard.turnKnowledge(withCollector)).isSameAs(turn);
        assertThat(KnowledgeClaimGuard.turnKnowledge(new ToolContext(Map.of()))).isNull();
        assertThat(KnowledgeClaimGuard.turnKnowledge(null)).isNull();
    }
}
