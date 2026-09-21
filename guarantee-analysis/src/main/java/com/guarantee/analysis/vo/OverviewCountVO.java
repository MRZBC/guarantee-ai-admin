package com.guarantee.analysis.vo;

import lombok.Data;

/**
 * 数据概览的基数统计（企业与项目跨两张订单表去重）。
 */
@Data
public class OverviewCountVO {

    /** 去重企业数（两张订单表 UNION 后去重） */
    private long enterpriseCount;
    /** 去重项目数（两张订单表 UNION 后去重） */
    private long projectCount;
    /** 机构数（sys_org） */
    private long orgCount;
    /** 生效订单量（status=EFFECTIVE，两表合计） */
    private long effectiveOrderCount;
}
