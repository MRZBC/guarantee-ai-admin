package com.guarantee.analysis.mapper;

import com.guarantee.analysis.dto.AnalysisCriteria;
import com.guarantee.analysis.dto.OrderTrendQuery;
import com.guarantee.analysis.vo.EnterpriseGroupVO;
import com.guarantee.analysis.vo.EnterpriseRankVO;
import com.guarantee.analysis.vo.OrderInstitutionVO;
import com.guarantee.analysis.vo.OrderInsuranceVO;
import com.guarantee.analysis.vo.OrderRegionVO;
import com.guarantee.analysis.vo.OrderTrendVO;
import com.guarantee.analysis.vo.ProjectGroupVO;
import com.guarantee.analysis.vo.ProjectRankVO;
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

    /**
     * 企业维度分布（REQ-BA-03）：按 {@code dimension}（INDUSTRY / LEVEL / REGION）聚合企业数 + 订单指标。
     *
     * <p>{@code dimension} 必须是调用方**归一化后**的取值（Service 层负责校验并给可读中文错误）。</p>
     */
    List<EnterpriseGroupVO> selectEnterpriseDistribution(@Param("c") AnalysisCriteria criteria,
                                                         @Param("dimension") String dimension,
                                                         @Param("limit") int limit);

    /** 企业排行（REQ-BA-03 TOP）：{@code orderBy} 取 ORDER_COUNT / GUARANTEE_AMOUNT。 */
    List<EnterpriseRankVO> selectEnterpriseTop(@Param("c") AnalysisCriteria criteria,
                                              @Param("orderBy") String orderBy,
                                              @Param("limit") int limit);

    /** 项目维度分布（REQ-BA-04）：{@code dimension} 取 PROJECT_TYPE / REGION。 */
    List<ProjectGroupVO> selectProjectDistribution(@Param("c") AnalysisCriteria criteria,
                                                   @Param("dimension") String dimension,
                                                   @Param("limit") int limit);

    /** 项目排行（REQ-BA-04 TOP）：按担保金额倒序。 */
    List<ProjectRankVO> selectProjectTop(@Param("c") AnalysisCriteria criteria,
                                         @Param("limit") int limit);
}
