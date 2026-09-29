package com.guarantee.ai.knowledge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TEST-RAG-01：知识真源 front-matter 解析与校验。
 *
 * <p>为什么每个失败路径都要有断言：真源是**人写的**（业务人员 + 开发），
 * 解析器是唯一能当场拦住"字段写错、编号写错、内容超长"的地方。
 * 一旦它把错误数据放进库，后果是回答里出现错的口径或错的来源行——
 * 而来源行是用户判断"这句话有没有依据"的唯一凭据。</p>
 */
class KnowledgeDocumentParserTest {

    private static final String VALID = """
            ---
            knowledge_no: KB-SYSTEM-0001
            domain: SYSTEM
            title: 机构的层级与编码规则
            keywords: 机构,层级,总部,省级,市级
            version: 1
            status: PUBLISHED
            source_ref: prompts/business-assistant.st L70-L72
            ---

            机构是一棵**三层树**。
            """;

    @Test
    @DisplayName("合法真源：字段逐项解析，正文取 front-matter 之后的内容")
    void parsesValidDocument() {
        KnowledgeDocument doc = KnowledgeDocumentParser.parse(VALID, "knowledge/SYSTEM/x.md");

        assertThat(doc.knowledgeNo()).isEqualTo("KB-SYSTEM-0001");
        assertThat(doc.domain()).isEqualTo(KnowledgeDomain.SYSTEM);
        assertThat(doc.title()).isEqualTo("机构的层级与编码规则");
        assertThat(doc.keywords()).containsExactly("机构", "层级", "总部", "省级", "市级");
        assertThat(doc.keywordsText()).isEqualTo("机构,层级,总部,省级,市级");
        assertThat(doc.version()).isEqualTo(1);
        assertThat(doc.status()).isEqualTo(KnowledgeStatus.PUBLISHED);
        assertThat(doc.effectiveFrom()).isNull();
        assertThat(doc.effectiveTo()).isNull();
        assertThat(doc.permissionCode()).isNull();
        assertThat(doc.sourceRef()).isEqualTo("prompts/business-assistant.st L70-L72");
        assertThat(doc.sourcePath()).isEqualTo("knowledge/SYSTEM/x.md");
        assertThat(doc.content()).isEqualTo("机构是一棵**三层树**。");
        assertThat(doc.contentBytes()).isLessThanOrEqualTo(KnowledgeDocumentParser.MAX_CONTENT_BYTES);
    }

    @Test
    @DisplayName("可选字段有默认值：status 默认 PUBLISHED、version 默认 1、生效期为不限")
    void optionalFieldsHaveDefaults() {
        String raw = """
                ---
                knowledge_no: KB-ORDER-0009
                domain: ORDER
                title: 最小合法条目
                keywords: 甲,乙,丙,丁,戊
                ---

                正文。
                """;
        KnowledgeDocument doc = KnowledgeDocumentParser.parse(raw, "x.md");

        assertThat(doc.version()).isEqualTo(1);
        assertThat(doc.status()).isEqualTo(KnowledgeStatus.PUBLISHED);
        assertThat(doc.effectiveFrom()).isNull();
        assertThat(doc.effectiveTo()).isNull();
        assertThat(doc.permissionCode()).isNull();
    }

