package com.guarantee.ai.mcp;

import com.guarantee.ai.mcp.mapper.McpTokenMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * MCP 机器凭据服务（REQ-MCP-02 / AC-MCP-02）。
 *
 * <p><b>三条不可妥协的口径</b>：</p>
 * <ol>
 *   <li><b>明文只在签发时返回一次</b>：库里只有哈希与前缀，没有任何"再看一次明文"的路径；</li>
 *   <li><b>撤销即时生效</b>：鉴权每次直查库（{@code revoked_at IS NULL}），不做本地缓存
 *       —— 缓存会让"已撤销的 Token 在 TTL 内继续可用"，那正是 AC-MCP-02 要禁止的；</li>
 *   <li><b>不用用户名密码</b>：机器凭据与人类账号完全分离，服务账号不参与登录、
 *       也不受"首登强制改密"闸门影响（见 {@code docs/MCP-外部接入.md} §4.1）。</li>
 * </ol>
 *
 * <p>Token 形态：{@code mcp_<Base64URL(32 字节 SecureRandom)>}，
 * 哈希为 {@code SHA-256(明文)} 的小写十六进制。</p>
 */
@Service
public class McpTokenService {

    private static final Logger log = LoggerFactory.getLogger(McpTokenService.class);

    /** 凭据可识别前缀（便于使用者与泄漏扫描工具识别）。 */
    public static final String TOKEN_PREFIX = "mcp_";
    /** 随机部分字节数：256 位熵。 */
    public static final int TOKEN_RANDOM_BYTES = 32;
    /** 展示用前缀长度：{@code mcp_} + 6 个随机字符（不足以反推明文）。 */
    public static final int DISPLAY_PREFIX_LENGTH = TOKEN_PREFIX.length() + 6;
    /** 明文长度上限：挡住"拿一兆字符串来算哈希"的廉价 DoS。 */
    public static final int MAX_RAW_TOKEN_LENGTH = 128;
    /** 权限范围列宽（与 V10 的 VARCHAR(512) 一致）。 */
    public static final int MAX_PERMISSIONS_LENGTH = 512;

    /** 形态校验：随机部分至少 20 个 URL-safe 字符（实际 43 个）。 */
    private static final Pattern TOKEN_SHAPE = Pattern.compile("^mcp_[A-Za-z0-9_-]{20,}$");
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final McpTokenMapper mapper;
    private final SecureRandom random = new SecureRandom();

    /**
     * 鉴权与过期判断用的时钟。
     *
     * <p><b>保持唯一公开构造器</b>（Spring 单构造器无需注解、天然无歧义）。
     * 把 {@code Clock} 做成构造器参数会引入一个容器里并不存在的 bean，
     * 从而把一个启动失败换成另一个（NoSuchBeanDefinition）——观测切片已实测踩过一次。
     * 单测用 {@link #withClock} 注入固定时钟。</p>
     */
    Clock clock = Clock.systemDefaultZone();

    public McpTokenService(McpTokenMapper mapper) {
        this.mapper = mapper;
    }

    /** 测试/嵌入用：指定时钟的实例（不参与 Spring 装配）。 */
    static McpTokenService withClock(McpTokenMapper mapper, Clock clock) {
        McpTokenService service = new McpTokenService(mapper);
        service.clock = clock == null ? Clock.systemDefaultZone() : clock;
        return service;
    }

    // ==================================================================
    // 签发
    // ==================================================================

