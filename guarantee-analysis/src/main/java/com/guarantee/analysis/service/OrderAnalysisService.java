package com.guarantee.analysis.service;

import com.guarantee.analysis.dto.AnalysisCriteria;
import com.guarantee.analysis.dto.OrderTrendQuery;
import com.guarantee.analysis.mapper.OrderAnalysisMapper;
import com.guarantee.analysis.vo.OrderInstitutionVO;
import com.guarantee.analysis.vo.OrderInsuranceVO;
import com.guarantee.analysis.vo.OrderRegionVO;
import com.guarantee.analysis.vo.OrderTrendVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 订单多维分析服务（趋势 / 地区 / 险种 / 机构）。
 *
 * <p>四条查询共用同一份 {@code AnalysisCriteria}，Mapper 侧统一用 UNION ALL 合并
 * 投标与履约订单，因此 orderType=ALL 与单类型口径一致。</p>
 */
@Service
public class OrderAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(OrderAnalysisService.class);

    /** 地区分布默认条数。 */
    private static final int DEFAULT_REGION_LIMIT = 10;
    /** 机构分布默认条数。 */
    private static final int DEFAULT_INSTITUTION_LIMIT = 20;
    /** 条数硬上限，避免超大结果集。 */
    private static final int MAX_LIMIT = 200;

    private final OrderAnalysisMapper orderAnalysisMapper;

    public OrderAnalysisService(OrderAnalysisMapper orderAnalysisMapper) {
        this.orderAnalysisMapper = orderAnalysisMapper;
    }

    @Transactional(readOnly = true)
    public List<OrderTrendVO> trend(OrderTrendQuery query) {
        OrderTrendQuery safe = query == null ? new OrderTrendQuery() : query;
        List<OrderTrendVO> list = orderAnalysisMapper.selectTrend(safe);
        list.forEach(OrderAnalysisService::fillTrend);
        log.debug("订单趋势 orderType={} granularity={} {}~{} -> {} 个周期",
                safe.normalizedOrderType(), safe.normalizedGranularity(),
                safe.getStartDate(), safe.getEndDate(), list.size());
        return list;
    }

    @Transactional(readOnly = true)
    public List<OrderRegionVO> regionDistribution(AnalysisCriteria criteria, Integer limit) {
        AnalysisCriteria safe = safe(criteria);
        List<OrderRegionVO> list = orderAnalysisMapper.selectRegionDistribution(
                safe, normalizeLimit(limit, DEFAULT_REGION_LIMIT));
        list.forEach(row -> {
            if (row.getGuaranteeAmount() == null) {
                row.setGuaranteeAmount(BigDecimal.ZERO);
            }
            if (row.getPremiumAmount() == null) {
                row.setPremiumAmount(BigDecimal.ZERO);
            }
        });
        return list;
    }

    @Transactional(readOnly = true)
    public List<OrderInsuranceVO> insuranceDistribution(AnalysisCriteria criteria) {
        List<OrderInsuranceVO> list = orderAnalysisMapper.selectInsuranceDistribution(safe(criteria));
        list.forEach(row -> {
            if (row.getGuaranteeAmount() == null) {
                row.setGuaranteeAmount(BigDecimal.ZERO);
            }
            if (row.getPremiumAmount() == null) {
                row.setPremiumAmount(BigDecimal.ZERO);
            }
        });
        return list;
    }

    @Transactional(readOnly = true)
    public List<OrderInstitutionVO> institutionDistribution(AnalysisCriteria criteria, Integer limit) {
        AnalysisCriteria safe = safe(criteria);
        List<OrderInstitutionVO> list = orderAnalysisMapper.selectInstitutionDistribution(
                safe, normalizeLimit(limit, DEFAULT_INSTITUTION_LIMIT));
        list.forEach(row -> {
            if (row.getGuaranteeAmount() == null) {
                row.setGuaranteeAmount(BigDecimal.ZERO);
            }
            if (row.getPremiumAmount() == null) {
                row.setPremiumAmount(BigDecimal.ZERO);
            }
        });
        return list;
    }

    private static AnalysisCriteria safe(AnalysisCriteria criteria) {
        return criteria == null ? new AnalysisCriteria() : criteria;
    }

    private static int normalizeLimit(Integer limit, int defaultValue) {
        if (limit == null || limit <= 0) {
            return defaultValue;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static void fillTrend(OrderTrendVO row) {
        if (row.getGuaranteeAmount() == null) {
            row.setGuaranteeAmount(BigDecimal.ZERO);
        }
        if (row.getPremiumAmount() == null) {
            row.setPremiumAmount(BigDecimal.ZERO);
        }
    }
}