    @Test
    @DisplayName("生效期与权限码：显式给出时按 yyyy-MM-dd 与码值解析")
    void parsesEffectiveRangeAndPermission() {
        String raw = """
                ---
                knowledge_no: KB-POLICY-0001
                domain: POLICY
                title: 保证金制度（示例）
                keywords: 制度,保证金,示例,条款,生效期
                version: 3
                status: DRAFT
                effective_from: 2026-01-01
                effective_to: 2026-12-31
                permission_code: system:audit:view
                source_ref: "制度原文：第 3 章"
                ---

                正文。
                """;
        KnowledgeDocument doc = KnowledgeDocumentParser.parse(raw, "knowledge/POLICY/x.md");

        assertThat(doc.effectiveFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(doc.effectiveTo()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(doc.permissionCode()).isEqualTo("system:audit:view");
        assertThat(doc.version()).isEqualTo(3);
        assertThat(doc.status()).isEqualTo(KnowledgeStatus.DRAFT);
        assertThat(doc.sourceRef()).isEqualTo("制度原文：第 3 章");
    }

    @Test
    @DisplayName("换行与首尾空白归一：CRLF 与 LF 得到同一份正文（幂等导入的前提）")
    void normalizesLineEndingsAndTrimsBody() {
        String lf = VALID;
        String crlf = VALID.replace("\n", "\r\n") + "\r\n\r\n   ";
        KnowledgeDocument a = KnowledgeDocumentParser.parse(lf, "p");
        KnowledgeDocument b = KnowledgeDocumentParser.parse(crlf, "p");

        assertThat(b.content()).isEqualTo(a.content());
        assertThat(b.contentHash()).as("内容哈希必须与换行/尾部空白无关").isEqualTo(a.contentHash());
    }

    @Test
    @DisplayName("值可带引号：双引号内可含冒号与井号，单引号用 '' 转义")
    void parsesQuotedScalars() {
        String raw = """
                ---
                knowledge_no: KB-ORDER-0010
                domain: ORDER
                title: 保额区间：不填=不限
                keywords: '甲甲,乙乙,丙丙,丁丁,戊戊'
                version: 1
                source_ref: "见 order#section: 3"
                ---

                正文。
                """;
        KnowledgeDocument doc = KnowledgeDocumentParser.parse(raw, "x.md");

        assertThat(doc.title()).isEqualTo("保额区间：不填=不限");
        assertThat(doc.sourceRef()).isEqualTo("见 order#section: 3");
        assertThat(doc.keywords()).containsExactly("甲甲", "乙乙", "丙丙", "丁丁", "戊戊");
    }

    // ------------------------------------------------------------------
    // 失败路径：错误必须可读、带定位
    // ------------------------------------------------------------------

    @Test
    @DisplayName("缺少 front-matter：报第 1 行并以 --- 为线索")
    void rejectsMissingFrontMatter() {
        assertThatThrownBy(() -> KnowledgeDocumentParser.parse("机构是一棵树。", "knowledge/SYSTEM/x.md"))
                .isInstanceOf(KnowledgeParseException.class)
                .hasMessageContaining("knowledge/SYSTEM/x.md")
                .hasMessageContaining("第 1 行")
                .hasMessageContaining("---");
    }

    @Test
    @DisplayName("front-matter 没有结束标记：明确报错，不把字段当正文吃掉")
    void rejectsUnterminatedFrontMatter() {
        String raw = """
                ---
                knowledge_no: KB-SYSTEM-0001
                domain: SYSTEM
                title: 标题
                keywords: 甲,乙,丙,丁,戊
                """;
        assertThatThrownBy(() -> KnowledgeDocumentParser.parse(raw, "x.md"))
                .isInstanceOf(KnowledgeParseException.class)
                .hasMessageContaining("结束标记");
    }

    @Test
    @DisplayName("未知字段：必须报错（写错字段名不能静默丢字段）")
    void rejectsUnknownField() {
        assertThatThrownBy(() -> KnowledgeDocumentParser.parse(
                VALID.replace("keywords:", "keyword:"), "x.md"))
                .isInstanceOf(KnowledgeParseException.class)
                .hasMessageContaining("未知字段 keyword");
    }

    @Test
    @DisplayName("字段重复：报错并指出字段名")
    void rejectsDuplicateField() {
        assertThatThrownBy(() -> KnowledgeDocumentParser.parse(
                VALID.replace("title: 机构的层级与编码规则", "title: 甲\ntitle: 乙"), "x.md"))
                .isInstanceOf(KnowledgeParseException.class)
                .hasMessageContaining("重复");
    }

    @Test
    @DisplayName("编号格式错误 / 编号前缀与域不一致：都报错")
    void rejectsBadKnowledgeNumber() {
        assertThatThrownBy(() -> KnowledgeDocumentParser.parse(
                VALID.replace("KB-SYSTEM-0001", "KB-SYSTEM-1"), "x.md"))
                .isInstanceOf(KnowledgeParseException.class)
                .hasMessageContaining("KB-<DOMAIN>-NNNN");

        assertThatThrownBy(() -> KnowledgeDocumentParser.parse(
                VALID.replace("KB-SYSTEM-0001", "KB-ORDER-0001"), "x.md"))
                .isInstanceOf(KnowledgeParseException.class)
                .hasMessageContaining("不一致");
    }

    @Test
    @DisplayName("域取值非法 / 状态非法 / 版本非数字：都报错")
    void rejectsInvalidEnumsAndVersion() {
        assertThatThrownBy(() -> KnowledgeDocumentParser.parse(
                VALID.replace("domain: SYSTEM", "domain: KNOWLEDGE"), "x.md"))
                .isInstanceOf(KnowledgeParseException.class)
                .hasMessageContaining("ORDER/SYSTEM/CONCEPT/POLICY");

        assertThatThrownBy(() -> KnowledgeDocumentParser.parse(
                VALID.replace("status: PUBLISHED", "status: ONLINE"), "x.md"))
                .isInstanceOf(KnowledgeParseException.class)
                .hasMessageContaining("DRAFT/PUBLISHED/RETIRED");

        assertThatThrownBy(() -> KnowledgeDocumentParser.parse(
                VALID.replace("version: 1", "version: 一"), "x.md"))
                .isInstanceOf(KnowledgeParseException.class)
                .hasMessageContaining("必须是整数");
    }

    @Test
    @DisplayName("缺少必填字段 / 正文为空：都报错；末尾多几个空行不算空正文")
    void rejectsMissingRequiredFieldsAndEmptyBody() {
        assertThatThrownBy(() -> KnowledgeDocumentParser.parse(
                VALID.replace("title: 机构的层级与编码规则\n", ""), "x.md"))
                .isInstanceOf(KnowledgeParseException.class)
                .hasMessageContaining("缺少必填字段 title");

        assertThatCode(() -> KnowledgeDocumentParser.parse(VALID + "\n\n   \n", "x.md"))
                .as("正文非空，末尾空行应被 strip 掉而不是判为空")
                .doesNotThrowAnyException();

        String noBody = VALID.substring(0, VALID.lastIndexOf("---") + 3);
        assertThatThrownBy(() -> KnowledgeDocumentParser.parse(noBody, "x.md"))
                .isInstanceOf(KnowledgeParseException.class)
                .hasMessageContaining("没有正文");
    }

    @Test
    @DisplayName("标签数量必须在 5~15：过少报错")
    void rejectsTooFewKeywords() {
        assertThatThrownBy(() -> KnowledgeDocumentParser.parse(
                VALID.replace("机构,层级,总部,省级,市级", "机构,层级"), "x.md"))
                .isInstanceOf(KnowledgeParseException.class)
                .hasMessageContaining("5~15");
    }

    @Test
    @DisplayName("正文超过 2KB：报错并给出实际字节数（不能被静默截断）")
    void rejectsOversizedContent() {
        String body = "甲".repeat(3000);
        String raw = VALID.substring(0, VALID.lastIndexOf("---") + 3) + "\n\n" + body;
        assertThatThrownBy(() -> KnowledgeDocumentParser.parse(raw, "x.md"))
                .isInstanceOf(KnowledgeParseException.class)
                .hasMessageContaining("2048 字节");
    }

    @Test
    @DisplayName("标题超过 60 字：报错")
    void rejectsOversizedTitle() {
        assertThatThrownBy(() -> KnowledgeDocumentParser.parse(
                VALID.replace("机构的层级与编码规则", "标".repeat(61)), "x.md"))
                .isInstanceOf(KnowledgeParseException.class)
                .hasMessageContaining("60 字");
    }

    @Test
    @DisplayName("日期格式错误 / 生效期倒挂：都报错")
    void rejectsBadDates() {
        assertThatThrownBy(() -> KnowledgeDocumentParser.parse(
                VALID.replace("version: 1", "version: 1\neffective_from: 2026/01/01"), "x.md"))
                .isInstanceOf(KnowledgeParseException.class)
                .hasMessageContaining("yyyy-MM-dd");

        assertThatThrownBy(() -> KnowledgeDocumentParser.parse(
                VALID.replace("version: 1",
                        "version: 1\neffective_from: 2026-12-31\neffective_to: 2026-01-01"), "x.md"))
                .isInstanceOf(KnowledgeParseException.class)
                .hasMessageContaining("不能晚于");
    }

    @Test
    @DisplayName("引号未闭合：报错而非把引号当内容")
    void rejectsUnclosedQuote() {
        assertThatThrownBy(() -> KnowledgeDocumentParser.parse(
                VALID.replace("title: 机构的层级与编码规则", "title: \"机构层级"), "x.md"))
                .isInstanceOf(KnowledgeParseException.class)
                .hasMessageContaining("引号");
    }

    @Test
    @DisplayName("注释与空行可出现在 front-matter 里；行内 # 属于值的一部分")
    void supportsCommentsAndBlankLines() {
        String raw = """
                ---
                # 这是注释

                knowledge_no: KB-SYSTEM-0001
                domain: SYSTEM
                title: 标题#带井号
                keywords: 甲,乙,丙,丁,戊
                ---

                正文。
                """;
        KnowledgeDocument doc = KnowledgeDocumentParser.parse(raw, "x.md");
        assertThat(doc.title()).isEqualTo("标题#带井号");
    }

    @Test
    @DisplayName("编号→域反查：非法编号返回空")
    void domainOfNumberIsLenient() {
        assertThat(KnowledgeDocumentParser.domainOfNumber("KB-ORDER-0001")).contains(KnowledgeDomain.ORDER);
        assertThat(KnowledgeDocumentParser.domainOfNumber("ORDER-1")).isEmpty();
        assertThat(KnowledgeDocumentParser.domainOfNumber(null)).isEmpty();
    }
}
