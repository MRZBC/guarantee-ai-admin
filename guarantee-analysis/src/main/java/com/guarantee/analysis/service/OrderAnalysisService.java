package com.guarantee.analysis.service;

import com.guarantee.analysis.dto.AnalysisCriteria;
import com.guarantee.analysis.dto.OrderTrendQuery;
import com.guarantee.analysis.mapper.OrderAnalysisMapper;
import com.guarantee.analysis.vo.EnterpriseGroupVO;
import com.guarantee.analysis.vo.EnterpriseRankVO;
import com.guarantee.analysis.vo.OrderInstitutionVO;
import com.guarantee.analysis.vo.OrderInsuranceVO;
import com.guarantee.analysis.vo.OrderRegionVO;
import com.guarantee.analysis.vo.OrderTrendVO;
import com.guarantee.analysis.vo.ProjectGroupVO;
import com.guarantee.analysis.vo.ProjectRankVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 订单多维分析服务（趋势 / 地区 / 险种 / 机构 / 企业 / 项目）。
 *
 * <p>查询共用同一份 {@code AnalysisCriteria}，Mapper 侧统一用 UNION ALL 合并
 * 投标与履约订单，因此 orderType=ALL 与单类型口径一致。</p>
 *
 * <p>企业与项目两个维度（REQ-BA-03/04）额外遵守一条口径：企业名/项目名必须**历史保留**
 * （见 {@code docs/DEC-订单筛选下拉的选项口径.md} §2.4），实现细节在 Mapper XML 的注释里。</p>
 */
