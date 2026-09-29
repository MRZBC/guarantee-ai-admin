package com.guarantee.ai.metrics;

import com.guarantee.ai.metrics.mapper.AiTurnMetricMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 单轮指标的读写服务（REQ-MCP-09 落库 + REQ-MCP-11 聚合）。
 *
 * <p><b>两条硬约束</b>：</p>
 * <ol>
 *   <li><b>写失败不得影响回答</b>：{@link #record} 自己吞掉异常并告警，返回 {@code false}，
 *       绝不向上抛 —— 与审计（写失败要回滚业务）相反；</li>
 *   <li><b>空结果不编造</b>：没有数据时返回 0 / 空列表，不用 null 或估计值充数，
 *       趋势也不补零（"没跑过"和"跑了且为 0"是两件事）。</li>
 * </ol>
 *
 * <p>参数一律先归一：{@code range} 只认 24h/7d/30d（未知回落 24h），
 * {@code days} 收敛到 1~90，{@code limit} 收敛到 1~20。归一是为了"页面传什么都不会 500"，
 * 同时保证指标卡片的窗口口径可预期。</p>
 *
 * <p>本类不依赖安全上下文：数据范围与权限由调用方（页面接口 / MCP 接口）负责，
 * 指标查询本身只读聚合、不含明细（见红线 §2.3-5：指标不放正文与敏感字段）。</p>
 */
@Service
public class TurnMetricService {

    private static final Logger log = LoggerFactory.getLogger(TurnMetricService.class);

    public static final String RANGE_24H = "24h";
    public static final String RANGE_7D = "7d";
    public static final String RANGE_30D = "30d";

    public static final int DEFAULT_TREND_DAYS = 7;
    public static final int MAX_TREND_DAYS = 90;

    public static final int DEFAULT_TOP_LIMIT = 10;
    public static final int MAX_TOP_LIMIT = 20;

    private final AiTurnMetricMapper mapper;

    /**
     * 查询时间窗口用的时钟。
     *
     * <p><b>为什么是字段而不是构造器参数</b>：本类原先有两个构造器且都没标注，
     * Spring 无法判断用哪个 → "找不到可用的实例化构造器" → 整机启动失败（已实测）。
     * 而把 {@code Clock} 作为构造器参数同样不行——容器里**没有** {@code Clock} bean，
     * 会换成另一个启动失败（NoSuchBeanDefinition）。
     * 因此这里保持**唯一一个公开构造器**（Spring 单构造器无需任何注解，天然无歧义），
     * 时钟做成包内可替换字段：单测用 {@link #withClock} 注入固定时钟。</p>
     */
    Clock clock = Clock.systemDefaultZone();

    public TurnMetricService(AiTurnMetricMapper mapper) {
        this.mapper = mapper;
    }

    /** 测试/嵌入用：指定时钟的实例（不参与 Spring 装配，避免引入并不存在的 Clock bean）。 */
    static TurnMetricService withClock(AiTurnMetricMapper mapper, Clock clock) {
        TurnMetricService service = new TurnMetricService(mapper);
        service.clock = clock == null ? Clock.systemDefaultZone() : clock;
        return service;
    }

    // ==================================================================
    // 写入（只追加）
    // ==================================================================

    /**
     * 追加一行单轮指标。
     *
     * @return true = 已落库；false = 未落库（入参为空或写库失败），调用方**不需要**做任何补偿
     */
    public boolean record(AiTurnMetric metric) {
        if (metric == null) {
            return false;
        }
        normalizeForInsert(metric);
        try {
            mapper.insert(metric);
            return true;
        } catch (RuntimeException ex) {
            // REQ-MCP-09：指标写失败只告警，不影响回答
            log.warn("单轮指标落库失败（降级为仅日志，不影响回答）：conversationId={}, source={}, 原因={}",
                    metric.getConversationId(), metric.getSource(), ex.toString());
            return false;
        }
    }

    /**
     * 写库前的归一（包级可见，便于单测直接断言）。
     *
     * <p>规则：空值/负值 → 0；{@code source}/{@code outcome} 先大写再校验，非法值回落默认并告警；
     * 未触顶时清空 {@code cap_reason}（不留上一次的旧原因）；触顶但没给原因时写 UNKNOWN，
     * 否则"触顶率"能算而"为什么触顶"永远查不到。</p>
     */
    void normalizeForInsert(AiTurnMetric metric) {
        metric.setSource(normalizeSource(metric.getSource()));
        String outcome = normalizeOutcome(metric.getOutcome());
        Integer capped = normalizeFlag(metric.getCapped());
        // 口径自洽：outcome=CAPPED 必须带 capped=1
        if (AiTurnMetric.OUTCOME_CAPPED.equals(outcome)) {
            capped = 1;
        }
        metric.setOutcome(outcome);
        metric.setCapped(capped);
        if (capped == 1) {
            String reason = metric.getCapReason();
            metric.setCapReason(reason == null || reason.isBlank() ? AiTurnMetric.CAP_UNKNOWN : reason.trim());
        } else {
            metric.setCapReason(null);
        }

        metric.setRounds(nonNegative(metric.getRounds()));
        metric.setToolCalls(nonNegative(metric.getToolCalls()));
        metric.setToolCostMs(nonNegative(metric.getToolCostMs()));
        metric.setTotalCostMs(nonNegative(metric.getTotalCostMs()));
        metric.setInputTokens(nonNegative(metric.getInputTokens()));
        metric.setOutputTokens(nonNegative(metric.getOutputTokens()));

        metric.setModel(trimToNull(metric.getModel()));
        metric.setPromptVersion(trimToNull(metric.getPromptVersion()));
        metric.setTraceId(trimToNull(metric.getTraceId()));
    }

    private String normalizeSource(String source) {
        String value = source == null ? "" : source.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case AiTurnMetric.SOURCE_CHAT, AiTurnMetric.SOURCE_MCP, AiTurnMetric.SOURCE_EVAL -> value;
            default -> {
                if (!value.isEmpty()) {
                    log.warn("单轮指标 source 取值非法，回落 CHAT：{}", source);
                }
                yield AiTurnMetric.SOURCE_CHAT;
            }
        };
    }

    private String normalizeOutcome(String outcome) {
        String value = outcome == null ? "" : outcome.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case AiTurnMetric.OUTCOME_SUCCESS, AiTurnMetric.OUTCOME_ERROR, AiTurnMetric.OUTCOME_CAPPED -> value;
            default -> {
                if (!value.isEmpty()) {
                    log.warn("单轮指标 outcome 取值非法，回落 SUCCESS：{}", outcome);
                }
                yield AiTurnMetric.OUTCOME_SUCCESS;
            }
        };
    }

    private static Integer normalizeFlag(Integer flag) {
        return flag != null && flag == 1 ? 1 : 0;
    }

    private static Integer nonNegative(Integer value) {
        return value == null || value < 0 ? 0 : value;
    }

    private static Long nonNegative(Long value) {
        return value == null || value < 0 ? 0L : value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ==================================================================
    // 查询（聚合）
    // ==================================================================

    /** 概览卡：按 {@code range} 归一后取窗口。 */
    public TurnMetricOverview overview(String range) {
        return overview(resolveRange(range));
    }

    public TurnMetricOverview overview(TurnMetricRange range) {
        TurnMetricRange resolved = range == null ? resolveRange(null) : range;
        return TurnMetricOverview.normalized(mapper.selectOverview(resolved.from(), resolved.to()));
    }

    /** 按天趋势：只返回库中真实存在的日期（不补零）。 */
    public List<TurnMetricTrendPoint> trend(Integer days) {
        int normalizedDays = normalizeTrendDays(days);
        LocalDateTime to = LocalDateTime.now(clock);
        LocalDateTime from = to.minusDays(normalizedDays);
        List<TurnMetricTrendPoint> rows = mapper.selectDailyTrend(from, to);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<TurnMetricTrendPoint> result = new ArrayList<>(rows.size());
        for (TurnMetricTrendPoint row : rows) {
            result.add(TurnMetricTrendPoint.normalized(row));
        }
        return result;
    }

    /** Top 工具：调用次数与耗时（p95 用最近秩法）。 */
    public List<ToolCallStat> topTools(Integer limit, String range) {
        TurnMetricRange resolved = resolveRange(range);
        int normalizedLimit = normalizeTopLimit(limit);
        List<ToolCallStat> rows = mapper.selectTopTools(resolved.from(), resolved.to(), normalizedLimit);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<ToolCallStat> result = new ArrayList<>(rows.size());
        for (ToolCallStat row : rows) {
            result.add(ToolCallStat.normalized(row));
        }
        return result;
    }

    /** 提案状态计数（SYS-NF-08）。 */
    public List<ProposalStatusStat> proposalStatusCounts(String range) {
        TurnMetricRange resolved = resolveRange(range);
        List<ProposalStatusStat> rows = mapper.selectProposalStatusCounts(resolved.from(), resolved.to());
        return rows == null ? List.of() : rows;
    }

    // ==================================================================
    // 参数归一
    // ==================================================================

    /** {@code range} → [from, to)；未知取值回落 24h（读接口宁可给默认窗口，也不要 500）。 */
    public TurnMetricRange resolveRange(String range) {
        String label = normalizeRangeLabel(range);
        LocalDateTime to = LocalDateTime.now(clock);
        LocalDateTime from = switch (label) {
            case RANGE_7D -> to.minusDays(7);
            case RANGE_30D -> to.minusDays(30);
            default -> to.minusHours(24);
        };
        return new TurnMetricRange(from, to, label);
    }

    private String normalizeRangeLabel(String range) {
        if (range == null || range.isBlank()) {
            return RANGE_24H;
        }
        String value = range.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case RANGE_24H, RANGE_7D, RANGE_30D -> value;
            default -> {
                log.warn("观测查询 range 取值非法，回落 24h：{}", range);
                yield RANGE_24H;
            }
        };
    }

    /** 趋势天数：空/非正 → 默认 7；超过 90 → 收敛到 90。 */
    public static int normalizeTrendDays(Integer days) {
        if (days == null || days <= 0) {
            return DEFAULT_TREND_DAYS;
        }
        return Math.min(days, MAX_TREND_DAYS);
    }

    /** Top 工具条数：空/非正 → 默认 10；超过 20 → 收敛到 20。 */
    public static int normalizeTopLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_TOP_LIMIT;
        }
        return Math.min(limit, MAX_TOP_LIMIT);
    }
}
