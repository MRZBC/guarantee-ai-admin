package com.guarantee.ai.tool;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@code queryProjectAnalysis} 的返回值（SYS-Q-07：必须是 record）。
 *
 * <p><b>口径要点</b>：项目类型是**中文枚举**（房建/市政/交通/水利/其他），库里存的就是中文，
 * 这里**直接透传中文**，不翻译、也不要求模型翻译——翻译一层就多一次编造的机会
 * （与项目管理页同一口径）。</p>
 *
 * <p>两种模式共用一个 items 列表，靠 {@link #mode()} 判断字段含义：</p>
 * <ul>
 *   <li>{@code DISTRIBUTION}：按项目类型/地区分组，{@code code/name}=分组键，
 *       {@code projectCount} 有意义；</li>
 *   <li>{@code TOP}：项目排行，{@code code}=项目编码、{@code name}=项目名称，
 *       {@code projectType}/{@code regionName} 有意义。</li>
 * </ul>
 */
public record ProjectAnalysisToolResult(
        /** 归一化后的模式：DISTRIBUTION / TOP */
        String mode,
        /** 归一化后的维度：PROJECT_TYPE / REGION */
        String dimension,
        /** 归一化后的订单类型：TENDER / PERFORMANCE / ALL */
        String orderType,
        String startDate,
        String endDate,
        String regionCode,
        /** 占比基线：GUARANTEE_AMOUNT（担保金额）——DISTRIBUTION 模式下 share = 该组担保额占该维度合计的百分比 */
        String shareBase,
        /** 分组明细或排行明细 */
        List<ProjectItem> items,
        ToolResultMeta meta) {

    /** 一条项目类型/地区分组，或一个项目。 */
    public record ProjectItem(
            /** 分组键（项目类型中文 / 地区编码）或项目编码 */
            String code,
            /** 分组显示名（项目类型中文 / 地区名称）或项目名称 */
            String name,
            /** 该组去重项目数（仅 DISTRIBUTION 有意义） */
            long projectCount,
            long orderCount,
            BigDecimal guaranteeAmount,
            BigDecimal premiumAmount,
            /** 服务端算好的担保金额占比（百分比 2 位小数；分类合计=100.00）。模型必须直接引用，不要自己除 */
            BigDecimal share,
            /** 项目类型（中文，TOP 模式下有值） */
            String projectType,
            /** 地区名称（TOP 模式下有值） */
            String regionName) {
    }
}
