package com.guarantee.ai.mcp.mapper;

import com.guarantee.ai.mcp.McpToken;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MCP 凭据 Mapper（{@code ai_mcp_token}）。
 *
 * <p>约定：</p>
 * <ul>
 *   <li>按哈希反查（{@link #selectByHash}）是鉴权的唯一入口，天然支持索引
 *       {@code uk_ai_mcp_token_hash}；</li>
 *   <li>所有读写的 SQL 都带 {@code is_deleted = 0}（与既有受管表 mapper 同款显式写法）；</li>
 *   <li>**没有**任何方法返回明文——明文根本不入库。</li>
 * </ul>
 */
@Mapper
public interface McpTokenMapper {

    int insert(McpToken token);

    /** 按 SHA-256 哈希反查（鉴权入口）。 */
    McpToken selectByHash(@Param("tokenHash") String tokenHash);

    McpToken selectById(@Param("id") Long id);

    /** 列表（含已撤销；**不含**明文与哈希，投影在 Service 层做）。 */
    List<McpToken> selectAll();

    /**
     * 撤销一把凭据。
     *
     * <p>带 {@code revoked_at IS NULL} 条件：重复撤销影响 0 行，让"撤销成功"与
     * "本来就已撤销"可区分（避免审计把重复点击记成两次有效撤销）。</p>
     */
    int revoke(@Param("id") Long id,
               @Param("revokedAt") LocalDateTime revokedAt,
               @Param("revokedBy") String revokedBy);

    /** 服务账号停用/删除时批量撤销其全部有效凭据（REQ-MCP-02 撤销即时生效）。 */
    int revokeByServiceAccount(@Param("serviceAccountId") Long serviceAccountId,
                               @Param("revokedAt") LocalDateTime revokedAt,
                               @Param("revokedBy") String revokedBy);

    /** 更新最后使用时间（不改变其它字段，也不延长有效期）。 */
    int touchLastUsed(@Param("id") Long id, @Param("lastUsedAt") LocalDateTime lastUsedAt);
}
