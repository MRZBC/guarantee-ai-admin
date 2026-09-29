package com.guarantee.ai.knowledge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 无分词环境的词项提取（RK-RAG-02）。
 *
 * <p>这套规则决定了"中文问句能不能命中人工标签"，是召回率的起点；
 * 它有单测才能保证换实现（例如以后接 ngram 全文索引）时行为可对比。</p>
 */
class KnowledgeTermsTest {

    @Test
    @DisplayName("中文长句展开为 2-gram 与 3-gram：'停用和删除' 必须同时给出 停用/删除")
    void longChineseRunIsExpanded() {
        assertThat(KnowledgeTerms.meaningful("停用和删除有什么区别"))
                .contains("停用", "删除", "区别");
    }

    @Test
    @DisplayName("单个汉字不作为词项（太宽泛，只会制造噪声）")
    void singleCharacterIsNotMeaningful() {
        assertThat(KnowledgeTerms.extract("和")).containsExactly("和");
        assertThat(KnowledgeTerms.meaningful("和")).isEmpty();
    }

    @Test
    @DisplayName("中英数混合：按连续区间切分，英文按词、中文按 gram")
    void mixedChineseAndAscii() {
        assertThat(KnowledgeTerms.meaningful("Q3 保费 premium 是多少"))
                .contains("q3", "premium", "保费", "多少");
    }

    @Test
    @DisplayName("空问句与纯标点：不产生词项")
    void blankAndPunctuationProduceNothing() {
        assertThat(KnowledgeTerms.meaningful(null)).isEmpty();
        assertThat(KnowledgeTerms.meaningful("   ")).isEmpty();
        assertThat(KnowledgeTerms.meaningful("？？！，。")).isEmpty();
    }
}
