package com.guarantee.ai.controller;

import com.guarantee.ai.dto.AiChatRequest;
import com.guarantee.ai.mapper.OperationAuditQuery;
import com.guarantee.ai.service.AiChatService;
import com.guarantee.ai.service.AiConversationService;
import com.guarantee.ai.service.OperationAuditService;
import com.guarantee.ai.service.ProposalExecutionContext;
import com.guarantee.ai.service.ProposalPayload;
import com.guarantee.ai.service.ProposalService;
import com.guarantee.ai.vo.ConversationDetailVO;
import com.guarantee.ai.vo.ConversationVO;
import com.guarantee.ai.vo.ToolCallVO;
import com.guarantee.common.api.Result;
import com.guarantee.common.api.ResultCode;
import com.guarantee.common.exception.BizException;
import com.guarantee.common.security.CurrentUser;
import com.guarantee.common.security.Permissions;
import com.guarantee.common.trace.TraceContext;
import com.guarantee.system.scope.DataScope;
import com.guarantee.system.scope.DataScopeService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 助手接口。
 *
 * <p>通道划分：</p>
 * <ul>
 *   <li>对话与查询：{@code POST /api/ai/chat} 以及会话/工具调用查询；</li>
 *   <li>提案（二期）：确认/拒绝/详情/列表——执行必须走这里，因为只有带 JWT 的
 *       HTTP 请求才有权限与数据范围上下文（7.1）；</li>
 *   <li>审计与自查：{@code GET /api/ai/operation-audits}（仅 ADMIN）与
 *       {@code GET /api/ai/tool-calls/mine}（**仅自己**，SYS-Q-06a 的页面等价接口）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiChatService aiChatService;
    private final AiConversationService aiConversationService;
    private final ProposalService proposalService;
    private final OperationAuditService auditService;
    private final DataScopeService dataScopeService;

    public AiController(AiChatService aiChatService,
                        AiConversationService aiConversationService,
                        ProposalService proposalService,
                        OperationAuditService auditService,
                        DataScopeService dataScopeService) {
        this.aiChatService = aiChatService;
        this.aiConversationService = aiConversationService;
        this.proposalService = proposalService;
        this.auditService = auditService;
        this.dataScopeService = dataScopeService;
    }

    // ==================================================================
    // 对话与查询
    // ==================================================================

    /**
     * 流式对话。返回 {@code text/event-stream}，事件见 {@code ChatStreamEvents}。
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.AI_CHAT + "')")
    public Flux<ServerSentEvent<String>> chat(@Valid @RequestBody AiChatRequest request) {
        return aiChatService.stream(requireUserId(), request);
    }

    @GetMapping("/conversations")
    @PreAuthorize("hasAuthority('" + Permissions.AI_CHAT + "')")
    public Result<List<ConversationVO>> conversations() {
        return Result.ok(aiConversationService.listConversations(requireUserId()));
    }

    @GetMapping("/conversations/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.AI_CHAT + "')")
    public Result<ConversationDetailVO> conversationDetail(@PathVariable Long id) {
        return Result.ok(aiConversationService.detail(id, requireUserId()));
    }

    @GetMapping("/tool-calls/{conversationId}")
    @PreAuthorize("hasAuthority('" + Permissions.AI_CHAT + "')")
    public Result<List<ToolCallVO>> toolCalls(@PathVariable Long conversationId) {
        // 传入当前用户：工具调用记录含入参与结果原文，必须校验会话归属（越权即等于泄漏明文）
        return Result.ok(aiConversationService.listToolCalls(conversationId, requireUserId()));
    }

    // ==================================================================
    // 提案（二期，5.3.2）
    // ==================================================================

    /** 待确认提案列表（用于"待办"角标）。 */
    @GetMapping("/proposals")
    @PreAuthorize("hasAuthority('" + Permissions.AI_CHAT + "')")
    public Result<List<ProposalPayload>> proposals(
            @RequestParam(required = false, defaultValue = "PENDING") String status,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        return Result.ok(proposalService.listMine(requireUserId(), status, limit));
    }

    /** 提案详情（刷新页面后恢复确认卡，SYS-C-14）。 */
    @GetMapping("/proposals/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.AI_CHAT + "')")
    public Result<ProposalPayload> proposalDetail(@PathVariable Long id) {
        return Result.ok(proposalService.detail(id, requireUserId()));
    }

    /**
     * 确认并执行提案（SYS-C-01 ~ SYS-C-08）。
     *
     * <p>必须是 POST；必须校验提案所有者；必须复核"用户**当前** token 的权限码是否
     * 仍覆盖提案所需权限"（SYS-C-04）。</p>
     */
    @PostMapping("/proposals/{id}/confirm")
    @PreAuthorize("hasAuthority('" + Permissions.AI_CHAT + "')")
    public Result<ProposalPayload> confirmProposal(@PathVariable Long id) {
        return Result.ok(proposalService.confirm(id, executionContext()));
    }

    /** 拒绝提案（SYS-C-10 要求拒绝也落审计）。 */
    @PostMapping("/proposals/{id}/reject")
    @PreAuthorize("hasAuthority('" + Permissions.AI_CHAT + "')")
    public Result<ProposalPayload> rejectProposal(@PathVariable Long id,
                                                  @RequestBody(required = false) RejectRequest request) {
        String reason = request == null ? null : request.reason();
        return Result.ok(proposalService.reject(id, requireUserId(), reason));
    }

    /** 拒绝请求体（reason 可选）。 */
    public record RejectRequest(String reason) {
    }

    // ==================================================================
    // 操作审计与自查（6.1）
    // ==================================================================

    /**
     * 操作审计列表（管理页面用，**仅 ADMIN**，D-1a）。
     *
     * <p>时间区间必填且 ≤90 天（SYS-A-17）。</p>
     */
    @GetMapping("/operation-audits")
    @PreAuthorize("hasAuthority('" + Permissions.AUDIT_VIEW + "')")
    public Result<OperationAuditService.AuditPage> operationAudits(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String operatorUsername,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String source,
            @RequestParam(required = false, defaultValue = "50") Integer limit) {
        OperationAuditQuery query = new OperationAuditQuery();
        query.setStartDate(startDate.atStartOfDay());
        query.setEndDate(endDate.atTime(23, 59, 59));
        query.setOperatorUsername(operatorUsername);
        query.setTargetType(targetType);
        query.setAction(action);
        query.setResult(result);
        query.setSource(source);
        query.setLimit(limit);
        CurrentUser.Principal principal = requirePrincipal();
        DataScope scope = dataScopeService.resolve(principal.userId(), principal.orgId(), principal.roles());
        return Result.ok(auditService.query(query, principal.isAdmin(), scope));
    }

    /**
     * 当前用户**自己**的工具调用记录（{@code queryMyToolCalls} 的页面等价接口）。
     *
     * <p>无需 {@code system:audit:view}；范围固定为本人，不接受任何用户维度参数
     * （SYS-Q-06a / AC-30）。</p>
     */
    @GetMapping("/tool-calls/mine")
    @PreAuthorize("hasAuthority('" + Permissions.AI_CHAT + "')")
    public Result<AiConversationService.ToolCallMinePage> myToolCalls(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String toolName,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "20") int limit) {
        LocalDateTime end = endDate == null ? LocalDate.now().atTime(23, 59, 59) : endDate.atTime(23, 59, 59);
        LocalDateTime start = startDate == null ? end.toLocalDate().minusDays(6).atStartOfDay()
                : startDate.atStartOfDay();
        int effectiveLimit = limit <= 0 ? 20 : Math.min(limit, 50);
        return Result.ok(aiConversationService.listMyToolCalls(
                requireUserId(), start, end, toolName, status, effectiveLimit));
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /** 执行上下文：权限来自**当前 token**，这是 SYS-C-04 复核的输入。 */
    private ProposalExecutionContext executionContext() {
        CurrentUser.Principal principal = requirePrincipal();
        DataScope scope = dataScopeService.resolve(principal.userId(), principal.orgId(), principal.roles());
        return new ProposalExecutionContext(principal.userId(), principal.username(), principal.realName(),
                principal.orgId(), principal.roles(), principal.permissions(), scope,
                TraceContext.currentTraceId());
    }

    private static CurrentUser.Principal requirePrincipal() {
        CurrentUser.Principal principal = CurrentUser.get();
        if (principal == null) {
            throw new BizException(ResultCode.UNAUTHORIZED, "未登录或登录已过期");
        }
        return principal;
    }

    private static Long requireUserId() {
        Long userId = CurrentUser.userId();
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED, "未登录或登录已过期");
        }
        return userId;
    }
}
