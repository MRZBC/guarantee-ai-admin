package com.guarantee.order.service;

import com.guarantee.common.api.PageResult;
import com.guarantee.common.exception.BizException;
import com.guarantee.order.dto.PerformanceOrderQuery;
import com.guarantee.order.mapper.PerformanceOrderMapper;
import com.guarantee.order.vo.OrderStatusNames;
import com.guarantee.order.vo.PerformanceOrderVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 履约订单服务。
 *
 * <p>分层约束：Controller -&gt; Service -&gt; Mapper -&gt; DB，对外只暴露 VO。</p>
 */
@Service
public class PerformanceOrderService {

    private static final Logger log = LoggerFactory.getLogger(PerformanceOrderService.class);

    private final PerformanceOrderMapper performanceOrderMapper;

    public PerformanceOrderService(PerformanceOrderMapper performanceOrderMapper) {
        this.performanceOrderMapper = performanceOrderMapper;
    }

    /** 分页查询履约订单。 */
    @Transactional(readOnly = true)
    public PageResult<PerformanceOrderVO> page(PerformanceOrderQuery query) {
        long total = performanceOrderMapper.countByQuery(query);
        log.debug("履约订单分页查询 orderNo={} region={} orgId={} status={} {}~{} -> total={}",
                query.getOrderNo(), query.getRegionCode(), query.getOrgId(), query.getStatus(),
                query.getStartDate(), query.getEndDate(), total);
        if (total == 0) {
            return PageResult.empty(query.getPageNum(), query.getPageSize());
        }
        List<PerformanceOrderVO> list = performanceOrderMapper
                .selectPage(query, query.offset(), query.getPageSize())
                .stream()
                .map(PerformanceOrderService::fillDerivedFields)
                .toList();
        return PageResult.of(query.getPageNum(), query.getPageSize(), total, list);
    }

    /** 订单详情，不存在则抛 BizException。 */
    @Transactional(readOnly = true)
    public PerformanceOrderVO getById(Long id) {
        PerformanceOrderVO vo = performanceOrderMapper.selectDetailById(id);
        if (vo == null) {
            throw BizException.notFound("履约订单不存在: " + id);
        }
        return fillDerivedFields(vo);
    }

    /** statusName 在 Java 侧翻译，不占用 SQL。 */
    private static PerformanceOrderVO fillDerivedFields(PerformanceOrderVO vo) {
        vo.setStatusName(OrderStatusNames.nameOf(vo.getStatus()));
        return vo;
    }
}