    /**
     * 签发一把凭据。
     *
     * <p>校验是**fail-closed** 的：服务账号为空、权限为空、有效期已过都直接拒绝，
     * 不"签发一把几乎不能用、也没人能用的 Token"。</p>
     *
     * @param serviceAccountId 服务账号（{@code sys_user.id}）
     * @param permissions      权限范围（至少一条；建议含 {@code ai:mcp:read}）
     * @param expiresAt        有效期，可为空（仍可随时撤销）
     * @param createdBy        签发人（管理员账号或服务账号，用于审计）
     */
    public McpTokenIssue issue(Long serviceAccountId, List<String> permissions,
                               LocalDateTime expiresAt, String createdBy) {
        if (serviceAccountId == null) {
            throw new McpException(McpErrorCode.INVALID_ARGUMENT, "必须指定服务账号（serviceAccountId）");
        }
        String permissionText = normalizePermissions(permissions);
        if (permissionText.isEmpty()) {
            throw new McpException(McpErrorCode.INVALID_ARGUMENT,
                    "权限范围不能为空：一把没有任何权限的机器凭据既不可用也不可审计");
        }
        if (permissionText.length() > MAX_PERMISSIONS_LENGTH) {
            throw new McpException(McpErrorCode.INVALID_ARGUMENT,
                    "权限范围过长（上限 " + MAX_PERMISSIONS_LENGTH + " 字符）");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new McpException(McpErrorCode.INVALID_ARGUMENT, "有效期必须晚于当前时间");
        }

        String plainToken = generateRawToken();
        McpToken token = new McpToken();
        token.setServiceAccountId(serviceAccountId);
        token.setTokenPrefix(displayPrefix(plainToken));
        token.setTokenHash(sha256Hex(plainToken));
        token.setPermissions(permissionText);
        token.setExpiresAt(expiresAt);
        token.setCreatedBy(trimToNull(createdBy));
        mapper.insert(token);

        // 日志里**只写前缀与账号**，永远不写明文与哈希
        log.info("签发 MCP Token id={} serviceAccountId={} prefix={} expiresAt={} by={}",
                token.getId(), serviceAccountId, token.getTokenPrefix(), expiresAt, token.getCreatedBy());
        return new McpTokenIssue(token.getId(), plainToken, token.getTokenPrefix(), expiresAt);
    }

    // ==================================================================
    // 鉴权
    // ==================================================================

    /**
     * 校验明文 Token，返回调用主体（不含明文/哈希）。
     *
     * <p>校验顺序刻意"先形态、再哈希、再前缀、最后失效状态"：</p>
     * <ol>
     *   <li>形态不合法（含超长）→ 直接拒绝，不查库；</li>
     *   <li>哈希反查不到 → {@code TOKEN_INVALID}；</li>
     *   <li>前缀与库中记录不一致 → {@code TOKEN_INVALID}（防止错行/脏数据被当成有效）；</li>
     *   <li>已撤销 → {@code TOKEN_REVOKED}；已过期 → {@code TOKEN_EXPIRED}。</li>
     * </ol>
     */
    public McpTokenVerification verify(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new McpException(McpErrorCode.TOKEN_INVALID, "缺少 MCP Token");
        }
        String raw = rawToken.trim();
        if (raw.length() > MAX_RAW_TOKEN_LENGTH || !TOKEN_SHAPE.matcher(raw).matches()) {
            throw new McpException(McpErrorCode.TOKEN_INVALID, "MCP Token 格式不合法");
        }

        McpToken token = mapper.selectByHash(sha256Hex(raw));
        if (token == null) {
            throw new McpException(McpErrorCode.TOKEN_INVALID, "MCP Token 无效（未签发或已下架）");
        }
        if (!displayPrefix(raw).equals(token.getTokenPrefix())) {
            // 哈希已命中却前缀不符：说明记录被篡改或写入有 bug —— 宁可信其不可用
            throw new McpException(McpErrorCode.TOKEN_INVALID, "MCP Token 前缀校验失败");
        }
        if (token.getRevokedAt() != null) {
            throw new McpException(McpErrorCode.TOKEN_REVOKED,
                    "MCP Token 已被撤销，请重新签发");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (token.getExpiresAt() != null && !token.getExpiresAt().isAfter(now)) {
            throw new McpException(McpErrorCode.TOKEN_EXPIRED, "MCP Token 已过期，请重新签发");
        }
        return new McpTokenVerification(
                token.getId(),
                token.getServiceAccountId(),
                parsePermissions(token.getPermissions()),
                token.getTokenPrefix(),
                token.getExpiresAt());
    }

