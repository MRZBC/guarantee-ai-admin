package com.guarantee.analysis.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 企业排行（REQ-BA-03 TOP）：按订单量或保额倒序的企业明细。
 *
 * <p>{@code entName} 必须"历史保留"：join {@code enterprise} 刻意不带
 * {@code is_deleted} / {@code status} 条件——已删除/停用企业的历史订单，
 * 榜单里仍要显示它的名字，否则会出现"金额最大的一行没有名字"
 * （见 {@code docs/DEC-订单筛选下拉的选项口径.md} §2.4）。</p>
 */
@Data
public class EnterpriseRankVO {

    private Long enterpriseId;

    private String entCode;

    private String entName;

    /** 行业（历史口径，与订单筛选下拉一致） */
    private String industry;

    /** 等级：AAA/AA/A/BBB */
    private String entLevel;

    private long orderCount;

    private BigDecimal guaranteeAmount = BigDecimal.ZERO;

    private BigDecimal premiumAmount = BigDecimal.ZERO;
}
