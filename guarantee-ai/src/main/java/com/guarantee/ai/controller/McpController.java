package com.guarantee.ai.controller;

import com.guarantee.ai.mcp.McpErrorCode;
import com.guarantee.ai.mcp.McpException;
import com.guarantee.ai.mcp.McpPrincipal;
import com.guarantee.ai.mcp.McpRateLimiter;
import com.guarantee.ai.mcp.McpServiceAccountResolver;
import com.guarantee.ai.mcp.McpTokenIssue;
import com.guarantee.ai.mcp.McpTokenService;
import com.guarantee.ai.mcp.McpTokenVerification;
import com.guarantee.ai.mcp.McpTokenView;
import com.guarantee.ai.mcp.McpToolCatalog;
import com.guarantee.ai.mcp.McpToolInvocation;
import com.guarantee.ai.mcp.McpToolInvoker;
import com.guarantee.ai.mcp.McpToolInvoker.McpToolDefinition;
import com.guarantee.ai.service.OperationAuditService;
import com.guarantee.common.api.Result;
import com.guarantee.common.api.ResultCode;
import com.guarantee.common.exception.BizException;
import com.guarantee.common.security.CurrentUser;
import com.guarantee.common.security.Permissions;
import com.guarantee.common.trace.TraceContext;
import com.guarantee.system.vo.UserVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务 MCP 平台侧接口（REQ-MCP-01 ~ 05 / AC-MCP-01 ~ 06）。
 *
 * <h3>为什么是"一个控制器、两套响应口径"</h3>
 * <p>两类消费者完全不同：</p>
 * <ul>
 *   <li><b>MCP 协议面</b>（{@code /api/ai/mcp/**}）：消费者是网关（{@code tools/business-mcp}），
 *       它按**冻结契约**只在**非 2xx** 时读 {@code message} 并翻译成可读错误
 *       （{@code backend.ts}）。若这里也守"HTTP 200 + 业务码"，网关会把
 *       {@code code=401} 当成成功体，最终抛 {@code INVALID_RESPONSE}——
 *       AC-MCP-02 的"撤销后立刻给出可读错误"就落不了地。因此协议面返回
 *       **真实 HTTP 状态**（401/403/429/…）+ **同值业务码**的 {@code Result{code,message}}，
 *       两头都读得懂。</li>
 *   <li><b>页面面</b>（{@code /api/system/mcp-tokens}）：消费者是管理后台，守项目全局约定
 *       "HTTP 200 + 业务码"，由 {@code request.ts} 拦截器统一处理。</li>
 * </ul>
 *
 * <h3>fail-closed 的三个层次</h3>
 * <ol>
 *   <li>开关：{@code guarantee.ai.mcp.enabled=false}（默认）时**本 Bean 不注册**，
 *       接口根本不存在（AC-MCP-06）。注意两处边界：① 本开关是 **yml 级、需重启生效**
 *       （不读第四阶段的 DB 配置项——对外暴露面宁可"改配置要重启"）；
 *       ② 关闭时**协议面与凭据管理面一起消失**（本类同时承载两者），因此操作顺序是
 *       「先开开关并重启 → 再签发凭据 → 最后交给外部 Agent」；</li>
 *   <li>凭据：{@code Authorization: Bearer <MCP-Token>} 缺失/无效/已撤销/过期 →
 *       可读拒绝（401）；撤销即时生效（鉴权每次直查库，无缓存窗口）；</li>
 *   <li>权限：服务账号缺 {@code ai:mcp:read} → 清单为空 + 调用拒绝；
 *       具体工具再按注册裁剪（与页面/助手同一套）。</li>
 * </ol>
 *
 * <h3>审计与限流</h3>
 * <ul>
 *   <li>每次成功调用由 {@code RecordingToolCallback} 链路写 {@code ai_tool_call}
 *       （{@code source=MCP} + {@code trace_id}）与 {@code ai_audit_log}（归属服务账号）；</li>
 *   <li>限流/配额超限额外写一条 {@code ai_operation_audit}（{@code source=MCP},
 *       {@code result=REJECTED}），并给**可读中文**——不是 500。</li>
 * </ul>
 */
@RestController
@ConditionalOnProperty(name = "guarantee.ai.mcp.enabled", havingValue = "true")
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    /** 协议面凭据头前缀（大小写不敏感）。 */
    private static final String BEARER_PREFIX = "Bearer ";

    /** 审计：凭据签发（页面渠道）。 */
    public static final String ACTION_TOKEN_ISSUE = "MCP_TOKEN_ISSUE";
    /** 审计：凭据撤销（页面渠道）。 */
    public static final String ACTION_TOKEN_REVOKE = "MCP_TOKEN_REVOKE";
    /** 审计：MCP 调用被限流/配额拒绝（协议渠道）。 */
    public static final String ACTION_RATE_LIMITED = "MCP_RATE_LIMITED";
    /** 审计目标类型：机器凭据。 */
    public static final String TARGET_TYPE_MCP_TOKEN = "MCP_TOKEN";
    /** 审计来源：协议面（与 WEB / AI 并列）。 */
    public static final String SOURCE_MCP = "MCP";
    /** 审计来源：页面面。 */
    public static final String SOURCE_WEB = "WEB";

    private final McpTokenService tokenService;
    private final McpToolInvoker toolInvoker;
    private final McpServiceAccountResolver accountResolver;
    private final McpRateLimiter rateLimiter;
    private final OperationAuditService auditService;
    private final ObjectMapper objectMapper;

    public McpController(McpTokenService tokenService,
                         McpToolInvoker toolInvoker,
                         McpServiceAccountResolver accountResolver,
                         McpRateLimiter rateLimiter,
                         OperationAuditService auditService,
                         ObjectMapper objectMapper) {
        this.tokenService = tokenService;
        this.toolInvoker = toolInvoker;
        this.accountResolver = accountResolver;
        this.rateLimiter = rateLimiter;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    // ==================================================================
    // MCP 协议面（真实 HTTP 状态 + 同值业务码）
    // ==================================================================

    /**
     * 当前服务账号可见的只读工具清单（网关的 {@code tools/list} 数据源）。
     *
     * <p>清单已按权限裁剪；无 {@code ai:mcp:read} 时返回空数组（不是 403 空清单——
     * 调用面同样会拒绝，两者口径一致）。</p>
     */
    @GetMapping("/api/ai/mcp/tools")
    public ResponseEntity<Result<List<McpToolView>>> tools(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        McpTokenVerification verification = null;
        try {
            verification = authenticate(authorization);
            McpPrincipal principal = accountResolver.resolve(verification, TraceContext.currentTraceId());
            rateLimiter.check(verification.tokenPrefix());
            tokenService.markUsed(verification.tokenId());

            List<McpToolView> views = new ArrayList<>();
            for (McpToolDefinition definition : toolInvoker.listTools(principal)) {
                views.add(new McpToolView(definition.name(), definition.description(),
                        parseSchema(definition.inputSchemaJson())));
            }
            return ResponseEntity.ok(Result.ok(List.copyOf(views)));
        } catch (McpException ex) {
            auditRejection(verification, ex);
            return protocolFailure(ex);
        }
    }

    /**
     * 执行一次只读工具调用（网关的 {@code tools/call} 数据源）。
     *
     * <p>请求体就是工具参数 JSON 对象；返回值里 {@code result} 是工具返回值**原文**
     * （JSON 文本或纯文本），{@code dataSource} / {@code truncated} 与页面/助手同源。</p>
     */
    @PostMapping("/api/ai/mcp/tools/{name}")
    public ResponseEntity<Result<McpCallView>> call(
            @PathVariable String name,
            @RequestBody(required = false) String argumentsJson,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        McpTokenVerification verification = null;
        try {
            verification = authenticate(authorization);
            McpPrincipal principal = accountResolver.resolve(verification, TraceContext.currentTraceId());
            rateLimiter.check(verification.tokenPrefix());
            tokenService.markUsed(verification.tokenId());

            McpToolInvocation invocation = toolInvoker.invoke(name, argumentsJson, principal);
            return ResponseEntity.ok(Result.ok(new McpCallView(
                    invocation.resultJson(), invocation.dataSource(), invocation.truncated())));
        } catch (McpException ex) {
            auditRejection(verification, ex);
            return protocolFailure(ex);
        }
    }

    // ==================================================================
    // 页面面：MCP 凭据管理（HTTP 200 + 业务码）
    // ==================================================================

    /**
     * 凭据列表（**不含明文与哈希**，见 {@link McpTokenView}）。
     *
     * <p>权限用独立的 {@code ai:mcp:manage}（ADMIN 默认）：能看配置 ≠ 能管机器凭据。</p>
     */
    @GetMapping("/api/system/mcp-tokens")
    @PreAuthorize("hasAuthority('" + Permissions.AI_MCP_MANAGE + "')")
    public Result<List<McpTokenView>> listTokens() {
        return Result.ok(tokenService.list());
    }

    /**
     * 签发一把凭据：**明文只在本响应里出现一次**，之后任何接口（含列表）都拿不到。
     *
     * <p>服务端强制要求 Token 至少包含 {@code ai:mcp:read}：否则它在 MCP 入口就是
     * fail-closed 的死凭据（清单为空、调用被拒），签出来只会让使用者排查半天。
     * 注意这只是"管理面的防呆"——校验路径本身不依赖它（库里若已存在这样的凭据，
     * 依然按 fail-closed 处理）。</p>
     */
    @PostMapping("/api/system/mcp-tokens")
    @PreAuthorize("hasAuthority('" + Permissions.AI_MCP_MANAGE + "')")
    @Transactional
    public Result<McpTokenIssue> issueToken(@Valid @RequestBody McpTokenIssueRequest request) {
        CurrentUser.Principal operator = requirePrincipal();
        List<String> permissions = requireEntryPermission(request.permissions());
        UserVO account = accountResolver.requireEnabledAccount(request.serviceAccountId());

        McpTokenIssue issue = tokenService.issue(account.getId(), permissions, request.expiresAt(),
                operator.userId().toString());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("serviceAccountId", account.getId());
        after.put("serviceAccount", account.getUsername());
        after.put("tokenPrefix", issue.tokenPrefix());
        after.put("permissions", String.join(",", permissions));
        after.put("expiresAt", issue.expiresAt() == null ? "长期有效" : issue.expiresAt().toString());
        // 明文**绝不**进审计：审计是长期留存、可被多人查看的
        auditService.record(OperationAuditService.AuditEntry.of(SOURCE_WEB, ACTION_TOKEN_ISSUE,
                        TARGET_TYPE_MCP_TOKEN, issue.id(), issue.tokenPrefix(), null, after, "SUCCESS"),
                operatorContext(operator), null, null, TraceContext.currentTraceId());

        log.info("签发 MCP Token id={} serviceAccount={} prefix={} by={}",
                issue.id(), account.getUsername(), issue.tokenPrefix(), operator.username());
        return Result.ok(issue);
    }

    /** 撤销一把凭据：**立即生效**（鉴权每次直查库，没有"到期前还能用"的窗口）。 */
    @DeleteMapping("/api/system/mcp-tokens/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.AI_MCP_MANAGE + "')")
    @Transactional
    public Result<McpTokenRevokeResult> revokeToken(@PathVariable Long id) {
        CurrentUser.Principal operator = requirePrincipal();
        boolean revoked = tokenService.revoke(id, operator.userId().toString());
        if (!revoked) {
            // 不存在、已撤销、已逻辑删除都走这里：重复撤销不该被记成两次有效撤销
            throw new BizException(ResultCode.NOT_FOUND, "凭据不存在或已经撤销（id=" + id + "）");
        }
        auditService.record(OperationAuditService.AuditEntry.of(SOURCE_WEB, ACTION_TOKEN_REVOKE,
                        TARGET_TYPE_MCP_TOKEN, id, null, Map.of("revoked", false), Map.of("revoked", true),
                        "SUCCESS"),
                operatorContext(operator), null, null, TraceContext.currentTraceId());
        return Result.ok(new McpTokenRevokeResult(id, true,
                "已撤销：下一次调用立即失败（鉴权每次直查库，无缓存窗口）"));
    }

    // ==================================================================
    // 视图
    // ==================================================================

    /** 清单条目：与网关冻结契约的 {@code { name, description, inputSchema }} 逐字段一致。 */
    public record McpToolView(String name, String description, Object inputSchema) {
    }

    /** 调用结果：与网关冻结契约的 {@code { result, dataSource, truncated }} 逐字段一致。 */
    public record McpCallView(String result, String dataSource, boolean truncated) {
    }

    /**
     * 签发请求。
     *
     * @param serviceAccountId 服务账号（{@code sys_user.id}）
     * @param permissions      权限范围（必须含 {@code ai:mcp:read}）
     * @param expiresAt        有效期；null = 长期有效（仍可随时撤销）
     */
    public record McpTokenIssueRequest(@NotNull Long serviceAccountId,
                                       List<String> permissions,
                                       LocalDateTime expiresAt) {
    }

    /** 撤销结果。 */
    public record McpTokenRevokeResult(Long id, boolean revoked, String message) {
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /** 校验并归一协议面凭据；缺失/格式不对都给可读 401。 */
    private McpTokenVerification authenticate(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            throw new McpException(McpErrorCode.TOKEN_INVALID,
                    "缺少 MCP Token：请带上请求头 Authorization: Bearer <MCP-Token>");
        }
        String header = authorization.trim();
        if (!header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            throw new McpException(McpErrorCode.TOKEN_INVALID,
                    "Authorization 头格式不正确：应为 Bearer <MCP-Token>");
        }
        String raw = header.substring(BEARER_PREFIX.length()).trim();
        if (raw.isEmpty()) {
            throw new McpException(McpErrorCode.TOKEN_INVALID, "Authorization 头里没有 MCP Token");
        }
        return tokenService.verify(raw);
    }

    /** 协议面失败：HTTP 状态 + 同值业务码（两头都读得懂）。 */
    private static <T> ResponseEntity<Result<T>> protocolFailure(McpException ex) {
        int status = ex.httpStatus();
        return ResponseEntity.status(status).body(Result.fail(status, ex.getMessage()));
    }

    /**
     * 限流/配额拒绝的审计。
     *
     * <p><b>为什么这里吞异常</b>：这条审计是"拒绝的旁证"，而拒绝本身必须稳定可读。
     * 若审计写失败就把 429 变成 500，外部 Agent 会收到一个无法解释的失败，
     * 反而把一次正常的限流记成平台故障。此处与"写操作审计失败必须回滚业务"不同——
     * 这里没有业务写入需要回滚。</p>
     */
    private void auditRejection(McpTokenVerification verification, McpException ex) {
        if (verification == null) {
            return;
        }
        if (ex.getCode() != McpErrorCode.RATE_LIMITED && ex.getCode() != McpErrorCode.DAILY_QUOTA_EXCEEDED) {
            return;
        }
        try {
            auditService.record(OperationAuditService.AuditEntry.of(SOURCE_MCP, ACTION_RATE_LIMITED,
                            TARGET_TYPE_MCP_TOKEN, verification.tokenId(), verification.tokenPrefix(),
                            null, Map.of("reason", ex.getCode().name(), "message", ex.getMessage()),
                            "REJECTED"),
                    new OperationAuditService.OperatorContext(verification.serviceAccountId(), null, null),
                    null, null, TraceContext.currentTraceId());
        } catch (RuntimeException auditFailure) {
            log.error("MCP 限流审计写入失败（不影响可读拒绝）：token={} err={}",
                    verification.tokenPrefix(), auditFailure.getMessage());
        }
    }

    /** 工具清单里的 inputSchema：解析成 JSON 对象；解析不了就退化为宽松 schema（不猜参数名）。 */
    private Object parseSchema(String inputSchemaJson) {
        if (inputSchemaJson == null || inputSchemaJson.isBlank()) {
            return PERMISSIVE_SCHEMA;
        }
        try {
            Object parsed = objectMapper.readValue(inputSchemaJson, Object.class);
            return parsed instanceof Map ? parsed : PERMISSIVE_SCHEMA;
        } catch (RuntimeException ex) {
            log.warn("工具 inputSchema 不是合法 JSON，退化为宽松 schema：{}", ex.getMessage());
            return PERMISSIVE_SCHEMA;
        }
    }

    /** 后端没给 schema 时的兜底（与网关 {@code PERMISSIVE_INPUT_SCHEMA} 同口径）。 */
    private static final Map<String, Object> PERMISSIVE_SCHEMA =
            Map.of("type", "object", "properties", Map.of(), "additionalProperties", true);

    /** 管理面权限：签发/列表/撤销都用独立的 {@code ai:mcp:manage}。 */
    private static List<String> requireEntryPermission(List<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            throw new BizException(ResultCode.BAD_REQUEST,
                    "权限范围不能为空：一把没有任何权限的机器凭据既不可用也不可审计");
        }
        List<String> normalized = new ArrayList<>();
        for (String permission : permissions) {
            if (permission == null || permission.isBlank()) {
                continue;
            }
            String code = permission.trim();
            if (!normalized.contains(code)) {
                normalized.add(code);
            }
        }
        if (!normalized.contains(McpToolCatalog.PERMISSION_MCP_READ)) {
            throw new BizException(ResultCode.BAD_REQUEST,
                    "权限范围必须包含 " + McpToolCatalog.PERMISSION_MCP_READ
                            + "：否则该 Token 在 MCP 入口 fail-closed（清单为空、调用一律拒绝）");
        }
        return List.copyOf(normalized);
    }

    private static OperationAuditService.OperatorContext operatorContext(CurrentUser.Principal principal) {
        return new OperationAuditService.OperatorContext(principal.userId(), principal.username(),
                principal.realName());
    }

    private static CurrentUser.Principal requirePrincipal() {
        CurrentUser.Principal principal = CurrentUser.get();
        if (principal == null || principal.userId() == null) {
            throw new BizException(ResultCode.UNAUTHORIZED, "未登录或登录已过期");
        }
        return principal;
    }
}
