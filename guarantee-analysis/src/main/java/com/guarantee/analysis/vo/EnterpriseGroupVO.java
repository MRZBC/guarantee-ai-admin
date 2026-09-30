package com.guarantee.analysis.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 企业维度分布（REQ-BA-03 DISTRIBUTION）。
 *
 * <p>按**行业 / 等级 / 地区**聚合企业数，并附该组下的订单量、保额、保费。</p>
 *
 * <p>分组键同时给编码与名称：行业与等级本身就是可读文本（{@code 建筑} / {@code AAA}），
 * 地区给 {@code region_code} + {@code region_name}，让调用方（AI 工具）不必再翻译一次。</p>
 */
@Data
public class EnterpriseGroupVO {

    /** 分组键：行业名 / 等级 / 地区编码 */
    private String groupCode;

    /** 分组显示名：行业名 / 等级 / 地区名称 */
    private String groupName;

    /** 该组去重企业数 */
    private long enterpriseCount;

    private long orderCount;

    private BigDecimal guaranteeAmount = BigDecimal.ZERO;

    private BigDecimal premiumAmount = BigDecimal.ZERO;
}
