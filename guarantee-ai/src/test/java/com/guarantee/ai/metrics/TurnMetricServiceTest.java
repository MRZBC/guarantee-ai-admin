package com.guarantee.ai.metrics;

import com.guarantee.ai.metrics.mapper.AiTurnMetricMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 单轮指标服务单元测试（TEST-MCP-02 的不连库部分）。
 *
 * <p>重心是三件最容易做错的事：</p>
 * <ol>
 *   <li><b>写失败不影响回答</b>：指标写库异常必须被吞掉并告警，不能把异常抛给聊天链路；</li>
 *   <li><b>空结果不编造</b>：没有数据时返回 0 / 空列表，而不是 null 或估计值；</li>
 *   <li><b>参数归一</b>：range / days / limit 的非法值必须有确定的回落，不能让页面 500。</li>
 * </ol>
 */
class TurnMetricServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-30T02:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);

    private AiTurnMetricMapper mapper;
    private TurnMetricService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AiTurnMetricMapper.class);
        service = TurnMetricService.withClock(mapper, FIXED_CLOCK);
    }

    private static AiTurnMetric sampleMetric() {
        AiTurnMetric metric = new AiTurnMetric();
        metric.setConversationId(11L);
        metric.setMessageId(22L);
        metric.setUserId(33L);
        metric.setModel(" deepseek-chat ");
        metric.setPromptVersion(" v3 ");
        metric.setRounds(2);
        metric.setToolCalls(3);
        metric.setToolCostMs(120L);
        metric.setTotalCostMs(4500L);
        metric.setInputTokens(1200);
        metric.setOutputTokens(800);
        metric.setCapped(0);
        metric.setTraceId(" trace-1 ");
        return metric;
    }

    // ==================================================================
    // 写入（REQ-MCP-09：写失败只告警，不影响回答）
    // ==================================================================

    @Test
    @DisplayName("写入前归一：空 source/outcome 回落默认，空值与负值归零，文本去空白")
    void shouldNormalizeBeforeInsert() {
        AiTurnMetric metric = new AiTurnMetric();
        metric.setConversationId(1L);
        metric.setUserId(2L);
        metric.setRounds(null);
        metric.setToolCalls(-5);
        metric.setToolCostMs(null);
        metric.setTotalCostMs(-1L);
        metric.setInputTokens(null);
        metric.setOutputTokens(null);
        metric.setCapped(null);
        metric.setSource(null);
        metric.setOutcome(null);
        metric.setModel("  ");
        metric.setPromptVersion("");
        metric.setTraceId("  ");

        assertThat(service.record(metric)).as("归一后应当落库").isTrue();

        ArgumentCaptor<AiTurnMetric> captor = ArgumentCaptor.forClass(AiTurnMetric.class);
        verify(mapper).insert(captor.capture());
        AiTurnMetric saved = captor.getValue();

        assertThat(saved.getSource()).isEqualTo(AiTurnMetric.SOURCE_CHAT);
        assertThat(saved.getOutcome()).isEqualTo(AiTurnMetric.OUTCOME_SUCCESS);
        assertThat(saved.getCapped()).isZero();
        assertThat(saved.getCapReason()).as("未触顶不得留旧原因").isNull();
        assertThat(saved.getRounds()).isZero();
        assertThat(saved.getToolCalls()).as("负值计数必须归零").isZero();
        assertThat(saved.getToolCostMs()).isZero();
        assertThat(saved.getTotalCostMs()).isZero();
        assertThat(saved.getInputTokens()).isZero();
        assertThat(saved.getOutputTokens()).isZero();
        assertThat(saved.getModel()).isNull();
        assertThat(saved.getPromptVersion()).isNull();
        assertThat(saved.getTraceId()).isNull();
    }

    @Test
    @DisplayName("source/outcome 大小写归一，只接受枚举值（MCP 来源不得被当成 CHAT）")
    void shouldNormalizeEnumerations() {
        AiTurnMetric metric = sampleMetric();
        metric.setSource("mcp");
        metric.setOutcome(" error ");

        service.record(metric);

        ArgumentCaptor<AiTurnMetric> captor = ArgumentCaptor.forClass(AiTurnMetric.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getSource()).isEqualTo(AiTurnMetric.SOURCE_MCP);
        assertThat(captor.getValue().getOutcome()).isEqualTo(AiTurnMetric.OUTCOME_ERROR);
    }

    @Test
    @DisplayName("触顶但没给原因时写 UNKNOWN，否则触顶率能算、原因永远查不到")
    void shouldFillUnknownCapReasonWhenCapped() {
        AiTurnMetric metric = sampleMetric();
        metric.setCapped(1);
        metric.setCapReason("  ");

        service.record(metric);

        ArgumentCaptor<AiTurnMetric> captor = ArgumentCaptor.forClass(AiTurnMetric.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getCapped()).isEqualTo(1);
        assertThat(captor.getValue().getCapReason()).isEqualTo(AiTurnMetric.CAP_UNKNOWN);
    }

    @Test
    @DisplayName("口径自洽：outcome=CAPPED 必须带 capped=1（不能出现「说要触顶了但标志为 0」）")
    void cappedOutcomeMustSetFlag() {
        AiTurnMetric metric = sampleMetric();
        metric.setOutcome(AiTurnMetric.OUTCOME_CAPPED);
        metric.setCapped(0);

        service.record(metric);

        ArgumentCaptor<AiTurnMetric> captor = ArgumentCaptor.forClass(AiTurnMetric.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getCapped()).isEqualTo(1);
    }

    @Test
    @DisplayName("写库失败只告警：record 返回 false 且不向调用方抛异常（不影响回答）")
    void writeFailureMustNotPropagate() {
        when(mapper.insert(any())).thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatCode(() -> service.record(sampleMetric())).doesNotThrowAnyException();
        assertThat(service.record(sampleMetric())).isFalse();
    }

    @Test
    @DisplayName("入参为 null 时静默跳过，不触碰 Mapper")
    void nullMetricIsIgnored() {
        assertThat(service.record(null)).isFalse();
        verifyNoInteractions(mapper);
    }

    // ==================================================================
    // 概览卡（空结果不编造 + 比率口径）
    // ==================================================================

    @Test
    @DisplayName("没有任何数据时概览全为 0，而不是 null 或估计值")
    void overviewOfEmptyPeriod() {
        when(mapper.selectOverview(any(), any())).thenReturn(null);

        TurnMetricOverview overview = service.overview("24h");

        assertThat(overview.getTurns()).isZero();
        assertThat(overview.getErrorTurns()).isZero();
        assertThat(overview.getCappedTurns()).isZero();
        assertThat(overview.getErrorRate()).isZero();
        assertThat(overview.getCappedRate()).isZero();
        assertThat(overview.getAvgRounds()).isZero();
        assertThat(overview.getAvgTotalCostMs()).isZero();
        assertThat(overview.getInputTokens()).isZero();
        assertThat(overview.getOutputTokens()).isZero();
    }

    @Test
    @DisplayName("聚合行的 NULL（无行时 SUM/AVG 必为 NULL）被归一成 0")
    void overviewNullAggregatesBecomeZero() {
        TurnMetricOverview raw = new TurnMetricOverview();
        raw.setTurns(0L);
        when(mapper.selectOverview(any(), any())).thenReturn(raw);

        TurnMetricOverview overview = service.overview("7d");

        assertThat(overview.getAvgRounds()).isZero();
        assertThat(overview.getAvgTotalCostMs()).isZero();
        assertThat(overview.getInputTokens()).isZero();
        assertThat(overview.getOutputTokens()).isZero();
    }

    @Test
    @DisplayName("失败率 / 触顶率由服务端算出：2/10、3/10")
    void overviewComputesRates() {
        TurnMetricOverview raw = new TurnMetricOverview();
        raw.setTurns(10L);
        raw.setErrorTurns(2L);
        raw.setCappedTurns(3L);
        raw.setAvgRounds(2.4);
        raw.setAvgTotalCostMs(5100.0);
        raw.setInputTokens(1000L);
        raw.setOutputTokens(400L);
        when(mapper.selectOverview(any(), any())).thenReturn(raw);

        TurnMetricOverview overview = service.overview("24h");

        assertThat(overview.getErrorRate()).isEqualTo(0.2);
        assertThat(overview.getCappedRate()).isEqualTo(0.3);
        assertThat(overview.getAvgRounds()).isEqualTo(2.4);
        assertThat(overview.getInputTokens()).isEqualTo(1000L);
    }

    // ==================================================================
    // range / days / limit 归一
    // ==================================================================

    @Test
    @DisplayName("range=24h 的时间窗口是 [now-24h, now)")
    void range24hWindow() {
        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);

        service.overview("24h");

        verify(mapper).selectOverview(from.capture(), to.capture());
        assertThat(to.getValue()).isEqualTo(NOW);
        assertThat(from.getValue()).isEqualTo(NOW.minusHours(24));
    }

    @Test
    @DisplayName("range 归一：大小写与空白可容错；7d/30d/未知值都有确定窗口")
    void rangeNormalization() {
        assertThat(service.resolveRange(" 7D ").label()).isEqualTo(TurnMetricService.RANGE_7D);
        assertThat(service.resolveRange(" 7D ").from()).isEqualTo(NOW.minusDays(7));
        assertThat(service.resolveRange("30d").label()).isEqualTo(TurnMetricService.RANGE_30D);
        assertThat(service.resolveRange("30d").from()).isEqualTo(NOW.minusDays(30));
        assertThat(service.resolveRange("去年").label()).as("非法 range 回落 24h 而不是 500")
                .isEqualTo(TurnMetricService.RANGE_24H);
        assertThat(service.resolveRange(null).label()).isEqualTo(TurnMetricService.RANGE_24H);
    }

    @Test
    @DisplayName("趋势天数归一：空/0 → 7，超上限 → 90")
    void trendDaysNormalization() {
        assertThat(TurnMetricService.normalizeTrendDays(null)).isEqualTo(7);
        assertThat(TurnMetricService.normalizeTrendDays(0)).isEqualTo(7);
        assertThat(TurnMetricService.normalizeTrendDays(-3)).isEqualTo(7);
        assertThat(TurnMetricService.normalizeTrendDays(30)).isEqualTo(30);
        assertThat(TurnMetricService.normalizeTrendDays(365)).isEqualTo(90);

        service.trend(365);
        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).selectDailyTrend(from.capture(), to.capture());
        assertThat(to.getValue()).isEqualTo(NOW);
        assertThat(from.getValue()).isEqualTo(NOW.minusDays(90));
    }

    @Test
    @DisplayName("趋势空结果返回空列表（不补零、不插值）")
    void trendEmpty() {
        when(mapper.selectDailyTrend(any(), any())).thenReturn(null);
        assertThat(service.trend(null)).isEmpty();
    }

    @Test
    @DisplayName("趋势行里的 NULL 字段被归一成 0，日期原样保留")
    void trendRowsAreNormalized() {
        TurnMetricTrendPoint raw = new TurnMetricTrendPoint();
        raw.setStatDate(java.time.LocalDate.of(2026, 9, 29));
        raw.setTurns(5L);
        when(mapper.selectDailyTrend(any(), any())).thenReturn(List.of(raw));

        List<TurnMetricTrendPoint> points = service.trend(7);

        assertThat(points).hasSize(1);
        assertThat(points.get(0).getStatDate()).isEqualTo(java.time.LocalDate.of(2026, 9, 29));
        assertThat(points.get(0).getTurns()).isEqualTo(5L);
        assertThat(points.get(0).getErrorTurns()).isZero();
        assertThat(points.get(0).getAvgTotalCostMs()).isZero();
    }

    @Test
    @DisplayName("Top 工具条数归一：空/0 → 10，超上限 → 20")
    void topLimitNormalization() {
        assertThat(TurnMetricService.normalizeTopLimit(null)).isEqualTo(10);
        assertThat(TurnMetricService.normalizeTopLimit(0)).isEqualTo(10);
        assertThat(TurnMetricService.normalizeTopLimit(5)).isEqualTo(5);
        assertThat(TurnMetricService.normalizeTopLimit(100)).isEqualTo(20);

        service.topTools(100, "24h");
        verify(mapper).selectTopTools(any(), any(), eq(20));
    }

    @Test
    @DisplayName("Top 工具空结果返回空列表")
    void topToolsEmpty() {
        when(mapper.selectTopTools(any(), any(), anyInt())).thenReturn(List.of());
        assertThat(service.topTools(null, null)).isEmpty();
    }

    @Test
    @DisplayName("提案状态计数空结果返回空列表（页面显示 0 而不是崩）")
    void proposalCountsEmpty() {
        when(mapper.selectProposalStatusCounts(any(), any())).thenReturn(null);
        assertThat(service.proposalStatusCounts("7d")).isEmpty();
    }

    @Test
    @DisplayName("提案状态计数按状态返回，不丢行")
    void proposalCountsPassThrough() {
        ProposalStatusStat executed = new ProposalStatusStat();
        executed.setStatus("EXECUTED");
        executed.setStatusCount(4L);
        ProposalStatusStat rejected = new ProposalStatusStat();
        rejected.setStatus("REJECTED");
        rejected.setStatusCount(1L);
        when(mapper.selectProposalStatusCounts(any(), any())).thenReturn(List.of(executed, rejected));

        List<ProposalStatusStat> stats = service.proposalStatusCounts("7d");

        assertThat(stats).hasSize(2);
        assertThat(stats).extracting(ProposalStatusStat::getStatus)
                .containsExactly("EXECUTED", "REJECTED");
        assertThat(stats.get(0).getStatusCount()).isEqualTo(4L);
    }

    @Test
    @DisplayName("sampleMetric 原样落库：字段与 AI_TURN_COST 日志一一对应，文本去空白")
    void sampleMetricRoundTrip() {
        AiTurnMetric metric = sampleMetric();
        metric.setOutcome(AiTurnMetric.OUTCOME_SUCCESS);

        service.record(metric);

        ArgumentCaptor<AiTurnMetric> captor = ArgumentCaptor.forClass(AiTurnMetric.class);
        verify(mapper).insert(captor.capture());
        AiTurnMetric saved = captor.getValue();
        assertThat(saved.getConversationId()).isEqualTo(11L);
        assertThat(saved.getMessageId()).isEqualTo(22L);
        assertThat(saved.getUserId()).isEqualTo(33L);
        assertThat(saved.getRounds()).isEqualTo(2);
        assertThat(saved.getToolCalls()).isEqualTo(3);
        assertThat(saved.getToolCostMs()).isEqualTo(120L);
        assertThat(saved.getTotalCostMs()).isEqualTo(4500L);
        assertThat(saved.getInputTokens()).isEqualTo(1200);
        assertThat(saved.getOutputTokens()).isEqualTo(800);
        assertThat(saved.getModel()).isEqualTo("deepseek-chat");
        assertThat(saved.getPromptVersion()).isEqualTo("v3");
        assertThat(saved.getTraceId()).isEqualTo("trace-1");
    }
}
