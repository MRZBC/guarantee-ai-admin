package com.guarantee.analysis.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 带 limit 的分析查询条件（地区 TOP N / 机构 TOP N 共用）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AnalysisLimitQuery extends AnalysisCriteria {

    /** 最大返回条数，默认值由各接口决定。 */
    @Min(value = 1, message = "limit 不能小于 1")
    @Max(value = 200, message = "limit 不能超过 200")
    private Integer limit;
}
