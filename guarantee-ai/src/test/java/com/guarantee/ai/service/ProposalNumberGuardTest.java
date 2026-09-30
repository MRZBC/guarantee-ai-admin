package com.guarantee.ai.service;

import com.guarantee.ai.tool.ProposalNoFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 提案编号白名单校验。
 *
 * <p>真机事故：正文写「待确认提案：提案编号 OP202609242359135602」，而库里该编号不存在、
 * 当轮也没有写工具调用。原有的 {@link ProposalClaimGuard} 只能发现"会话内一条 PENDING 都没有"
 * 这一种情况——本类补的是**确定性**校验：编号必须来自本轮工具真实返回或用户自己打出的串。</p>
 */
class ProposalNumberGuardTest {

    private static final String REAL_NO = "OP202609292345001234";
    private static final String FAKE_NO = "OP202609242359135602";

    @Test
    @DisplayName("白名单为空 + 正文出现编号 → 编号被移除、置位改写、给出纠正")
    void removesNumberWhenNothingWasReturned() {
        ProposalNumberGuard.Result result = ProposalNumberGuard.sanitize(
                "我已生成变更提案，提案编号 " + FAKE_NO + "，请在确认卡上点击「确认执行」。",
                Set.of());

        assertThat(result.changed()).isTrue();
        assertThat(result.removed()).containsExactly(FAKE_NO);
        assertThat(result.text()).doesNotContain(FAKE_NO);
        assertThat(result.text()).contains("我已生成变更提案");
        assertThat(result.text()).contains("请在确认卡上点击");
    }

    @Test
    @DisplayName("编号来自本轮工具真实返回 → 原样保留（不得误伤真编号）")
    void keepsTrustedNumber() {
        ProposalNumberGuard.Result result = ProposalNumberGuard.sanitize(
                "待确认提案：" + REAL_NO + "，15 分钟内有效。",
                Set.of(REAL_NO));

        assertThat(result.changed()).isFalse();
        assertThat(result.removed()).isEmpty();
        assertThat(result.text()).isEqualTo("待确认提案：" + REAL_NO + "，15 分钟内有效。");
    }

    @Test
    @DisplayName("真编号与假编号同时出现：只移除假的")
    void removesOnlyUntrustedNumbers() {
        ProposalNumberGuard.Result result = ProposalNumberGuard.sanitize(
                "待确认提案：" + REAL_NO + "；另外上一轮的 " + FAKE_NO + " 也存在。",
                Set.of(REAL_NO));

        assertThat(result.changed()).isTrue();
        assertThat(result.removed()).containsExactly(FAKE_NO);
        assertThat(result.text()).contains(REAL_NO).doesNotContain(FAKE_NO);
    }

    @Test
    @DisplayName("多个编造编号：全部移除并计数")
    void removesAllFabricatedNumbers() {
        ProposalNumberGuard.Result result = ProposalNumberGuard.sanitize(
                "提案 " + FAKE_NO + " 与 " + "OP202601010000009999" + " 都已生成。",
                Set.of());

        assertThat(result.removed()).hasSize(2);
        assertThat(result.text()).doesNotContain("OP2026");
    }

    @Test
    @DisplayName("可采信集合 = 工具真实返回 ∪ 用户原话里自己打出的编号")
    void trustedUnionIncludesUserSuppliedNumber() {
        Set<String> trusted = ProposalNumberGuard.trusted(
                "帮我看看 " + FAKE_NO + " 这个提案还在吗", Set.of(REAL_NO));

        assertThat(trusted).containsExactlyInAnyOrder(REAL_NO, FAKE_NO);

        // 用户自己贴的编号，模型如实回显不算编造
        ProposalNumberGuard.Result result = ProposalNumberGuard.sanitize(
                "编号 " + FAKE_NO + " 在系统里不存在。", trusted);
        assertThat(result.changed()).isFalse();
        assertThat(result.text()).contains(FAKE_NO);
    }

    @Test
    @DisplayName("可采信集合对 null 宽容（工具没返回、用户也没提编号）")
    void trustedHandlesNulls() {
        assertThat(ProposalNumberGuard.trusted(null, null)).isEmpty();
        assertThat(ProposalNumberGuard.trusted("你好", null)).isEmpty();
        assertThat(ProposalNumberGuard.trusted(null, Set.of(REAL_NO))).containsExactly(REAL_NO);
    }

