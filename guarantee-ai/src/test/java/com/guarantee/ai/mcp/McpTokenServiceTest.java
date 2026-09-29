package com.guarantee.ai.mcp;

import com.guarantee.ai.mcp.mapper.McpTokenMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MCP 凭据服务单元测试（不连库，mock mapper）。
 *
 * <p>重点守护四件事：</p>
 * <ol>
 *   <li><b>明文只出现一次</b>：签发返回值里有明文，库里只有哈希 + 前缀，列表视图连哈希都没有；</li>
 *   <li><b>算法正确</b>：SHA-256 十六进制（可反查、可复现），Token 形态 URL-safe 且有足够熵；</li>
 *   <li><b>失效路径可区分</b>：格式非法 / 查不到 / 前缀不符 / 已撤销 / 已过期各有独立错误码
 *       （AC-MCP-02 要求撤销后立刻给出可读错误，而不是笼统 401）；</li>
 *   <li><b>fail-closed</b>：权限列为空时解析出空列表，不做任何"默认放行"。</li>
 * </ol>
 */
class McpTokenServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-30T02:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

    /** 形态合法（43 个 URL-safe 字符）的假明文 Token。 */
    private static final String RAW_TOKEN =
            "mcp_abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";

    private McpTokenMapper mapper;
    private McpTokenService service;

    @BeforeEach
    void setUp() {
        mapper = mock(McpTokenMapper.class);
        service = McpTokenService.withClock(mapper, FIXED_CLOCK);
    }

    // ==================================================================
    // 签发
    // ==================================================================

    @Test
    @DisplayName("签发：明文只返回一次，库里只有 SHA-256 哈希与前缀（前缀 != 明文）")
    void issueStoresOnlyHashAndPrefix() {
        when(mapper.insert(any())).thenAnswer(invocation -> {
            invocation.getArgument(0, McpToken.class).setId(42L);
            return 1;
        });

        McpTokenIssue issue = service.issue(7L, List.of("ai:mcp:read", "system:order:tender:view"),
                NOW.plusDays(30), " 1 ");

        assertThat(issue.plainToken()).startsWith("mcp_").hasSizeGreaterThan(40);
        assertThat(issue.id()).isEqualTo(42L);
        assertThat(issue.tokenPrefix()).isEqualTo(issue.plainToken().substring(0, 10));
        assertThat(issue.tokenPrefix()).isNotEqualTo(issue.plainToken());

        ArgumentCaptor<McpToken> captor = ArgumentCaptor.forClass(McpToken.class);
        verify(mapper).insert(captor.capture());
        McpToken stored = captor.getValue();

        assertThat(stored.getTokenHash())
                .as("库里必须存 SHA-256 十六进制")
                .isEqualTo(McpTokenService.sha256Hex(issue.plainToken()))
                .matches("[0-9a-f]{64}");
        assertThat(stored.getTokenHash())
                .as("哈希绝不能等于明文")
                .isNotEqualTo(issue.plainToken());
        assertThat(stored.getTokenPrefix()).isEqualTo(issue.tokenPrefix());
        assertThat(stored.getServiceAccountId()).isEqualTo(7L);
        assertThat(stored.getPermissions()).isEqualTo("ai:mcp:read,system:order:tender:view");
        assertThat(stored.getCreatedBy()).as("签发人去空白").isEqualTo("1");
        assertThat(stored.getExpiresAt()).isEqualTo(NOW.plusDays(30));
        assertThat(stored.getRevokedAt()).as("新签发的凭据不得是已撤销状态").isNull();
    }

    @Test
    @DisplayName("签发：Token 形态是 URL-safe 且每次不同（256 位随机）")
    void issueGeneratesUrlSafeUniqueTokens() {
        when(mapper.insert(any())).thenReturn(1);

        McpTokenIssue first = service.issue(7L, List.of("ai:mcp:read"), null, null);
        McpTokenIssue second = service.issue(7L, List.of("ai:mcp:read"), null, null);

        assertThat(first.plainToken()).matches("^mcp_[A-Za-z0-9_-]{20,}$");
        assertThat(second.plainToken()).matches("^mcp_[A-Za-z0-9_-]{20,}$");
        assertThat(first.plainToken()).isNotEqualTo(second.plainToken());
    }

    @Test
    @DisplayName("签发：权限范围去空白、去重、保持顺序；重复签发不同哈希")
    void issueNormalizesPermissions() {
        when(mapper.insert(any())).thenReturn(1);

        service.issue(7L, List.of(" ai:mcp:read ", "system:org:view", "ai:mcp:read", "", "  "), null, null);

        ArgumentCaptor<McpToken> captor = ArgumentCaptor.forClass(McpToken.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getPermissions()).isEqualTo("ai:mcp:read,system:org:view");
    }

    @Test
    @DisplayName("签发 fail-closed：缺服务账号 / 权限为空 / 有效期已过 一律拒绝，不写库")
    void issueRejectsInvalidInput() {
        assertMcpError(() -> service.issue(null, List.of("ai:mcp:read"), null, null), McpErrorCode.INVALID_ARGUMENT);
        assertMcpError(() -> service.issue(7L, List.of(), null, null), McpErrorCode.INVALID_ARGUMENT);
        assertMcpError(() -> service.issue(7L, java.util.Arrays.asList("  ", null), null, null),
                McpErrorCode.INVALID_ARGUMENT);
        assertMcpError(() -> service.issue(7L, List.of("ai:mcp:read"), NOW.minusMinutes(1), null),
                McpErrorCode.INVALID_ARGUMENT);
        assertMcpError(() -> service.issue(7L, List.of("ai:mcp:read"), NOW, null),
                McpErrorCode.INVALID_ARGUMENT);

        verifyNoInteractions(mapper);
    }

    // ==================================================================
    // 鉴权
    // ==================================================================

    @Test
    @DisplayName("鉴权成功：按哈希反查 + 前缀核对，返回服务账号与权限快照")
    void verifyHappyPath() {
        when(mapper.selectByHash(anyString())).thenReturn(tokenRow(RAW_TOKEN, NOW.plusDays(1)));

        McpTokenVerification verification = service.verify("  " + RAW_TOKEN + "  ");

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(mapper).selectByHash(hash.capture());
        assertThat(hash.getValue())
                .as("必须用明文的 SHA-256 反查（不能存明文、也不能明文比较）")
                .isEqualTo(McpTokenService.sha256Hex(RAW_TOKEN))
                .hasSize(64);

        assertThat(verification.tokenId()).isEqualTo(1L);
        assertThat(verification.serviceAccountId()).isEqualTo(7L);
        assertThat(verification.permissions()).containsExactly("ai:mcp:read", "system:order:tender:view");
        assertThat(verification.tokenPrefix()).isEqualTo(RAW_TOKEN.substring(0, 10));
    }

    @Test
    @DisplayName("鉴权：格式非法（空/超长/无前缀）直接拒绝，不查库")
    void verifyRejectsMalformedTokenWithoutDbHit() {
        assertMcpError(() -> service.verify(null), McpErrorCode.TOKEN_INVALID);
        assertMcpError(() -> service.verify("   "), McpErrorCode.TOKEN_INVALID);
        assertMcpError(() -> service.verify("abcdef"), McpErrorCode.TOKEN_INVALID);
        assertMcpError(() -> service.verify(RAW_TOKEN.replace("mcp_", "Bearer ")), McpErrorCode.TOKEN_INVALID);
        assertMcpError(() -> service.verify("mcp_" + "a".repeat(500)), McpErrorCode.TOKEN_INVALID);

        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("鉴权：哈希查不到 → TOKEN_INVALID")
    void verifyRejectsUnknownToken() {
        when(mapper.selectByHash(anyString())).thenReturn(null);

        assertMcpError(() -> service.verify(RAW_TOKEN), McpErrorCode.TOKEN_INVALID);
    }

    @Test
    @DisplayName("鉴权：哈希命中但前缀不符 → TOKEN_INVALID（防脏数据被当成有效凭据）")
    void verifyRejectsPrefixMismatch() {
        McpToken row = tokenRow(RAW_TOKEN, NOW.plusDays(1));
        row.setTokenPrefix("mcp_zzzzzz");
        when(mapper.selectByHash(anyString())).thenReturn(row);

        assertMcpError(() -> service.verify(RAW_TOKEN), McpErrorCode.TOKEN_INVALID);
    }

    @Test
    @DisplayName("鉴权：已撤销 → TOKEN_REVOKED（撤销即时生效，无缓存窗口）")
    void verifyRejectsRevokedToken() {
        McpToken row = tokenRow(RAW_TOKEN, NOW.plusDays(1));
        row.setRevokedAt(NOW.minusHours(1));
        row.setRevokedBy("1");
        when(mapper.selectByHash(anyString())).thenReturn(row);

        assertMcpError(() -> service.verify(RAW_TOKEN), McpErrorCode.TOKEN_REVOKED);
    }

    @Test
    @DisplayName("鉴权：已过期（含恰好等于当前时刻）→ TOKEN_EXPIRED")
    void verifyRejectsExpiredToken() {
        when(mapper.selectByHash(anyString())).thenReturn(tokenRow(RAW_TOKEN, NOW));
        assertMcpError(() -> service.verify(RAW_TOKEN), McpErrorCode.TOKEN_EXPIRED);

        when(mapper.selectByHash(anyString())).thenReturn(tokenRow(RAW_TOKEN, NOW.minusDays(1)));
        assertMcpError(() -> service.verify(RAW_TOKEN), McpErrorCode.TOKEN_EXPIRED);
    }

    @Test
    @DisplayName("鉴权：expires_at 为空表示长期有效，权限列为空表示没有权限（fail-closed）")
    void verifyAcceptLongLivedTokenAndEmptyPermissions() {
        McpToken row = tokenRow(RAW_TOKEN, null);
        row.setPermissions(null);
        when(mapper.selectByHash(anyString())).thenReturn(row);

        McpTokenVerification verification = service.verify(RAW_TOKEN);

        assertThat(verification.expiresAt()).isNull();
        assertThat(verification.permissions()).as("权限为空就是空，不得默认放行").isEmpty();
    }

    // ==================================================================
    // 撤销 / 使用时间 / 列表
    // ==================================================================

    @Test
    @DisplayName("撤销：影响 1 行 → true；影响 0 行（不存在或本就已撤销）→ false")
    void revokeDistinguishesAlreadyRevoked() {
        when(mapper.revoke(eq(5L), any(), anyString())).thenReturn(1);
        assertThat(service.revoke(5L, " 1 ")).isTrue();
        verify(mapper).revoke(eq(5L), eq(NOW), eq("1"));

        when(mapper.revoke(eq(6L), any(), any())).thenReturn(0);
        assertThat(service.revoke(6L, null)).isFalse();

        assertMcpError(() -> service.revoke(null, "1"), McpErrorCode.INVALID_ARGUMENT);
    }

    @Test
    @DisplayName("服务账号停用：批量撤销其全部有效凭据")
    void revokeAllForServiceAccount() {
        when(mapper.revokeByServiceAccount(eq(7L), any(), any())).thenReturn(3);

        assertThat(service.revokeAllForServiceAccount(7L, "1")).isEqualTo(3);
        verify(mapper).revokeByServiceAccount(eq(7L), eq(NOW), eq("1"));

        assertMcpError(() -> service.revokeAllForServiceAccount(null, "1"), McpErrorCode.INVALID_ARGUMENT);
    }

    @Test
    @DisplayName("markUsed：更新 last_used_at；id 为空时不触碰 Mapper")
    void markUsedUpdatesTimestamp() {
        when(mapper.touchLastUsed(eq(9L), any())).thenReturn(1);

        assertThat(service.markUsed(9L)).isTrue();
        assertThat(service.markUsed(null)).isFalse();

        // 两次调用只应产生一次写库（id 为空时直接返回，不写库）
        verify(mapper, times(1)).touchLastUsed(eq(9L), eq(NOW));
    }

    @Test
    @DisplayName("列表：不返回明文，也不返回哈希（连等值判据都不给）")
    void listNeverExposesSecretMaterial() {
        McpToken row = tokenRow(RAW_TOKEN, NOW.plusDays(1));
        row.setLastUsedAt(NOW.minusMinutes(5));
        when(mapper.selectAll()).thenReturn(List.of(row));

        List<McpTokenView> views = service.list();

        assertThat(views).hasSize(1);
        assertThat(views.get(0).tokenPrefix()).isEqualTo(RAW_TOKEN.substring(0, 10));
        assertThat(views.get(0).serviceAccountId()).isEqualTo(7L);
        // 视图是 record：逐字段检查"没有哈希/明文"这两个字段，而不是靠人记住
        List<String> viewFields = java.util.Arrays.stream(McpTokenView.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
        assertThat(viewFields)
                .as("列表视图不得包含 tokenHash / plainToken")
                .doesNotContain("tokenHash", "plainToken", "token");
        // 实体本身也不允许有"明文"字段（明文只存在于签发返回值的局部变量里）
        List<String> entityFields = java.util.Arrays.stream(McpToken.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .toList();
        assertThat(entityFields).doesNotContain("plainToken", "token");
        assertThat(String.join(",", viewFields)).doesNotContain("hash");
    }

    @Test
    @DisplayName("列表：空结果返回空列表，不返回 null")
    void listEmpty() {
        when(mapper.selectAll()).thenReturn(null);
        assertThat(service.list()).isEmpty();
    }

    // ==================================================================
    // 算法本身（可复现，防止"悄悄换哈希算法"）
    // ==================================================================

    @Test
    @DisplayName("哈希算法固定为 SHA-256 十六进制（反查的前提是确定性）")
    void hashAlgorithmIsDeterministicSha256() {
        // echo -n "abc" | sha256sum
        assertThat(McpTokenService.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(McpTokenService.sha256Hex(RAW_TOKEN)).hasSize(64).matches("[0-9a-f]+");
        assertThat(McpTokenService.displayPrefix(RAW_TOKEN)).hasSize(10).isNotEqualTo(RAW_TOKEN);
        assertThat(McpTokenService.parsePermissions(" a , b ,, ")).containsExactly("a", "b");
        assertThat(McpTokenService.parsePermissions(null)).isEmpty();
        assertThat(McpTokenService.normalizePermissions(List.of("x", "x"))).isEqualTo("x");
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private static McpToken tokenRow(String rawToken, LocalDateTime expiresAt) {
        McpToken token = new McpToken();
        token.setId(1L);
        token.setServiceAccountId(7L);
        token.setTokenPrefix(McpTokenService.displayPrefix(rawToken));
        token.setTokenHash(McpTokenService.sha256Hex(rawToken));
        token.setPermissions("ai:mcp:read,system:order:tender:view");
        token.setExpiresAt(expiresAt);
        return token;
    }

    private static void assertMcpError(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
                                       McpErrorCode expected) {
        McpException ex = catchThrowableOfType(callable, McpException.class);
        assertThat(ex).as("必须抛 McpException（%s）", expected).isNotNull();
        assertThat(ex.getCode()).isEqualTo(expected);
        assertThat(ex.httpStatus()).isEqualTo(expected.httpStatus());
    }
}
