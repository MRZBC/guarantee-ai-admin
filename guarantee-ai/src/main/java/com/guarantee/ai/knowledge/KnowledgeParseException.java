package com.guarantee.ai.knowledge;

/**
 * 知识真源解析失败（front-matter 缺失、字段非法、编号格式错误……）。
 *
 * <p>消息必须**可读且带定位**（文件 + 行号 + 具体原因）：知识真源的维护者多半是业务
 * 人员或非本模块的开发，一句"解析失败"帮不上任何忙。TEST-RAG-01 直接断言这一点。</p>
 *
 * <p>导入器的处理口径：**单个文件失败不阻断启动**，只记 ERROR 并跳过该文件
 * （RK-RAG-08：知识层不可用不能拖垮数字类问答）。而且因为导入器只在
 * "该编号最后一次导入的来源文件已消失"时才置 RETIRED，一个解析失败但**文件仍在**的条目
 * 不会被静默停用——它只是这一轮没更新。</p>
 */
public class KnowledgeParseException extends RuntimeException {

    public KnowledgeParseException(String message) {
        super(message);
    }

    public KnowledgeParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