    // ==================================================================
    // 撤销与管理
    // ==================================================================

    /** 撤销一把凭据；返回 false 表示"不存在或本就已撤销"。 */
    public boolean revoke(Long id, String revokedBy) {
        if (id == null) {
            throw new McpException(McpErrorCode.INVALID_ARGUMENT, "必须指定要撤销的凭据 id");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        int affected = mapper.revoke(id, now, trimToNull(revokedBy));
        if (affected > 0) {
            log.info("撤销 MCP Token id={} by={}", id, revokedBy);
        }
        return affected > 0;
    }

    /** 批量撤销某个服务账号的全部有效凭据（服务账号停用/删除时调用）。 */
    public int revokeAllForServiceAccount(Long serviceAccountId, String revokedBy) {
        if (serviceAccountId == null) {
            throw new McpException(McpErrorCode.INVALID_ARGUMENT, "必须指定服务账号");
        }
        int affected = mapper.revokeByServiceAccount(serviceAccountId, LocalDateTime.now(clock), trimToNull(revokedBy));
        if (affected > 0) {
            log.info("撤销服务账号 {} 的全部 MCP Token，共 {} 把，by={}", serviceAccountId, affected, revokedBy);
        }
        return affected;
    }

    /** 列表（**不含明文与哈希**，见 {@link McpTokenView}）。 */
    public List<McpTokenView> list() {
        List<McpToken> rows = mapper.selectAll();
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<McpTokenView> views = new ArrayList<>(rows.size());
        for (McpToken row : rows) {
            McpTokenView view = McpTokenView.from(row);
            if (view != null) {
                views.add(view);
            }
        }
        return views;
    }

    /** 记录一次成功使用（不延长有效期，也不复活已撤销的凭据）。 */
    public boolean markUsed(Long tokenId) {
        if (tokenId == null) {
            return false;
        }
        return mapper.touchLastUsed(tokenId, LocalDateTime.now(clock)) > 0;
    }

    // ==================================================================
    // 纯函数（包级可见，便于单测直接断言算法）
    // ==================================================================

    String generateRawToken() {
        byte[] bytes = new byte[TOKEN_RANDOM_BYTES];
        random.nextBytes(bytes);
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 展示前缀：{@code mcp_} + 6 个随机字符（永不返回完整 Token）。 */
    static String displayPrefix(String rawToken) {
        if (rawToken == null) {
            return null;
        }
        int end = Math.min(DISPLAY_PREFIX_LENGTH, rawToken.length());
        return rawToken.substring(0, end);
    }

    /** SHA-256 小写十六进制。 */
    static String sha256Hex(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            char[] out = new char[hashed.length * 2];
            for (int i = 0; i < hashed.length; i++) {
                int value = hashed[i] & 0xFF;
                out[i * 2] = HEX[value >>> 4];
                out[i * 2 + 1] = HEX[value & 0x0F];
            }
            return new String(out);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 是 JDK 必备算法，走到这里说明运行环境被破坏
            throw new IllegalStateException("运行环境缺少 SHA-256", ex);
        }
    }

    /** 权限范围归一：去空白、去重、保持顺序，逗号拼接。 */
    static String normalizePermissions(List<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return "";
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String permission : permissions) {
            if (permission == null) {
                continue;
            }
            String trimmed = permission.trim();
            if (!trimmed.isEmpty()) {
                unique.add(trimmed);
            }
        }
        return String.join(",", unique);
    }

    /** 解析库中的权限范围列；空值返回空列表（fail-closed：没有权限就是没有）。 */
    static List<String> parsePermissions(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        List<String> permissions = new ArrayList<>();
        for (String part : stored.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                permissions.add(trimmed);
            }
        }
        return List.copyOf(permissions);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
