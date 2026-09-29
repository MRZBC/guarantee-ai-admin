package com.guarantee.ai.mcp;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 校验通过后的调用主体信息（**不含**明文与哈希）。
 *
 * <p>{@code permissions} 就是要写进 {@code ToolContext} 的权限快照：
 * 工具可见性与数据范围都由它驱动，与页面/助手走同一套裁剪（红线 §2.3-1）。</p>
 *
 * @param tokenId          凭据主键（用于更新 last_used_at 与审计）
 * @param serviceAccountId 服务账号 id（写进 ToolContext 的 {@code aiUserId}）
 * @param permissions      权限快照
 * @param tokenPrefix      展示用前缀（审计里用它指代"哪把凭据"，不写哈希）
 * @param expiresAt        有效期（可为空）
 */
public record McpTokenVerification(Long tokenId,
                                   Long serviceAccountId,
                                   List<String> permissions,
                                   String tokenPrefix,
                                   LocalDateTime expiresAt) {
}
