package com.guarantee.ai.tool;

/**
 * Tool 读写类型。
 *
 * <p>第一阶段只允许 READ。WRITE 类型从架构上提前区分出来，
 * 后续写入类 Tool 必须走：
 * AI Plan -&gt; Permission Check -&gt; Preview -&gt; User Confirmation -&gt; Execute -&gt; Audit。</p>
 */
public enum ToolKind {

    /** 只读工具：仅允许调用业务 Service 的查询方法。 */
    READ,

    /** 写入工具：第一阶段禁止注册。 */
    WRITE
}
