package com.guarantee.ai.controller;

import com.guarantee.ai.metrics.ProposalStatusStat;
import com.guarantee.ai.metrics.ToolCallStat;
import com.guarantee.ai.metrics.TurnMetricOverview;
import com.guarantee.ai.metrics.TurnMetricRange;
import com.guarantee.ai.metrics.TurnMetricService;
import com.guarantee.ai.metrics.TurnMetricTrendPoint;
import com.guarantee.common.api.Result;
import com.guarantee.common.security.Permissions;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 运行可视化接口（REQ-MCP-11 / AC-MCP-09）。
 *
 * <p><b>只做聚合的"出口"，不做聚合本身</b>：所有 SQL 都在 {@link TurnMetricService} 与
 * {@code AiTurnMetricMapper}（T5-01 已交付并验收）。本类只负责三件事：参数归一（复用 Service
 * 的归一逻辑，页面传什么都不会 500）、权限兜底、把结果按页面需要的形状包一层。
 * 在这里再写一份聚合 SQL = 两处口径，必然漂移（AC-MCP-09 要的恰恰是"页面数字 == 直接查库聚合"）。</p>
 *
 * <p><b>权限</b>：整类 {@code @PreAuthorize} → 只读权限 {@code system:audit:view}（与操作审计页同一枚，
 * REQ §5.3.4 的裁定）。这是**只读**页面：本类没有任何写接口。前端隐藏菜单不算安全边界，
 * 因此三个接口逐个都有服务端兜底（类级注解对本类所有方法生效）。</p>
 *
 * <p><b>为什么提案计数挂在 overview 里</b>：它和概览卡用的是**同一个时间窗口**，
 * 单独开一个接口只会让页面为一块小卡片多打一次请求、并多一处窗口不一致的可能。
 * 返回体仍是"概览 + 提案计数"两块，字段语义与 Service 的聚合一一对应。</p>
 *
 * <p><b>空数据不编造</b>：没有数据时返回 0 / 空列表（Service 已保证），趋势**不补零**——
 * "没跑过"和"跑了且为 0"是两件事，页面据实渲染空态。</p>
 */
@RestController
@RequestMapping("/api/ai/metrics")
@PreAuthorize("hasAuthority('" + Permissions.AUDIT_VIEW + "')")
public class AiMetricsController {

    private final TurnMetricService turnMetricService;

    public AiMetricsController(TurnMetricService turnMetricService) {
        this.turnMetricService = turnMetricService;
    }

    /**
     * 概览卡 + 提案状态计数（同一时间窗口）。
     *
     * @param range 24h / 7d / 30d；缺省或非法 → 24h（Service 归一）
     */
    @GetMapping("/overview")
    public Result<OverviewResponse> overview(@RequestParam(required = false) String range) {
        TurnMetricRange window = turnMetricService.resolveRange(range);
        TurnMetricOverview overview = turnMetricService.overview(window);
        // 提案计数没有 TurnMetricRange 重载（Service 已验收的签名只接受 range 字符串），
        // 传**归一后**的窗口标签：与概览卡同一窗口语义，且不会被非法值影响。
        List<ProposalStatusStat> proposals = turnMetricService.proposalStatusCounts(window.label());
        return Result.ok(new OverviewResponse(window.label(), overview, proposals));
    }

    /**
     * 按天趋势（只返回库中真实存在的日期，不补零）。
     *
     * @param days 1~90；缺省 → 7（Service 归一）
     */
    @GetMapping("/trend")
    public Result<TrendResponse> trend(@RequestParam(required = false) Integer days) {
        int normalizedDays = TurnMetricService.normalizeTrendDays(days);
        List<TurnMetricTrendPoint> points = turnMetricService.trend(normalizedDays);
        return Result.ok(new TrendResponse(normalizedDays, points));
    }

    /**
     * Top 工具（调用次数 + 平均/最大/p95 耗时）。
     *
     * @param limit 1~20；缺省 → 10
     * @param range 24h / 7d / 30d；缺省 → 24h
     */
    @GetMapping("/tools/top")
    public Result<ToolsResponse> topTools(@RequestParam(required = false) Integer limit,
                                          @RequestParam(required = false) String range) {
        TurnMetricRange window = turnMetricService.resolveRange(range);
        List<ToolCallStat> tools = turnMetricService.topTools(limit, range);
        return Result.ok(new ToolsResponse(window.label(), tools));
    }

    /** 概览响应：`range` 是**归一后**的窗口标签（页面按它显示"近 24 小时"）。 */
    public record OverviewResponse(String range, TurnMetricOverview overview,
                                   List<ProposalStatusStat> proposals) {
    }

    /** 趋势响应：`days` 是归一后的天数（页面按它显示"近 N 天"）。 */
    public record TrendResponse(int days, List<TurnMetricTrendPoint> points) {
    }

    /** Top 工具响应：`range` 是归一后的窗口标签。 */
    public record ToolsResponse(String range, List<ToolCallStat> tools) {
    }
}
