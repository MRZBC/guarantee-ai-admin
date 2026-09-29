package com.guarantee.ai.config;

/**
 * 配置项的值类型（REQ-CFG-01 / docs/REQ-第四阶段-AI配置与确认审计.md §6.1）。
 *
 * <p>类型决定两件事：写入前如何校验（{@link AiConfigCatalog#validate}）与读取时如何解析
 * （{@link AiConfigSnapshot} 的强类型 getter）。运行期只认 catalog 里声明的类型，
 * 数据库行上的 {@code value_type} 只是一份便于运维直接读表的投影，不参与判定。</p>
 */
public enum AiConfigType {

    /** 自由字符串（可声明最大长度）。 */
    STRING,

    /** 整数（十进制串，落库时归一化为不含前导零/小数的形式）。 */
    INT,

    /** 十进制数（允许小数；落库时去掉多余的尾随零）。 */
    DECIMAL,

    /** 布尔（接受 true/false/1/0，大小写不敏感，落库归一化为 true/false）。 */
    BOOLEAN,

    /** 枚举（必须命中 {@code enum_options} 之一，大小写不敏感，落库归一化为声明里的原样拼写）。 */
    ENUM
}
