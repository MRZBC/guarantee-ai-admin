package com.guarantee.ai.knowledge;

import java.util.Locale;
import java.util.Optional;

/**
 * 知识域（REQ-RAG-01 / §3 术语）。
 *
 * <p>条目归属分类：订单与统计口径、系统管理域、领域概念、制度（本期留空通道）。
 * 域同时决定真源目录名（{@code knowledge/<domain>/}）与编号前缀
 * （{@code KB-<DOMAIN>-NNNN}），导入器会校验二者一致——否则"编号说是系统域、
 * 内容写的是订单口径"这种错位会一路漂到回答里。</p>
 */
public enum KnowledgeDomain {

    /** 订单与统计口径。 */
    ORDER,
    /** 系统管理域（机构/部门/用户/角色/权限/审计/逻辑删除）。 */
    SYSTEM,
    /** 领域概念（保额、保费、费率等）。 */
    CONCEPT,
    /** 制度（本期无语料，留空通道）。 */
    POLICY;

    /** 宽松解析：大小写与首尾空白都可容错；未知值返回空。 */
    public static Optional<KnowledgeDomain> fromCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (KnowledgeDomain domain : values()) {
            if (domain.name().equals(normalized)) {
                return Optional.of(domain);
            }
        }
        return Optional.empty();
    }

    /** 编号前缀，形如 {@code KB-SYSTEM-}。 */
    public String numberPrefix() {
        return "KB-" + name() + "-";
    }
}
