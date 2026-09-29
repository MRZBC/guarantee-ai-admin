package com.guarantee.ai.mcp;

import java.time.LocalDateTime;

/**
 * 签发结果——**唯一一次**能看到明文 Token 的地方。
 *
 * <p>调用方（控制器/页面）必须把 {@code plainToken} 立刻交给使用者并只显示一次；
 * 任何"再次查看"的接口都不存在，因为库里只有哈希。</p>
 *
 * @param id          凭据主键（撤销用）
 * @param plainToken  **明文**（示例 {@code mcp_9f3c2a1b...}），仅此一次返回
 * @param tokenPrefix 可安全展示的前缀（列表页显示它）
 * @param expiresAt   有效期（可为空 = 长期有效，但仍可随时撤销）
 */
public record McpTokenIssue(Long id, String plainToken, String tokenPrefix, LocalDateTime expiresAt) {
}