    @Test
    @DisplayName("正文没有编号 / 空正文：一律不改写（避免无谓的 reset 重发）")
    void leavesTextWithoutNumbersAlone() {
        String answer = "本季度共 12 笔订单，金额 1.50 元。";

        ProposalNumberGuard.Result result = ProposalNumberGuard.sanitize(answer, Set.of());

        assertThat(result.changed()).isFalse();
        assertThat(result.text()).isSameAs(answer);
        assertThat(ProposalNumberGuard.sanitize(null, Set.of()).changed()).isFalse();
        assertThat(ProposalNumberGuard.sanitize("   ", Set.of()).changed()).isFalse();
    }

    @Test
    @DisplayName("纠正文案不回显假编号（避免用户记住错的那个），并给出下一步")
    void correctionDoesNotEchoFakeNumber() {
        assertThat(ProposalNumberGuard.CORRECTION)
                .doesNotContain("OP")
                .contains("不是本轮工具返回的真实编号")
                .contains("已由系统移除")
                .contains("以确认卡上的编号为准");
    }

    // ------------------------------------------------------------------
    // R2：形态逃逸修掉之后的对照表（"修前 10/20 识别" → "修后 18/20"）+ 安全底线 + 反证
    // ------------------------------------------------------------------

    /**
     * 修后表：原先 20 种写法里只有 10 种被识别，另外 10 种**逃逸存活**。
     *
     * <p>修法：{@link ProposalNoFormat#CANDIDATE_PATTERN} 宽口径扫描拿原文 span +
     * {@link ProposalNoFormat#canonical} 归一化比对；删除仍用原文 span。</p>
     */
    @Test
    @DisplayName("修后表：小写/全角/分隔符/零宽/词边界被破坏等 18 种形态都能识别并移除")
    void escapedFormsAreCaughtAfterNormalization() {
        String fake = FAKE_NO;
        String tail = fake.substring(2);
        Set<String> escapedForms = Set.of(
                fake,
                "OP12345678",
                "编号" + fake,
                fake + "号",
                "#" + fake,
                "**" + fake + "**",
                "- " + fake,
                "> " + fake,
                "`" + fake + "`",
                fake.toLowerCase(),                       // 小写
                "Op" + tail,                              // 混合大小写
                "ＯＰ" + tail,                             // 全角 OP
                "ＯＰ" + "２０２６０９２４２３５９１３５６０２", // 全角 OP + 全角数字
                "OP-" + tail,                             // 半角连字符
                "OP " + tail,                             // 空格
                "OP\u200b" + tail,                        // 零宽空格
                "_" + fake,                               // 词边界被下划线破坏
                fake + "X");                              // 词边界被字母破坏
        for (String form : escapedForms) {
            ProposalNumberGuard.Result result = ProposalNumberGuard.sanitize("提案编号 " + form + " 已生成。", Set.of());
            assertThat(result.removed()).as("应被识别并移除：%s", form).isNotEmpty();
            assertThat(ProposalNoFormat.canonical(result.text()))
                    .as("移除后不该再留下这个编号（按归一值检查）：%s", form)
                    .doesNotContain("OP2026");
        }
    }

    /** 刻意保留的两条边界（不是缺陷）：8 位下限、必须有 OP 前缀。 */
    @Test
    @DisplayName("刻意保留的边界：7 位及以下不算编号、裸数字不算、省略号形态不算、普通小数不被误判")
    void deliberateBoundariesStay() {
        assertThat(ProposalNumberGuard.sanitize("提案编号 OP1234567 已生成。", Set.of()).changed())
                .as("既有决策：少于 8 位数字不算编号").isFalse();
        assertThat(ProposalNumberGuard.sanitize("订单号 202609242359135602 已生成。", Set.of()).changed())
                .as("没有 OP 前缀的裸数字不是提案编号").isFalse();
        assertThat(ProposalNumberGuard.sanitize("提案编号 OP2026…258712 已生成。", Set.of()).changed())
                .as("省略号是'省略中间'的写法，不等于任何编号").isFalse();
        assertThat(ProposalNumberGuard.sanitize("比例约为 OP 3.14159265 的样子。", Set.of()).changed())
                .as("小数点不作为分隔符：正文里的普通小数不该被拼成编号").isFalse();
    }

