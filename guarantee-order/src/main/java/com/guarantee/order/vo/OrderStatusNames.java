package com.guarantee.order.vo;

import java.util.Map;

/**
 * 订单状态字典：VO 的 statusName 在 Java 侧翻译（而非 SQL CASE WHEN），
 * 保证列表页与详情页口径一致，新增状态只需改这一处。
 *
 * <p>放在 vo 包内，因为它只服务于 VO 展示字段。</p>
 */
public final class OrderStatusNames {

    public static final String DRAFT = "DRAFT";
    public static final String UNDER_REVIEW = "UNDER_REVIEW";
    public static final String EFFECTIVE = "EFFECTIVE";
    public static final String EXPIRED = "EXPIRED";
    public static final String RELEASED = "RELEASED";

    private static final Map<String, String> NAMES = Map.of(
            DRAFT, "草稿",
            UNDER_REVIEW, "审核中",
            EFFECTIVE, "已生效",
            EXPIRED, "已过期",
            RELEASED, "已解除");

    private OrderStatusNames() {
    }

    /** 状态码翻译为中文名称；null 返回 null，未知状态原样返回。 */
    public static String nameOf(String status) {
        if (status == null) {
            return null;
        }
        return NAMES.getOrDefault(status, status);
    }
}
