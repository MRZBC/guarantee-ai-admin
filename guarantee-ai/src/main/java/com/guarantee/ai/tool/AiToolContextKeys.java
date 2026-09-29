package com.guarantee.ai.tool;

/**
 * 通过 Spring AI {@code ToolContext} 在「聊天请求」与「Tool 执行」之间传递的上下文键。
 *
 * <p>Tool 执行发生在模型回调内部，拿不到 Web 层参数，因此会话信息与**权限快照**
 * 都必须随 ToolContext 下传（SYS-P-03）。</p>
 *
 * <p><b>为什么权限要下传而不是回查</b>：Tool 执行线程上没有 {@code HttpServletRequest}、
 * {@code CurrentUser} ThreadLocal 已被清理、也没有 Spring Security 上下文。
 * 唯一可靠的来源就是请求线程在构造 ToolContext 时写入的这份快照。</p>
 */
public final class AiToolContextKeys {

    /** 当前会话 ID（Long）。 */
    public static final String CONVERSATION_ID = "aiConversationId";

    /** 当前用户 ID（Long）。 */
    public static final String USER_ID = "aiUserId";

    /** 当前用户账号（String）。 */
    public static final String USERNAME = "aiUsername";

    /** 当前用户姓名（String）。 */
    public static final String REAL_NAME = "aiRealName";

    /** 当前用户所属机构 ID（Long）。 */
    public static final String ORG_ID = "aiOrgId";

    /** 当前用户启用权限编码（{@code List<String>}）。 */
    public static final String PERMISSIONS = "aiPermissions";

    /** 当前用户启用角色编码（{@code List<String>}）。 */
    public static final String ROLES = "aiRoles";

    /** 本次请求的 TraceId（String）。 */
    public static final String TRACE_ID = "aiTraceId";

    /**
     * 用户本轮原话（String）。
     *
     * <p>确认卡必须同时展示"用户原话"与"模型解析后的参数"，让用户核对模型有没有理解错
     * （SYS-C-15 / RK-01）。原话在 Tool 线程上拿不到请求体，因此随 ToolContext 下传。</p>
     */
    public static final String USER_TEXT = "aiUserText";

    /** 用于把 Tool Call 实时推给 SSE 的 {@code Sinks.Many<ToolCallEvent>}。 */
    public static final String EVENT_SINK = "aiToolEventSink";

    /**
     * 用于把「写工具生成的提案」实时推给 SSE 的事件通道。
     *
     * <p>与 {@link #EVENT_SINK} 分开：工具调用记录与提案是两种不同的 SSE 事件载荷。</p>
     */
    public static final String PROPOSAL_SINK = "aiProposalSink";

    /**
     * 本轮的 {@link TurnFacts} 收集器（服务端口径与提案编号的唯一真值来源）。
     *
     * <p>收尾时服务端要用它生成口径页脚、校验正文里的提案编号，因此必须与工具执行
     * 共享同一个实例——工具线程拿不到 Web 层参数，只能靠 ToolContext 传下去。</p>
     */
    public static final String TURN_FACTS = "aiTurnFacts";

    private AiToolContextKeys() {
    }
}
