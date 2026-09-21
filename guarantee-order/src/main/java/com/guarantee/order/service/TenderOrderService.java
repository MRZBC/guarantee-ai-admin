package com.guarantee.order.service;

import com.guarantee.common.api.PageResult;
import com.guarantee.common.exception.BizException;
import com.guarantee.order.dto.TenderOrderQuery;
import com.guarantee.order.mapper.TenderOrderMapper;
import com.guarantee.order.vo.OrderStatusNames;
import com.guarantee.order.vo.TenderOrderVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 投标订单服务。
 *
 * <p>分层约束：Controller -&gt; Service -&gt; Mapper -&gt; DB，对外只暴露 VO。</p>
 */
@Service
public class TenderOrderService {

    private static final Logger log = LoggerFactory.getLogger(TenderOrderService.class);

    private final TenderOrderMapper tenderOrderMapper;

    public TenderOrderService(TenderOrderMapper tenderOrderMapper) {
        this.tenderOrderMapper = tenderOrderMapper;
    }

    /** 分页查询投标订单。 */
    @Transactional(readOnly = true)
    public PageResult<TenderOrderVO> page(TenderOrderQuery query) {
        long total = tenderOrderMapper.countByQuery(query);
        log.debug("投标订单分页查询 orderNo={} region={} orgId={} status={} {}~{} -> total={}",
                query.getOrderNo(), query.getRegionCode(), query.getOrgId(), query.getStatus(),
                query.getStartDate(), query.getEndDate(), total);
        if (total == 0) {
            return PageResult.empty(query.getPageNum(), query.getPageSize());
        }
        List<TenderOrderVO> list = tenderOrderMapper
                .selectPage(query, query.offset(), query.getPageSize())
                .stream()
                .map(TenderOrderService::fillDerivedFields)
                .toList();
        return PageResult.of(query.getPageNum(), query.getPageSize(), total, list);
    }

    /** 订单详情，不存在则抛 BizException。 */
    @Transactional(readOnly = true)
    public TenderOrderVO getById(Long id) {
        TenderOrderVO vo = tenderOrderMapper.selectDetailById(id);
        if (vo == null) {
            throw BizException.notFound("投标订单不存在: " + id);
        }
        return fillDerivedFields(vo);
    }

    /** statusName 在 Java 侧翻译，不占用 SQL。 */
    private static TenderOrderVO fillDerivedFields(TenderOrderVO vo) {
        vo.setStatusName(OrderStatusNames.nameOf(vo.getStatus()));
        return vo;
    }
}
