package com.guarantee.ai.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 提案敏感参数的一次性加密暂存（{@code ai_operation_secret}）。
 *
 * <p>见 {@code schema.sql} 中该表的说明：这是 D-4（审计/提案不得有明文）与
 * "提案必须可执行"之间唯一自洽的落地方式。</p>
 */
@Data
public class AiOperationSecret {

    private Long id;
    private Long proposalId;
    /** AES-256-GCM 密文（Base64，前 12 字节为 IV）。 */
    private String cipherText;
    private Integer keyVersion;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
}
