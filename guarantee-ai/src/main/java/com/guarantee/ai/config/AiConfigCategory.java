package com.guarantee.ai.config;

/**
 * 配置项分类（REQ-CFG-01 / docs/REQ-第四阶段-AI配置与确认审计.md §6.1）。
 *
 * <p>分类只服务于页面分组与审计可读性，不参与运行期判定。</p>
 *
 * <p>{@link #PROMPT} 是相对 REQ §6.1 列举（{@code MODEL}/{@code SWITCH}/{@code BUDGET}）
 * 的**增量分类**：§6.2 的配置项总表里 {@code prompt.active-version} 没有归属分类，
 * 而它既不是模型参数、也不是能力开关/预算。若把它硬塞进前三类，页面会出现
 * "提示词版本号列在能力开关里"这种误导性分组。该增量已记录在 T4-00 的实施说明中。</p>
 */
public enum AiConfigCategory {

    /** 模型接入与采样参数。 */
    MODEL,

    /** 能力开关（工具组 / 写能力总开关 / 知识层）。 */
    SWITCH,

    /** 预算参数（历史条数 / 轮次 / 调用数 / 超时 / 结果上限）。 */
    BUDGET,

    /** 提示词版本指针（与 {@code ai_prompt_version} 配合）。 */
    PROMPT
}
