package com.guarantee.ai.metrics.mapper;

import com.guarantee.ai.metrics.AiTurnMetric;
import com.guarantee.ai.metrics.ProposalStatusStat;
import com.guarantee.ai.metrics.ToolCallStat;
import com.guarantee.ai.metrics.TurnMetricOverview;
import com.guarantee.ai.metrics.TurnMetricTrendPoint;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 单轮指标 Mapper（{@code ai_turn_metric} + 两个只读聚合来源）。
 *
 * <p><b>只追加</b>：本接口刻意不提供 {@code update*} / {@code delete*} 方法
 * （单测会守住这一点）——指标是流水，写完即历史。</p>
 *
 * <p>四个聚合查询对应 REQ-MCP-11 §5.3.4 的四个面板：
 * {@link #selectOverview} 概览卡、{@link #selectDailyTrend} 趋势、
 * {@link #selectTopTools} Top 工具、{@link #selectProposalStatusCounts} 提案状态。</p>
 *
 * <p>时间区间统一为 {@code [from, to)} 右开；{@code limit} 由 Service 先归一（1~20）。</p>
 */
@Mapper
public interface AiTurnMetricMapper {

    /** 追加一行单轮指标（含失败与触顶；写失败由调用方兜住，不影响回答）。 */
    int insert(AiTurnMetric metric);

    /** 概览卡：问答数 / 失败数 / 触顶数 / 平均轮次 / 平均耗时 / token 合计。 */
    TurnMetricOverview selectOverview(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 按天趋势（只返回库中真实存在的日期）。 */
    List<TurnMetricTrendPoint> selectDailyTrend(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Top 工具：调用次数与耗时（含 p95，最近秩法）。 */
    List<ToolCallStat> selectTopTools(@Param("from") LocalDateTime from,
                                      @Param("to") LocalDateTime to,
                                      @Param("limit") int limit);

    /** 提案状态计数（兑现 SYS-NF-08）。 */
    List<ProposalStatusStat> selectProposalStatusCounts(@Param("from") LocalDateTime from,
                                                        @Param("to") LocalDateTime to);
}
