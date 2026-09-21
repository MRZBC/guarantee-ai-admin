package com.guarantee.analysis.service;

import com.guarantee.analysis.dto.ProjectQuery;
import com.guarantee.analysis.mapper.ProjectMapper;
import com.guarantee.analysis.vo.ProjectDetailVO;
import com.guarantee.analysis.vo.ProjectVO;
import com.guarantee.common.api.PageResult;
import com.guarantee.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 项目管理服务（只读分析视图）。
 */
@Service
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    private final ProjectMapper projectMapper;

    public ProjectService(ProjectMapper projectMapper) {
        this.projectMapper = projectMapper;
    }

    @Transactional(readOnly = true)
    public PageResult<ProjectVO> page(ProjectQuery query) {
        long total = projectMapper.countByQuery(query);
        if (total == 0) {
            return PageResult.empty(query.getPageNum(), query.getPageSize());
        }
        List<ProjectVO> list = projectMapper.selectPage(query, query.offset(), query.getPageSize());
        return PageResult.of(query.getPageNum(), query.getPageSize(), total, list);
    }

    @Transactional(readOnly = true)
    public ProjectDetailVO detail(Long id) {
        ProjectDetailVO detail = projectMapper.selectDetailById(id);
        if (detail == null) {
            throw BizException.notFound("项目不存在: " + id);
        }
        ProjectDetailVO metrics = projectMapper.selectOrderMetricsByProjectId(id);
        mergeMetrics(detail, metrics);
        log.debug("项目详情 id={} tenderOrders={} performanceOrders={}",
                id, detail.getTenderOrderCount(), detail.getPerformanceOrderCount());
        return detail;
    }

    /** 合并订单指标：MyBatis 映射 null 会覆盖原值，因此逐字段判空后再写入。 */
    private static void mergeMetrics(ProjectDetailVO detail, ProjectDetailVO metrics) {
        if (metrics != null) {
            detail.setTenderOrderCount(metrics.getTenderOrderCount());
            detail.setPerformanceOrderCount(metrics.getPerformanceOrderCount());
            if (metrics.getTotalGuaranteeAmount() != null) {
                detail.setTotalGuaranteeAmount(metrics.getTotalGuaranteeAmount());
            }
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
