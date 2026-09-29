package com.guarantee.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 「本轮没调任何工具，却给出了业务数字」的兜底校验。
 *
 * <p><b>为什么需要它</b>：{@link DataSourceClaimGuard} 只在模型写下 {@code 口径：} 行时才会
 * 发现"零工具却有数据"。真机上更隐蔽的形态是**不写口径行、直接给数字**：
 * "本季度共 12 笔订单，担保金额 1.5 亿元"——没有任何东西会报错，用户会把凭空捏造的数字
 * 当成有依据的统计。判定条件与口径兜底同样**确定**：本轮一次工具都没执行，则任何业务数字
 * 都不可能有来源。</p>
 *
 * <p><b>宁窄勿宽</b>：只在数字与"业务量词/指标词"紧邻时命中，避免误伤正常表述——
 * {@code 2026 年}（年份）、{@code 50 条}（系统上限）、{@code 3 个维度}（能力说明）、
 * {@code 15 分钟}（有效期）都不得触发。因此量词白名单只收业务专用词
 * （笔 / 元 / 万元 / 亿元），指标词只收业务指标名，且要求同句、邻近。</p>
 *
 * <p><b>与口径兜底的去重由调用方负责</b>：两者都在表达"零工具 → 没有来源"，
 * 同时追加两段「系统提示」只会让用户以为出了两个问题。</p>
 */
@Component
public class NumberClaimGuard {

    private static final Logger log = LoggerFactory.getLogger(NumberClaimGuard.class);

    /** 追加的纠正文案。 */
    public static final String CORRECTION =
            "（系统提示：本轮没有调用任何数据工具，因此上面的业务数字没有数据来源；"
                    + "请勿据此做判断，如需真实数据请重新提问或让我先查询。）";

    /** 数字 + 业务量词（笔 / 元 / 万元 / 亿元）。 */
    private static final Pattern NUMBER_WITH_UNIT =
            Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?\\s*(?:笔|万元|亿元|元)");

    /** 业务指标词 + 邻近数字（允许中间夹 12 个字，含"为/是/共/约"等）。 */
    private static final Pattern LABEL_WITH_NUMBER = Pattern.compile(
            "(?:订单量|订单数|担保金额|保额|保费|投标订单|履约订单|企业数|项目数|成交金额|费率)"
                    + "[^。！？\\n]{0,12}?\\d");

    /**
     * 判断是否需要追加纠正提示。
     *
     * @param answer          本轮模型产出的正文
     * @param anyToolExecuted 本轮请求内是否执行过**任何**工具（读或写）
     */
    public Optional<String> correctionFor(String answer, boolean anyToolExecuted) {
        // 执行过工具就放过：这一轮的数字至少有来源可查（权威数值另由服务端指标块给出），
        // 具体数值对不对属于"引用错误"，不在本兜底职责内。
        if (anyToolExecuted || answer == null || answer.isBlank()) {
            return Optional.empty();
        }
        if (!claimsBusinessNumber(answer)) {
            return Optional.empty();
        }
        log.warn("本轮零工具调用却出现业务数字，追加纠正提示");
        return Optional.of(CORRECTION);
    }

    /** 正文是否出现"有业务含义"的数字。 */
    static boolean claimsBusinessNumber(String answer) {
        return NUMBER_WITH_UNIT.matcher(answer).find() || LABEL_WITH_NUMBER.matcher(answer).find();
    }
}
