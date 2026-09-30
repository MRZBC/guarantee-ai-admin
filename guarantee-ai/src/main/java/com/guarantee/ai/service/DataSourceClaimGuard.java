package com.guarantee.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 「本轮没调任何工具，却写出了口径行」的兜底校验（提示词第 32 / 39 条的代码级兜底）。
 *
 * <p><b>为什么必须由后端拦</b>：正文末尾那行 {@code 口径：<dataSource>} 是**业务用户判断
 * "这批数字从哪来"的唯一凭据**。而它只能由后端的 {@code DataSourceText}（读工具）与
 * {@code BaseProposalTool}（写工具）生成——也就是说，<b>本轮一次工具都没执行却出现口径行，
 * 必然是编造</b>。真机已复现：模型没调用任何写工具，却写了一句
 * {@code 口径：用户配置 · 目标: user0005 张涛 · 变更类型：角色分配 · 数据范围：全量…}
 * ——它把上一轮见过的字符串重新拼了一个"像模像样"的，格式其实并不对。</p>
 *
 * <p>这类编造的危害大于"少一张确认卡"：如果它发生在**读查询**上，用户会把凭空捏造的
 * 数字当成"有依据的统计"，而系统里没有任何东西会报错。</p>
 *
 * <p><b>判定为什么极准</b>：条件只需两条——① 本轮请求内**没有任何工具执行**；
 * ② 正文出现形如 {@code 口径：…} 的独立行。两条同时成立时不存在"如实回答"的可能，
 * 误报率远低于按话术猜测的 {@link ProposalClaimGuard}。因此这里可以比它更果断。</p>
 *
 * <p><b>已知边界（不隐瞒）</b>：只匹配**行首**的口径声明（允许前置空白与 Markdown 装饰）。
 * 若模型把口径行混在句子中间（如"以上按口径：xxx 统计"）则不会命中——宁可漏报，也不要误伤
 * "我们按同一口径统计"这类正当表述。</p>
 *
 * <p><b>与"知识来源行"守卫保持对称</b>（{@code KnowledgeClaimGuard}）：两者是同一件事的两个面
 * （服务端才是产出口），装饰容忍规则必须一致。曾经只有知识侧做了装饰容忍，口径侧停留在窄式
 * 正则，于是模型换个写法（{@code **口径：…**}、{@code - 口径：…}…）就能把假口径留在正文里
 * ——这是"同源逻辑两处实现必然漂移"的又一次实证。</p>
 *
 * <p><b>口径的产出已上收到服务端</b>：提示词不再要求模型写口径行，改由
 * {@link #footer(List)} 用本轮真实执行的工具返回值生成、{@link #stripDataSourceLines(String)}
 * 把模型自写的口径行剥离。{@code correctionFor} 保留原职责（零工具却有口径行 = 编造），
 * 它现在是"模型没遵守新指令"的兜底。三者是同一件事的三个面：**口径只能由服务端产出**。</p>
 */
@Component
public class DataSourceClaimGuard {

    private static final Logger log = LoggerFactory.getLogger(DataSourceClaimGuard.class);

    /**
     * 追加的纠正文案。
     *
     * <p>必须点明"不是系统回显"：用户之所以会信那行字，正是因为它长得像系统输出。</p>
     */
    public static final String CORRECTION =
            "（系统提示：本轮没有调用任何数据工具，因此没有任何口径；正文里那行「口径：」"
                    + "不是系统回显，已由系统移除。请勿据此认为数据有来源。如需真实数据，请重新提问。）";

    /** 口径行的前缀（全角冒号）。后端的两个生成点用的都是这个形态。 */
    private static final String PREFIX_FULL_WIDTH = "口径：";

    /**
     * Markdown 装饰前缀/后缀（与 {@code KnowledgeClaimGuard} 同一套）：列表/引用/标题/表格符、
     * 行内代码、强调符。
     *
     * <p><b>为什么必须容忍</b>：真机复现的绕过就是"换个写法"——
     * {@code **口径：…**}、{@code - 口径：…}、{@code > 口径：…}、{@code 口径 ：…}、
     * {@code **口径**: …}、{@code `口径：…`}。窄式正则（只认裸行）对它们既不剥离也不纠正，
     * 假口径会留在正文里并落库。</p>
     */
    private static final String DECORATION = "(?:[-*+>|#]|`{1,3}|\\*{1,2}|_{1,2})";

    /**
     * 口径行的整行匹配（行首 + 可选装饰 + 「口径」+ 可选装饰 + 可选空白 + 冒号 + 行尾换行）。
     *
     * <p>用 {@code MULTILINE} 而不是按 {@code \R} 切分再拼回：切分/拼回会顺手改写换行符，
     * 让"没改动"的正文也被判定为改动，进而触发一次无谓的 reset 重发。</p>
     */
    private static final Pattern DATA_SOURCE_LINE = Pattern.compile(
            "^[ \\t]*" + DECORATION + "*[ \\t]*口径[ \\t]*(?:" + DECORATION + ")*[ \\t]*[：:][^\\r\\n]*(?:\\R|$)",
            Pattern.MULTILINE);

    /**
     * 是否出现"声明口径"的行首标记（与 {@link #DATA_SOURCE_LINE} 同一套装饰容忍规则）。
     *
     * <p>两者共用一套规则是刻意的：判定与剥离若不同源，就会出现"被判定为编造却没有剥离"
     * （或反过来）的中间态。</p>
     */
    private static final Pattern CLAIM_LINE = Pattern.compile(
            "(?m)^[ \\t]*" + DECORATION + "*[ \\t]*口径[ \\t]*(?:" + DECORATION + ")*[ \\t]*[：:]");

    /**
     * 判断是否需要追加纠正提示。
     *
     * @param answer           本轮模型产出的正文
     * @param anyToolExecuted  本轮请求内是否执行过**任何**工具（读或写）
     * @return 需要纠正时返回纠正文案，否则返回 {@code Optional#empty()}
     */
    public Optional<String> correctionFor(String answer, boolean anyToolExecuted) {
        // 执行过工具就放过：就算这一轮的口径行抄错了，也属于"引用错误"而非"凭空编造"，
        // 不在本兜底的职责内（本类只处理"零工具"这一确定性场景）。
        if (anyToolExecuted) {
            return Optional.empty();
        }
        if (!claimsDataSource(answer)) {
            return Optional.empty();
        }
        log.warn("本轮零工具调用却出现口径行，追加纠正提示");
        return Optional.of(CORRECTION);
    }

    /**
     * 正文是否包含形如 {@code 口径：…} 的独立行。
     *
     * <p>按行判定且要求**行首**（允许缩进与 Markdown 装饰）：后端追加的口径行就是独占一行、
     * 行首即"口径："，照抄它的模型也会落在行首；模型改用 {@code **口径：…**} 之类写法同样算。
     * 行中出现的一律不算，以免误伤"我们按同一口径统计"这类正常表述。</p>
     */
    static boolean claimsDataSource(String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        return CLAIM_LINE.matcher(answer).find();
    }

    /**
     * 剥离模型自写的口径行（服务端是口径的唯一出口）。
     *
     * <p>模型仍可能按旧习惯写出 {@code 口径：…}，或为了"像模像样"自己拼一条。
     * 无论哪种，正文里的口径行都不再被采信——服务端会在收尾时用本轮真实工具返回值
     * 重新生成（见 {@link #footer(List)}）。剥离而不是"检测后纠正"，是因为
     * 编造与照抄在文本上无法区分：唯一可靠的做法是让模型写的口径行一律不生效。</p>
     *
     * <p>行中出现的"口径"字样不受影响（那是正常表述）；正文未被改动时**原样返回**，
     * 以便调用方据此判断要不要重发。</p>
     */
    public static String stripDataSourceLines(String answer) {
        if (answer == null || answer.isBlank()) {
            return answer == null ? "" : answer;
        }
        String stripped = DATA_SOURCE_LINE.matcher(answer).replaceAll("");
        if (stripped.equals(answer)) {
            return answer;
        }
        // 移除整行后可能留下尾部空行：去掉，避免与服务端追加的页脚之间出现双重空行
        return stripped.replaceAll("[ \\t\\r\\n]+$", "");
    }

    /**
     * 服务端口径页脚：本轮**实际执行**的每个工具一行。
     *
     * <p>格式与提示词要求过的完全一致（{@code 口径：…}），因此用户看到的形态没有变化，
     * 变化的只是"这行字由谁产出"——从"模型照抄"变成"服务端生成"，模型再也无从编造。</p>
     *
     * @param dataSources 本轮工具返回的 dataSource（顺序即工具执行顺序，调用方负责去重）
     * @return 需要追加的页脚（含前置空行）；没有可用口径时返回空串
     */
    public static String footer(List<String> dataSources) {
        if (dataSources == null || dataSources.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>(dataSources.size());
        for (String dataSource : dataSources) {
            if (dataSource != null && !dataSource.isBlank()) {
                lines.add(PREFIX_FULL_WIDTH + dataSource);
            }
        }
        return lines.isEmpty() ? "" : "\n\n" + String.join("\n", lines);
    }
}