    /**
     * 安全底线：模型**如实回显**的真编号，无论写成哪种逃逸形态，都不得被误删。
     *
     * <p>这正是"比对必须用归一值"的原因：如果拿逃逸原文去和真实编号做字符串比较，它会被判成编造。</p>
     */
    @Test
    @DisplayName("安全底线：真编号的小写/分段/全角/零宽写法一律不误删（归一化比对才做得到）")
    void faithfulEchoOfTrustedNumberIsNeverRemoved() {
        Set<String> trusted = ProposalNumberGuard.trusted(null, Set.of(REAL_NO));
        String tail = REAL_NO.substring(2);
        Set<String> echoes = Set.of(
                REAL_NO,
                REAL_NO.toLowerCase(),
                "OP-" + tail,
                "OP " + tail,
                "ＯＰ" + tail,
                "OP\u200b" + tail);
        for (String echo : echoes) {
            ProposalNumberGuard.Result result =
                    ProposalNumberGuard.sanitize("待确认提案：" + echo + "，15 分钟内有效。", trusted);
            assertThat(result.changed()).as("如实回显的真编号不得被误删：%s", echo).isFalse();
            assertThat(result.text()).as("原文必须保持不变：%s", echo).contains(echo);
        }

        // 用户自己贴的分段写法也要进可信集合（否则模型如实回显用户给的串会被误删）
        assertThat(ProposalNumberGuard.trusted("帮我核对 op-" + tail + " 这个提案", null))
                .contains(ProposalNoFormat.canonical(REAL_NO));
    }

    /**
     * 反证：**归一化就是这次修复本身**——把归一化关掉，必须有断言变红。
     *
     * <p>第 ④ 条最直接：把"可信集合"传成**未归一化的原文**（= 模拟不做归一化的比对），
     * 真编号的逃逸写法立刻被判成编造并被移除。若有人把 {@code canonical()} 从比对链路上摘掉，
     * 这一条就会红。</p>
     */
    @Test
    @DisplayName("反证：退回旧口径（严格正则 / 原文比对）时，漏判与误删都会发生")
    void counterProofNormalizationIsTheFix() {
        String echoed = "op-" + REAL_NO.substring(2);

        // ① 旧严格正则在这个形态上扫不到任何东西 —— 这就是"形态逃逸"
        assertThat(ProposalNoFormat.STRICT_PATTERN.matcher(echoed).find())
                .as("反证①：旧口径扫不到逃逸形态（于是编造编号既不移除也不纠正）").isFalse();
        // ② 新口径能扫到，且归一后与真实编号相等
        assertThat(ProposalNoFormat.findAll(echoed)).as("新口径能扫到").isNotEmpty();
        assertThat(ProposalNoFormat.canonical(echoed)).isEqualTo(REAL_NO);

        // ③ 归一化在：真编号的逃逸写法被正确采信 → 不改写
        Set<String> normalizedTrusted = ProposalNumberGuard.trusted(null, Set.of(REAL_NO));
        assertThat(normalizedTrusted).contains(ProposalNoFormat.canonical(echoed));
        assertThat(ProposalNumberGuard.sanitize("待确认提案：" + echoed + "。", normalizedTrusted).changed())
                .as("反证③：归一化后不误删").isFalse();

        // ④ 归一化"关掉"的等价物：直接做**原文字符串比较**时，同一个编号因为写法不同而不相等
        //    → 真编号会被判成编造并移除。下面每条都是"摘掉 canonical() 就会变红"的绊线。
        Set<String> rawOnly = Set.of(echoed);
        assertThat(rawOnly).as("反证④-1：原文比较下两者不相等（旧口径必然误删如实回显）").doesNotContain(REAL_NO);
        assertThat(ProposalNoFormat.canonical(echoed)).as("反证④-2：归一化后二者相等").isEqualTo(REAL_NO);

        // ④-3：可信集合侧必须归一化 —— 用户贴的是分段写法时，集合里放的必须是归一值
        //      （若把 canonical() 从 trusted() 摘掉，这里会得到原文，断言立刻红）
        assertThat(ProposalNumberGuard.trusted("帮我核对 op-" + REAL_NO.substring(2), null))
                .as("反证④-3：用户分段写法进入可信集合时已被归一化")
                .containsExactly(REAL_NO);

        // ④-4：比对侧必须归一化 —— 可信集合是归一值，正文写成分段形态，仍不得误删
        //      （若把 canonical() 从 sanitize() 摘掉，原文 "op-…" 与 "OP…" 不相等 → 真编号被误删 → 红）
        assertThat(ProposalNumberGuard.sanitize(echoed, Set.of(REAL_NO)).changed())
                .as("反证④-4：正文分段写法 + 可信集合归一值 → 不得误删")
                .isFalse();
    }
}
