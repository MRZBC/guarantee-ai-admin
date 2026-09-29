package com.guarantee.ai.metrics;

import java.time.LocalDateTime;

/**
 * 观测查询的时间区间（**归一后的**）。
 *
 * <p>页面/接口只传 {@code range=24h|7d|30d}，由 {@link TurnMetricService#resolveRange(String)}
 * 统一换算成 [from, to) 区间：右开区间，避免边界那一秒被算两次。</p>
 *
 * @param from  区间起点（含）
 * @param to    区间终点（不含）
 * @param label 归一后的区间标签（一定是 24h / 7d / 30d 之一）
 */
public record TurnMetricRange(LocalDateTime from, LocalDateTime to, String label) {
}
