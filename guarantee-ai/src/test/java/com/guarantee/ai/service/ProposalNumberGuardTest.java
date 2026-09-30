package com.guarantee.ai.service;

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
    // 形态边界（T6-01 / D-B）：把"认什么、不认什么"钉住，防静默漂移
    // ------------------------------------------------------------------

    /**
     * 形态对照表（实测输出见任务记录里的 `.agent/ProposalNoProbe` 复现）。
     *
     * <table>
     *   <caption>当前 {@code \bOP\d{8,}\b} 的边界</caption>
     *   <tr><th>输入</th><th>被识别/移除</th></tr>
     *   <tr><td>{@code OP}+≥8 位数字（真实形态 18 位）</td><td>✅</td></tr>
     *   <tr><td>中文/符号前缀或后缀（{@code 编号OP…}、{@code OP…号}、{@code #OP…}）</td><td>✅（词边界成立）</td></tr>
     *   <tr><td>Markdown 装饰（{@code **OP…**}、{@code - OP…}、{@code > OP…}、{@code `OP…`}）</td><td>✅（token 级扫描，装饰不影响）</td></tr>
     *   <tr><td>7 位及以下 / 小写或混合大小写 / 全角</td><td>❌ 不识别</td></tr>
     *   <tr><td>分隔写法（{@code OP-2026…} / {@code OP 2026…}）/ 零宽字符</td><td>❌ 不识别</td></tr>
     *   <tr><td>下划线前缀、字母后缀（{@code _OP…} / {@code OP…X}）/ 无 {@code OP} 前缀的裸数字</td><td>❌ 不识别</td></tr>
     * </table>
     *
     * <p><b>这些 ❌ 是已知的、已登记的形态规避边界（第三阶段验证 §6「既有缺陷 B」，中低），
     * 不是期望行为</b>：模型用非标准形态写一个编造的编号时，既不会移除也不会纠正。
     * 之所以不在本任务里放宽，是因为"改宽"必须同时改归一化比对（提取侧与白名单比对侧都要归一），
     * 否则模型**如实回显**真编号的小写/分段形态反而会被误删——那是另一类风险，需要单独设计。</p>
     *
     * <p>本用例的作用是"边界钉住"：将来若做归一化放宽，这一条会红，逼迫改动者同步更新
     * 对照表与验证报告，而不是让它静默变成另一种行为。</p>
     */
    @Test
    @DisplayName("形态边界（已知登记项，非期望行为）：标准/带前缀后缀/带装饰可拦，非标准形态目前不拦")
    void documentsKnownFormatBoundary() {
        String canonical = "OP202609242359135602";

        // 当前能拦下的形态（含 Markdown 装饰——装饰不是绕过点，形态才是）
        Set<String> recognized = Set.of(
                canonical,
                "OP12345678",
                "编号" + canonical,
                canonical + "号",
                "#" + canonical,
                "**" + canonical + "**",
                "- " + canonical,
                "> " + canonical,
                "`" + canonical + "`");
        for (String form : recognized) {
            ProposalNumberGuard.Result result = ProposalNumberGuard.sanitize("提案编号 " + form + " 已生成。", Set.of());
            assertThat(result.removed())
                    .as("应被识别并移除：%s", form).isNotEmpty();
            assertThat(result.text())
                    .as("移除后正文里不应再有任何编号 token：%s", form).doesNotContain("OP");
        }

        // 已知不拦的形态（登记项）：断言"目前确实拦不住"，以便将来放宽时这条会红
        Set<String> notRecognized = Set.of(
                "OP1234567",
                "op202609242359135602",
                "Op202609242359135602",
                "\uFF2F\uFF30" + "202609242359135602",
                "OP-202609242359135602",
                "OP 202609242359135602",
                "OP\u200b202609242359135602",
                "_OP202609242359135602",
                "OP202609242359135602X",
                "202609242359135602");
        for (String form : notRecognized) {
            ProposalNumberGuard.Result result = ProposalNumberGuard.sanitize("提案编号 " + form + " 已生成。", Set.of());
            assertThat(result.changed())
                    .as("已知边界：当前识别不了这种形态（若已放宽，请同步更新对照表与验证报告）：%s", form)
                    .isFalse();
        }
    }
}
