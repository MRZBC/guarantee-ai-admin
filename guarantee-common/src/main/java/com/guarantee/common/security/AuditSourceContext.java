package com.guarantee.common.security;

/**
 * 审计来源上下文（AC-22 的关键机制）。
 *
 * <p><b>要解决的问题</b>：同一个业务写 Service 会被两条渠道调用——</p>
 * <ul>
 *   <li><b>页面直连</b>：发生在带 JWT 的 Web 线程上，{@link CurrentUser} 有效；</li>
 *   <li><b>助手确认</b>：发生在 {@code ProposalService.confirm} 的线程上，
 *       {@code CurrentUser} 可能为空（尤其是集成测试与异步执行场景），
 *       且该路径**已经**由 {@code ProposalService} 自己写了一条 {@code source=AI} 的审计。</li>
 * </ul>
 *
 * <p>如果写 Service 无条件按"页面直连"处理，就会出现两个错误：
 * ① 助手渠道的变更被记成 {@code source=WEB}（追溯时找不到是谁通过助手改的）；
 * ② 同一次变更产生两条审计（{@code ProposalService} 一条 + 写 Service 一条）。</p>
 *
 * <p><b>做法</b>：在"非页面直连"的执行路径上显式压入本上下文，写 Service 据此
 * **跳过**自己写审计（让上层负责），从而保证"一次变更恰好一条审计、来源正确"。</p>
 *
 * <p>用 {@link ThreadLocal} 而不是实例字段：{@code ProposalService.confirm} 对 executor 的调用
 * 是同步方法调用（非 Reactor 切换），线程亲和性成立。用 try/finally 保证清理，
 * 避免线程复用导致来源串台。</p>
 */
public final class AuditSourceContext {

    private static final ThreadLocal<String> SOURCE = new ThreadLocal<>();

    /** 助手确认来源：由 {@code ProposalService.confirm} 压入。 */
    public static final String AI = "AI";

    /** 页面直连来源：默认值（未压入上下文时按页面处理）。 */
    public static final String WEB = "WEB";

    private AuditSourceContext() {
    }

    /** 当前来源；未显式设置时返回 {@link #WEB}。 */
    public static String current() {
        String source = SOURCE.get();
        return source == null ? WEB : source;
    }

    /** 是否处于"助手确认"执行路径中。 */
    public static boolean isAiDriven() {
        return AI.equals(SOURCE.get());
    }

    /**
     * 标记后续的业务写操作为助手发起。
     *
     * <p>调用方必须用 try/finally 配对 {@link #clear()}。</p>
     */
    public static void markAiDriven() {
        SOURCE.set(AI);
    }

    public static void clear() {
        SOURCE.remove();
    }
}
