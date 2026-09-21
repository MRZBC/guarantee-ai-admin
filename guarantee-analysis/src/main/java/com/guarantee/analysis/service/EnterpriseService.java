package com.guarantee.analysis.service;

import com.guarantee.analysis.dto.EnterpriseQuery;
import com.guarantee.analysis.mapper.EnterpriseMapper;
import com.guarantee.analysis.vo.EnterpriseDetailVO;
import com.guarantee.analysis.vo.EnterpriseVO;
import com.guarantee.common.api.PageResult;
import com.guarantee.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 企业管理服务（只读分析视图）。
 *
 * <p>列表的订单指标来自 UNION ALL 派生的 LEFT JOIN，无订单企业显示为 0。</p>
 */
@Service
public class EnterpriseService {

    private static final Logger log = LoggerFactory.getLogger(EnterpriseService.class);

    private final EnterpriseMapper enterpriseMapper;

    public EnterpriseService(EnterpriseMapper enterpriseMapper) {
        this.enterpriseMapper = enterpriseMapper;
    }

    @Transactional(readOnly = true)
    public PageResult<EnterpriseVO> page(EnterpriseQuery query) {
        long total = enterpriseMapper.countByQuery(query);
        if (total == 0) {
            return PageResult.empty(query.getPageNum(), query.getPageSize());
        }
        List<EnterpriseVO> list = enterpriseMapper.selectPage(query, query.offset(), query.getPageSize());
        list.forEach(row -> {
            if (row.getTotalGuaranteeAmount() == null) {
                row.setTotalGuaranteeAmount(BigDecimal.ZERO);
            }
        });
        return PageResult.of(query.getPageNum(), query.getPageSize(), total, list);
    }

    @Transactional(readOnly = true)
    public EnterpriseDetailVO detail(Long id) {
        EnterpriseDetailVO detail = enterpriseMapper.selectDetailById(id);
        if (detail == null) {
            throw BizException.notFound("企业不存在: " + id);
        }
        EnterpriseDetailVO metrics = enterpriseMapper.selectOrderMetricsByEnterpriseId(id);
        mergeMetrics(detail, metrics);
        log.debug("企业详情 id={} orderCount={} projectCount={}",
                id, detail.getOrderCount(), detail.getProjectCount());
        return detail;
    }

    /** 合并细分指标：仅覆盖非空字段，避免把列表口径的值冲掉。 */
    private static void mergeMetrics(EnterpriseDetailVO detail, EnterpriseDetailVO metrics) {
        if (metrics != null) {
            detail.setProjectCount(metrics.getProjectCount());
            detail.setTenderOrderCount(metrics.getTenderOrderCount());
            detail.setPerformanceOrderCount(metrics.getPerformanceOrderCount());
            if (metrics.getTotalPremiumAmount() != null) {
                detail.setTotalPremiumAmount(metrics.getTotalPremiumAmount());
            }
        }
        if (detail.getTotalGuaranteeAmount() == null) {
            detail.setTotalGuaranteeAmount(BigDecimal.ZERO);
        }
        if (detail.getTotalPremiumAmount() == null) {
            detail.setTotalPremiumAmount(BigDecimal.ZERO);
        }
    }
}
