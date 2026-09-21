package com.guarantee.analysis.service;

import com.guarantee.analysis.mapper.OverviewMapper;
import com.guarantee.analysis.vo.OverviewCountVO;
import com.guarantee.analysis.vo.OverviewVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 数据概览服务。
 *
 * <p>口径说明：订单量与金额来自 tender_order / performance_order 的 UNION ALL 合并结果；
 * 企业与项目为两张订单表去重计数；数据时间范围取两表 apply_date 的 MIN/MAX。</p>
 */
@Service
public class OverviewService {

    private static final Logger log = LoggerFactory.getLogger(OverviewService.class);

    private final OverviewMapper overviewMapper;

    public OverviewService(OverviewMapper overviewMapper) {
        this.overviewMapper = overviewMapper;
    }

    @Transactional(readOnly = true)
    public OverviewVO overview() {
        OverviewVO order = overviewMapper.selectOrderOverview();
        OverviewVO vo = order == null ? new OverviewVO() : order;
        // 空表兜底：聚合结果可能为 null，统一转成 0，避免前端出现 null
        vo.setTenderGuaranteeAmount(zeroIfNull(vo.getTenderGuaranteeAmount()));
        vo.setPerformanceGuaranteeAmount(zeroIfNull(vo.getPerformanceGuaranteeAmount()));
        vo.setTotalGuaranteeAmount(zeroIfNull(vo.getTotalGuaranteeAmount()));
        vo.setTotalPremiumAmount(zeroIfNull(vo.getTotalPremiumAmount()));

        OverviewCountVO count = overviewMapper.selectOverviewCount();
        if (count != null) {
            vo.setEnterpriseCount(count.getEnterpriseCount());
            vo.setProjectCount(count.getProjectCount());
            vo.setOrgCount(count.getOrgCount());
            vo.setEffectiveOrderCount(count.getEffectiveOrderCount());
        }

        log.debug("数据概览 totalOrderCount={} totalGuaranteeAmount={} {}~{}",
                vo.getTotalOrderCount(), vo.getTotalGuaranteeAmount(),
                vo.getDataStartDate(), vo.getDataEndDate());
        return vo;
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
