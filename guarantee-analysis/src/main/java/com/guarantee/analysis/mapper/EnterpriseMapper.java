package com.guarantee.analysis.mapper;

import com.guarantee.analysis.dto.EnterpriseQuery;
import com.guarantee.analysis.vo.EnterpriseDetailVO;
import com.guarantee.analysis.vo.EnterpriseVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 企业管理 Mapper。
 *
 * <p>列表通过 LEFT JOIN 一张 UNION ALL 派生表聚合订单指标，
 * 因此没有订单的企业也会以 0 出现。SQL 统一维护在 mapper/analysis/EnterpriseMapper.xml。</p>
 */
@Mapper
public interface EnterpriseMapper {

    /** 企业分页列表（订单指标聚合两张订单表）。 */
    List<EnterpriseVO> selectPage(@Param("q") EnterpriseQuery query,
                                  @Param("offset") int offset,
                                  @Param("limit") int limit);

    /** 与 selectPage 同条件的总数。 */
    long countByQuery(@Param("q") EnterpriseQuery query);

    /** 企业详情基础信息 + 列表口径的订单指标。 */
    EnterpriseDetailVO selectDetailById(@Param("id") Long id);

    /** 企业详情订单细分指标：投标/履约 UNION ALL 后按 enterprise_id 聚合。 */
    EnterpriseDetailVO selectOrderMetricsByEnterpriseId(@Param("id") Long id);
}
