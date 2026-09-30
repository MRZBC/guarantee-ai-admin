package com.guarantee.analysis.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 项目维度分布（REQ-BA-04 DISTRIBUTION）。
 *
 * <p>按**项目类型**（房建/市政/交通/水利/其他，库里就是中文）或**地区**聚合项目数与订单指标。</p>
 *
 * <p>项目类型**直接返回中文**（{@code project.project_type} 本身即中文枚举，与项目管理页一致），
 * 不在这里翻译、也不让模型翻译——翻译一层就多一次编造的机会。</p>
 */
@Data
public class ProjectGroupVO {

    /** 分组键：项目类型（中文）/ 地区编码 */
    private String groupCode;

    /** 分组显示名：项目类型（中文）/ 地区名称 */
    private String groupName;

    /** 该组去重项目数 */
    private long projectCount;

    private long orderCount;

    private BigDecimal guaranteeAmount = BigDecimal.ZERO;

    private BigDecimal premiumAmount = BigDecimal.ZERO;
}
