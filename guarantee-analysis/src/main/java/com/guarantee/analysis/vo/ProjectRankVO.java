package com.guarantee.analysis.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 项目排行（REQ-BA-04 TOP）：按担保金额倒序的项目明细。
 *
 * <p>与 {@link EnterpriseRankVO} 同理，join {@code project} 刻意不带
 * {@code is_deleted}/{@code status}，保证历史项目的名称不会消失。</p>
 */
@Data
public class ProjectRankVO {

    private Long projectId;

    private String projectCode;

    private String projectName;

    /** 项目类型（中文，直接来自库里） */
    private String projectType;

    private String regionCode;

    private String regionName;

    private long orderCount;

    private BigDecimal guaranteeAmount = BigDecimal.ZERO;

    private BigDecimal premiumAmount = BigDecimal.ZERO;
}
