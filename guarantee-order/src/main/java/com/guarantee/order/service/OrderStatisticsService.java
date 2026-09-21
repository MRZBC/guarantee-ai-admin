package com.guarantee.order.service;

import com.guarantee.order.dto.OrderSummaryCriteria;
import com.guarantee.order.mapper.OrderStatisticsMapper;
import com.guarantee.order.vo.OrderSummaryVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 订单统计服务。
 *
 * <p>分层约束：AI Tool 只能调用本 Service，不能直接访问 Mapper。
 * 调用链必须保持 Tool -&gt; Service -&gt; Mapper -&gt; DB。</p>
 */
@Service
public class OrderStatisticsService {

    private static final Logger log = LoggerFactory.getLogger(OrderStatisticsService.class);

    private final OrderStatisticsMapper orderStatisticsMapper;

    public OrderStatisticsService(OrderStatisticsMapper orderStatisticsMapper) {
        this.orderStatisticsMapper = orderStatisticsMapper;
    }

    /**
     * 汇总订单核心指标。
     *
     * @param criteria 查询条件；日期为 null 表示不限
     * @return 永不为 null 的汇总结果（空结果时各数值为 0）
     */
    @Transactional(readOnly = true)
    public OrderSummaryVO summarize(OrderSummaryCriteria criteria) {
        OrderSummaryCriteria safe = criteria == null ? new OrderSummaryCriteria() : criteria;
        OrderSummaryVO summary = orderStatisticsMapper.selectSummary(safe);
        if (summary == null) {
            summary = new OrderSummaryVO();
        }
        if (summary.getGuaranteeAmount() == null) {
            summary.setGuaranteeAmount(BigDecimal.ZERO);
        }
        if (summary.getPremiumAmount() == null) {
            summary.setPremiumAmount(BigDecimal.ZERO);
        }
        log.debug("订单汇总 orderType={} {}~{} region={} orgId={} -> count={}",
                safe.normalizedOrderType(), safe.getStartDate(), safe.getEndDate(),
                safe.getRegionCode(), safe.getOrgId(), summary.getOrderCount());
        return summary;
    }
}
