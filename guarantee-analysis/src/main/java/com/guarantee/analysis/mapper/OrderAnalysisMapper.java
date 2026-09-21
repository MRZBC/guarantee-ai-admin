package com.guarantee.analysis.mapper;

import com.guarantee.analysis.dto.AnalysisCriteria;
import com.guarantee.analysis.dto.OrderTrendQuery;
import com.guarantee.analysis.vo.OrderInstitutionVO;
import com.guarantee.analysis.vo.OrderInsuranceVO;
import com.guarantee.analysis.vo.OrderRegionVO;
import com.guarantee.analysis.vo.OrderTrendVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 订单多维分析 Mapper（趋势 / 地区 / 险种 / 机构）。
 *
 * <p>所有查询共用 {@code <sql id="orderSource">} 片段：
 * 当 orderType=ALL 时用 UNION ALL 合并 tender_order 与 performance_order，
 * 合并后再分组聚合，保证统计口径与单类型一致。
 * SQL 统一维护在 mapper/analysis/OrderAnalysisMapper.xml。</p>
 */
@Mapper
public interface OrderAnalysisMapper {

    /** 订单趋势，按 period 升序。 */
    List<OrderTrendVO> selectTrend(@Param("c") OrderTrendQuery query);

    /** 订单地区分布，按订单量倒序取前 limit 条。 */
    List<OrderRegionVO> selectRegionDistribution(@Param("c") AnalysisCriteria criteria,
                                                 @Param("limit") int limit);

    /** 订单险种分布，按订单量倒序。 */
    List<OrderInsuranceVO> selectInsuranceDistribution(@Param("c") AnalysisCriteria criteria);

    /** 订单承保机构分布，按订单量倒序取前 limit 条。 */
    List<OrderInstitutionVO> selectInstitutionDistribution(@Param("c") AnalysisCriteria criteria,
                                                          @Param("limit") int limit);
}