@Service
public class OrderAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(OrderAnalysisService.class);

    /** 地区分布默认条数。 */
    private static final int DEFAULT_REGION_LIMIT = 10;
    /** 机构分布默认条数。 */
    private static final int DEFAULT_INSTITUTION_LIMIT = 20;
    /** 企业维度默认条数（行业/等级分组本身不多，10 条够用）。 */
    private static final int DEFAULT_ENTERPRISE_LIMIT = 10;
    /** 项目维度默认条数。 */
    private static final int DEFAULT_PROJECT_LIMIT = 10;
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

    /**
     * 按维度归一化占比（百分比、2 位小数），**合计正好 100.00**。
     *
     * <p>为什么不让模型自己除：实测同一批数据"人工算 22.31% / 模型答 22.29%"，
     * 0.02pp 漂移且无法判定谁对。这里用**最大余数法**：先按万分位向下取整，
     * 再把剩下的万分位补给它的小数部分最大的那几组——既无浮点累积误差，
     * 也不会出现"99.99 / 100.01"这种无法判定的尾差。</p>
     *
     * <p>包可见是**故意的**：单测直接覆盖它，不用起数据库。</p>
     */
    static List<BigDecimal> sharesOf(List<BigDecimal> values) {
        int n = values.size();
        List<BigDecimal> shares = new ArrayList<>(n);
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            total = total.add(value == null ? BigDecimal.ZERO : value);
        }
        if (total.signum() <= 0) {
            for (int i = 0; i < n; i += 1) {
                shares.add(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            }
            return shares;
        }
        long[] basis = new long[n];
        BigDecimal[] fraction = new BigDecimal[n];
        long used = 0;
        for (int i = 0; i < n; i += 1) {
            BigDecimal value = values.get(i) == null ? BigDecimal.ZERO : values.get(i);
            BigDecimal exact = value.multiply(BigDecimal.valueOf(10000))
                    .divide(total, 6, RoundingMode.HALF_UP);
            BigDecimal floor = exact.setScale(0, RoundingMode.FLOOR);
            basis[i] = floor.longValue();
            fraction[i] = exact.subtract(floor);
            used += basis[i];
        }
        long remainder = 10000 - used;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i += 1) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> fraction[b].compareTo(fraction[a]));
        for (long k = 0; k < remainder && n > 0; k += 1) {
            basis[order[(int) (k % n)]] += 1;
        }
        for (int i = 0; i < n; i += 1) {
            shares.add(BigDecimal.valueOf(basis[i]).movePointLeft(2));
        }
        return shares;
    }

    // ------------------------------------------------------------------
    // REQ-BA-03 / REQ-BA-04：企业与项目维度（M2.3）
    // ------------------------------------------------------------------

    /**
     * 企业维度分布：按行业 / 等级 / 地区聚合企业数与订单指标。
     *
     * @param dimension INDUSTRY（行业）/ LEVEL（等级）/ REGION（地区），大小写与中文别名都接受
     * @throws IllegalArgumentException 维度取值无法识别时给**可读中文**（不把堆栈抛给模型）
     */
    @Transactional(readOnly = true)
    public List<EnterpriseGroupVO> enterpriseDistribution(AnalysisCriteria criteria,
                                                          String dimension, Integer limit) {
        List<EnterpriseGroupVO> list = orderAnalysisMapper.selectEnterpriseDistribution(
                safe(criteria), EnterpriseDimension.normalize(dimension).name(),
                normalizeLimit(limit, DEFAULT_ENTERPRISE_LIMIT));
        // 占比基线 = 订单量（"哪些行业的企业下单最多"）；按返回的分组做最大余数归一 ⇒ 合计 100.00
        List<BigDecimal> shares = sharesOf(list.stream()
                .map(row -> BigDecimal.valueOf(row.getOrderCount())).toList());
        for (int i = 0; i < list.size(); i += 1) {
            EnterpriseGroupVO row = list.get(i);
            row.setShare(shares.get(i));
            if (row.getGuaranteeAmount() == null) {
                row.setGuaranteeAmount(BigDecimal.ZERO);
            }
            if (row.getPremiumAmount() == null) {
                row.setPremiumAmount(BigDecimal.ZERO);
            }
        }
        return list;
    }

    /** 企业排行：{@code orderBy} 取 ORDER_COUNT（默认）/ GUARANTEE_AMOUNT。 */
    @Transactional(readOnly = true)
    public List<EnterpriseRankVO> enterpriseTop(AnalysisCriteria criteria,
                                                String orderBy, Integer limit) {
        List<EnterpriseRankVO> list = orderAnalysisMapper.selectEnterpriseTop(
                safe(criteria), EnterpriseOrderBy.normalize(orderBy).name(),
                normalizeLimit(limit, DEFAULT_ENTERPRISE_LIMIT));
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

    /** 项目维度分布：按项目类型（中文）或地区聚合项目数与订单指标。 */
    @Transactional(readOnly = true)
    public List<ProjectGroupVO> projectDistribution(AnalysisCriteria criteria,
                                                    String dimension, Integer limit) {
        List<ProjectGroupVO> list = orderAnalysisMapper.selectProjectDistribution(
                safe(criteria), ProjectDimension.normalize(dimension).name(),
                normalizeLimit(limit, DEFAULT_PROJECT_LIMIT));
        // 占比基线 = 担保金额（"某类项目的担保额占比"就是这么问的）
        List<BigDecimal> shares = sharesOf(list.stream()
                .map(row -> row.getGuaranteeAmount() == null ? BigDecimal.ZERO : row.getGuaranteeAmount())
                .toList());
        for (int i = 0; i < list.size(); i += 1) {
            ProjectGroupVO row = list.get(i);
            row.setShare(shares.get(i));
            if (row.getGuaranteeAmount() == null) {
                row.setGuaranteeAmount(BigDecimal.ZERO);
            }
            if (row.getPremiumAmount() == null) {
                row.setPremiumAmount(BigDecimal.ZERO);
            }
        }
        return list;
    }

    /** 项目排行：按担保金额倒序。 */
    @Transactional(readOnly = true)
    public List<ProjectRankVO> projectTop(AnalysisCriteria criteria, Integer limit) {
        List<ProjectRankVO> list = orderAnalysisMapper.selectProjectTop(
                safe(criteria), normalizeLimit(limit, DEFAULT_PROJECT_LIMIT));
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

    /** 企业分布的维度：英文码 + 中文别名都接受（模型偶尔直接给"行业"）。 */
    public enum EnterpriseDimension {
        INDUSTRY, LEVEL, REGION;

        public static EnterpriseDimension normalize(String raw) {
            String text = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
            return switch (text) {
                case "", "INDUSTRY", "行业" -> INDUSTRY;
                case "LEVEL", "ENT_LEVEL", "等级" -> LEVEL;
                case "REGION", "地区", "区域" -> REGION;
                default -> throw new IllegalArgumentException(
                        "企业维度只能是 INDUSTRY（行业）、LEVEL（等级）或 REGION（地区），实际收到: " + raw);
            };
        }
    }

    /** 企业排行的排序依据。 */
    public enum EnterpriseOrderBy {
        ORDER_COUNT, GUARANTEE_AMOUNT;

        public static EnterpriseOrderBy normalize(String raw) {
            String text = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
            return switch (text) {
                case "", "ORDER_COUNT", "订单量", "ORDERCOUNT" -> ORDER_COUNT;
                case "GUARANTEE_AMOUNT", "保额", "担保金额", "AMOUNT" -> GUARANTEE_AMOUNT;
                default -> throw new IllegalArgumentException(
                        "企业排行依据只能是 ORDER_COUNT（订单量）或 GUARANTEE_AMOUNT（保额），实际收到: " + raw);
            };
        }
    }

    /** 项目分布的维度。 */
    public enum ProjectDimension {
        PROJECT_TYPE, REGION;

        public static ProjectDimension normalize(String raw) {
            String text = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
            return switch (text) {
                case "", "PROJECT_TYPE", "类型", "项目类型" -> PROJECT_TYPE;
                case "REGION", "地区", "区域" -> REGION;
                default -> throw new IllegalArgumentException(
                        "项目维度只能是 PROJECT_TYPE（项目类型）或 REGION（地区），实际收到: " + raw);
            };
        }
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
