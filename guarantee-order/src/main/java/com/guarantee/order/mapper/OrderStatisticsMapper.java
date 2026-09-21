package com.guarantee.order.mapper;

import com.guarantee.order.dto.OrderSummaryCriteria;
import com.guarantee.order.vo.OrderSummaryVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrderStatisticsMapper {

    /**
     * 按条件汇总订单指标。投标/履约通过 UNION ALL 合并后再聚合，
     * 保证 orderType=ALL 时去重企业数与项目数口径正确。
     */
    OrderSummaryVO selectSummary(@Param("c") OrderSummaryCriteria criteria);
}
