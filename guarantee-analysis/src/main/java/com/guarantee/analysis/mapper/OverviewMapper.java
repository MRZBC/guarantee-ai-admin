package com.guarantee.analysis.mapper;

import com.guarantee.analysis.vo.OverviewCountVO;
import com.guarantee.analysis.vo.OverviewVO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 数据概览 Mapper。
 *
 * <p>订单口径：投标/履约通过 UNION ALL 合并后再聚合，保证 orderType=ALL 与单类型口径一致。
 * SQL 统一维护在 mapper/analysis/OverviewMapper.xml。</p>
 */
@Mapper
public interface OverviewMapper {

    /** 订单规模与金额指标（含数据时间范围）。 */
    OverviewVO selectOrderOverview();

    /** 企业数 / 项目数 / 机构数 / 生效订单量。 */
    OverviewCountVO selectOverviewCount();
}
