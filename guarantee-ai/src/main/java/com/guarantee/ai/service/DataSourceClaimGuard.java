package com.guarantee.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

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
 * <p><b>已知边界（不隐瞒）</b>：只匹配**行首**的口径声明（允许前置空白）。若模型把口径行
 * 混在句子中间（如"以上按口径：xxx 统计"）则不会命中——宁可漏报，也不要误伤
 * "我们按同一口径统计"这类正当表述。</p>
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
            "（系统提示：本轮没有调用任何数据工具，上面的「口径：」不是系统回显，"
                    + "请勿据此认为数据有来源。如需真实数据，请重新提问。）";

    /** 口径行的前缀。后端的两个生成点用的都是全角冒号，这里两种都收。 */
    private static final String PREFIX_FULL_WIDTH = "口径：";

    private static final String PREFIX_HALF_WIDTH = "口径:";

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
     * <p>按行判定且要求**行首**（允许缩进）：后端追加的口径行就是独占一行、
     * 行首即"口径："，照抄它的模型也会落在行首。行中出现的一律不算，
     * 以免误伤"我们按同一口径统计"这类正常表述。</p>
     */
    static boolean claimsDataSource(String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        for (String line : answer.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith(PREFIX_FULL_WIDTH) || trimmed.startsWith(PREFIX_HALF_WIDTH)) {
                return true;
            }
        }
        return false;
    }
}
