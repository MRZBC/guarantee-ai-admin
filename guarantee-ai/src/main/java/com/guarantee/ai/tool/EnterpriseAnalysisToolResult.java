package com.guarantee.ai.tool;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@code queryEnterpriseAnalysis} 的返回值（SYS-Q-07：必须是 record）。
 *
 * <p><b>为什么有它</b>：企业维度是 AC-BA-03 的验收对象，也是仅剩的两条"不成立"之一。
 * 没有它时，模型只能用 {@code queryOrderSummary} 逐个企业去试——枚举不全就得出
 * "这些区域由单一企业独家承做"这类与事实相反的结论（机构维度的同款事故见
 * {@link OrderDistributionTool} 的类注释）。</p>
 *
 * <p><b>两种模式共用一个 items 列表</b>，靠 {@link #mode()} 判断哪些字段有意义：</p>
 * <ul>
 *   <li>{@code DISTRIBUTION}：按行业/等级/地区分组，{@code code/name}=分组键，
 *       {@code enterpriseCount} 有意义；</li>
 *   <li>{@code TOP}：企业排行，{@code code}=企业编码、{@code name}=企业名称，
 *       {@code industry}/{@code entLevel} 有意义。</li>
 * </ul>
 *
 * <p>{@code enterpriseCount} 在 TOP 模式下恒为 0（单个企业不构成"企业数"），
 * 不返回 null 是为了让模型不必处理可空字段——口径由 {@code mode} 说清。</p>
 */
public record EnterpriseAnalysisToolResult(
        /** 归一化后的模式：DISTRIBUTION / TOP */
        String mode,
        /** 归一化后的维度：INDUSTRY / LEVEL / REGION（TOP 模式下为回显值） */
        String dimension,
        /** TOP 模式的排序依据：ORDER_COUNT / GUARANTEE_AMOUNT；DISTRIBUTION 模式下为 ORDER_COUNT */
        String orderBy,
        /** 归一化后的订单类型：TENDER / PERFORMANCE / ALL */
        String orderType,
        String startDate,
        String endDate,
        /** 回显的区域编码（未过滤时为 null） */
        String regionCode,
        /** 分组明细或排行明细，按订单量/保额倒序 */
        List<EnterpriseItem> items,
        ToolResultMeta meta) {

    /** 一条企业分组 / 一家企业。 */
    public record EnterpriseItem(
            /** 分组键（行业名 / 等级 / 地区编码）或企业编码 */
            String code,
            /** 分组显示名（行业名 / 等级 / 地区名称）或企业名称 */
            String name,
            /** 该组去重企业数（仅 DISTRIBUTION 有意义） */
            long enterpriseCount,
            long orderCount,
            BigDecimal guaranteeAmount,
            BigDecimal premiumAmount,
            /** 行业（仅 TOP 有意义） */
            String industry,
            /** 等级 AAA/AA/A/BBB（仅 TOP 有意义） */
            String entLevel) {
    }
}
