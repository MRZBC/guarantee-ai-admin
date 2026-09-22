package com.guarantee.ai.service;

import com.guarantee.ai.entity.AiOperationProposal;
import com.guarantee.ai.mapper.AiOperationProposalMapper;
import com.guarantee.common.api.ResultCode;
import com.guarantee.common.exception.BizException;
import com.guarantee.common.security.AuditSourceContext;
import com.guarantee.common.security.SensitiveFieldMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 变更提案服务：写工具只产出提案，执行必须走独立的 HTTP 确认接口（SYS-W-00 / 7.1）。
 *
 * <p><b>状态机</b>：</p>
 * <pre>
 *   PENDING ──确认──▶ EXECUTING ──成功──▶ EXECUTED
 *      │                  └────失败────▶ FAILED
 *      ├──拒绝──────────────────────────▶ REJECTED
 *      ├──超时（15 分钟）───────────────▶ EXPIRED
 *      └──确认时权限已变更───────────────▶ INVALIDATED
 * </pre>
 *
 * <p><b>三个必须由本类保证的硬约束</b>：</p>
 * <ol>
 *   <li>写工具不落库：{@link #create} 只写提案与审计，业务表一行不动（AC-14）；</li>
 *   <li>不重复执行：{@link #confirm} 用条件更新抢占 {@code PENDING→EXECUTING}，
 *       {@code affectedRows != 1} 一律拒绝（SYS-C-03 / AC-18）；</li>
 *   <li>不越权执行：确认时按**当前 token 的权限**复核
 *       {@code required_perms}，不满足则置 {@code INVALIDATED}（SYS-C-04 / AC-20）。</li>
 * </ol>
 */
@Service
public class ProposalService {

    private static final Logger log = LoggerFactory.getLogger(ProposalService.class);

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 未确认提案有效期（SYS-C-09）。 */
    public static final int VALID_MINUTES = 15;

    /** 列待确认提案的默认条数。 */
    private static final int LIST_LIMIT = 50;

    /** 单次过期清理批量上限，避免一次锁太多行。 */
    private static final int EXPIRE_BATCH = 200;

    private final AiOperationProposalMapper proposalMapper;
    private final ProposalSecretStore secretStore;
    private final OperationAuditService auditService;
    private final AiConversationService conversationService;
    private final ProposalEventPublisher eventPublisher;
    private final ProposalFailureRecorder failedRecorder;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<List<ProposalExecutor>> executorsProvider;

    public ProposalService(AiOperationProposalMapper proposalMapper,
                           ProposalSecretStore secretStore,
                           OperationAuditService auditService,
                           AiConversationService conversationService,
                           ProposalEventPublisher eventPublisher,
                           ProposalFailureRecorder failedRecorder,
                           ObjectMapper objectMapper,
                           ObjectProvider<List<ProposalExecutor>> executorsProvider) {
        this.proposalMapper = proposalMapper;
        this.secretStore = secretStore;
        this.auditService = auditService;
        this.conversationService = conversationService;
        this.eventPublisher = eventPublisher;
        this.failedRecorder = failedRecorder;
        this.objectMapper = objectMapper;
        this.executorsProvider = executorsProvider;
    }

    /** 提案生成的入参（由写工具构造）。 */
    public record ProposalDraft(
            Long conversationId,
            Long userId,
            String username,
            String realName,
            Long orgId,
            String toolName,
            String action,
            String targetType,
            Long targetId,
            String targetName,
            ProposalRequest request,
            ProposalPreview preview,
            Set<String> requiredPerms,
            String userText,
            /** 敏感字段值（不落提案表，只加密暂存）。 */
            Map<String, Object> secretValues,
            String traceId) {
    }

    // ==================================================================
    // 生成提案
    // ==================================================================

    /**
     * 生成提案（**不落库任何业务变更**，SYS-W-08 / AC-14）。
     *
     * <p>同一会话内已存在同目标同动作的 PENDING 提案时提示用户，
     * 避免重复点击导致重复执行（SYS-W-12）。</p>
     */
    @Transactional
    public ProposalPayload create(ProposalDraft draft) {
        List<AiOperationProposal> existing = proposalMapper.selectPendingSameTarget(
                draft.conversationId(), draft.targetType(), draft.targetId(), draft.action());
        if (!existing.isEmpty()) {
            AiOperationProposal pending = existing.get(0);
            log.info("会话 {} 已存在同目标同动作的待确认提案 {}，本次不重复生成",
                    draft.conversationId(), pending.getProposalNo());
            // 仍然返回既有提案，让前端复用同一张确认卡（而不是生成第二张）
            return toPayload(pending, draft.preview(), draft.userText());
        }

        AiOperationProposal entity = new AiOperationProposal();
        entity.setProposalNo(generateNo());
        entity.setConversationId(draft.conversationId());
        entity.setUserId(draft.userId());
        entity.setToolName(draft.toolName());
        entity.setAction(draft.action());
        entity.setTargetType(draft.targetType());
        entity.setTargetId(draft.targetId());
        entity.setTargetName(truncate(draft.targetName(), 128));
        // SYS-A-09：敏感字段先替换为占位符，明文另走加密暂存
        entity.setRequestPayload(toJson(maskRequest(draft.request())));
        entity.setPreviewPayload(toJson(draft.preview()));
        entity.setRequiredPerms(String.join(",", draft.requiredPerms() == null ? Set.of() : draft.requiredPerms()));
        entity.setStatus("PENDING");
        entity.setExpiresAt(LocalDateTime.now().plusMinutes(VALID_MINUTES));
        entity.setTraceId(draft.traceId());
        // 版本指纹在写工具预检时已算好，随 request 的 extra 传入
        Object fingerprint = draft.request().extra() == null
                ? null : draft.request().extra().get("fingerprint");
        if (fingerprint != null) {
            entity.setTargetFingerprint(String.valueOf(fingerprint));
        }
        proposalMapper.insert(entity);

        // 敏感参数加密暂存（只在确认执行的那一刻解密）
        if (draft.secretValues() != null && !draft.secretValues().isEmpty()) {
            secretStore.save(entity.getId(), toJson(draft.secretValues()), entity.getExpiresAt());
        }

        // 审计：提案创建（SYS-A-01 要求生命周期每一步可追溯）
        auditService.record(
                OperationAuditService.AuditEntry.of("AI", "PROPOSAL_CREATED", draft.targetType(),
                        draft.targetId(), draft.targetName(), null, null, "SUCCESS"),
                operator(draft.userId(), draft.username(), draft.realName(), draft.orgId()),
                entity.getId(), draft.conversationId(), draft.traceId());
        conversationService.audit(draft.conversationId(), draft.userId(), "PROPOSAL_CREATED",
                "proposalNo=" + entity.getProposalNo() + " tool=" + draft.toolName()
                        + " action=" + draft.action() + " target=" + draft.targetType()
                        + ":" + draft.targetId(), draft.traceId());

        log.info("提案已生成 id={} no={} tool={} action={} target={}:{} 有效期至 {}",
                entity.getId(), entity.getProposalNo(), draft.toolName(), draft.action(),
                draft.targetType(), draft.targetId(), entity.getExpiresAt());

        ProposalPayload payload = toPayload(entity, draft.preview(), draft.userText());
        // SSE 推送：若有活跃流，前端立即渲染确认卡
        eventPublisher.publishProposal(draft.conversationId(), com.guarantee.ai.vo.ChatStreamEvents.Proposal.from(payload));
        return payload;
    }

    // ==================================================================
    // 查询
    // ==================================================================

    @Transactional(readOnly = true)
    public List<ProposalPayload> listMine(Long userId, String status, int limit) {
        int effective = limit <= 0 ? LIST_LIMIT : Math.min(limit, LIST_LIMIT);
        return proposalMapper.selectByUserId(userId, status, effective).stream()
                .map(entity -> toPayload(entity, readPreview(entity), null))
                .toList();
    }

    /** 提案详情（SYS-C-14：刷新页面后恢复确认卡）。 */
    @Transactional(readOnly = true)
    public ProposalPayload detail(Long proposalId, Long userId) {
        AiOperationProposal entity = requireOwner(proposalId, userId);
        return toPayload(entity, readPreview(entity), null);
    }

    // ==================================================================
    // 确认执行
    // ==================================================================

    /**
     * 确认并执行（SYS-C-01 ~ SYS-C-08）。
     *
     * <p><b>事务只能用 REQUIRES_NEW 开启，不能只加 noRollbackFor</b>：
     * {@code guarantee-system} 的写方法自身是 {@code @Transactional} 的，
     * 它们抛出的 {@code BizException}（"已处于目标状态""状态已被他人修改"）会先把
     * **当前事务**标记为 rollback-only；即使本方法声明 {@code noRollbackFor}，
     * 内层这个标记依然存在，最终提交时会抛
     * {@code UnexpectedRollbackException}，把已写入的业务变更与审计一起回滚掉
     * （表现为"接口报错，但实际上已经改成功了"或相反，最难排查的一类问题）。</p>
     *
     * <p>因此这里显式开启一个**新事务**，并在业务失败时主动回滚它——
     * 既保证"失败不留半成品"，也让 {@code FAILED} 审计不会因为业务回滚而丢失。
     * 业务成功时正常提交（SYS-C-06：业务执行 + 操作审计 + 提案状态回写同事务）。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = BizException.class)
    public ProposalPayload confirm(Long proposalId, ProposalExecutionContext context) {
        AiOperationProposal proposal = proposalMapper.selectById(proposalId);
        if (proposal == null) {
            throw BizException.notFound("提案不存在: " + proposalId);
        }
        // SYS-C-01：防越权确认
        if (!proposal.getUserId().equals(context.userId())) {
            log.warn("用户 {} 尝试确认他人（{}）的提案 {}", context.userId(), proposal.getUserId(), proposalId);
            throw new BizException(ResultCode.FORBIDDEN, "无权确认他人的提案");
        }
        // SYS-C-02：状态必须为 PENDING，且绝不重复执行
        if (!"PENDING".equals(proposal.getStatus())) {
            throw new BizException(statusRejectionMessage(proposal));
        }
        if (proposal.getExpiresAt() != null && proposal.getExpiresAt().isBefore(LocalDateTime.now())) {
            markExpired(proposal);
            throw new BizException("提案已过期（有效期 " + VALID_MINUTES + " 分钟），请重新发起");
        }
        // SYS-C-04：按当前 token 的权限复核
        if (!coversRequired(proposal, context.permissions())) {
            invalidate(proposal, "权限已变更，请重新发起");
            throw new BizException(ResultCode.FORBIDDEN, "权限已变更，请重新发起");
        }

        // SYS-C-03：条件更新抢占，affectedRows=1 才继续
        int claimed = proposalMapper.claimForExecution(proposalId, LocalDateTime.now());
        if (claimed != 1) {
            throw new BizException("该提案正在执行或已被处理，请勿重复确认");
        }
        // 抢占成功后重新读取，避免使用旧状态
        proposal = proposalMapper.selectById(proposalId);

        ProposalRequest request = readRequest(proposal);
        // 合并敏感参数（只在执行这一刻解密）
        request = mergeSecrets(proposal, request);

        ProposalExecutor executor = findExecutor(proposal.getTargetType());
        ProposalExecutionResult result;
        boolean businessFailure = false;
        try {
            // 标记为"助手发起"：业务写 Service 会据此**跳过**自己写 source=WEB 的审计，
            // 由本方法统一写 source=AI 的审计。若不标记，同一次变更会产生两条审计，
            // 且助手渠道的变更会被错记成页面直连（AC-22 的来源区分失效）。
            AuditSourceContext.markAiDriven();
            result = executor.execute(proposal, request, context);
        } catch (RuntimeException ex) {
            log.error("提案执行失败 id={} target={}", proposalId, proposal.getTargetType(), ex);
            result = ProposalExecutionResult.failed(
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            // 业务失败必须留下"无半成品"的语义：把本事务标记为回滚，
            // 业务写入与"成功审计"都不落库；随后由独立事务记录 FAILED 审计与状态
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            businessFailure = true;
        } finally {
            AuditSourceContext.clear();
        }

        if (businessFailure) {
            // 独立事务：本事务即将回滚，失败痕迹必须另开事务才能留下。
            // 注意此处不改写提案行——本事务仍持有该行锁，独立事务去写会互等超时。
            Long failureAuditId = failedRecorder.recordFailure(proposal, context, result);
            String failureMessage = result.errorMessage() == null ? "执行失败" : result.errorMessage();
            proposalMapper.updateResult(proposalId, "FAILED",
                    truncate(failureMessage, 500), null, LocalDateTime.now(), failureAuditId);
            secretStore.purge(proposalId);
            appendResultMessage(proposal.getConversationId(),
                    "[变更结果] 执行失败：" + failureMessage
                            + (failureAuditId == null ? "" : "（审计编号 " + failureAuditId + "）"));
            return toPayload(proposalMapper.selectById(proposalId), readPreview(proposal), null);
        }

        LocalDateTime now = LocalDateTime.now();
        Long auditId = writeExecutionAudit(proposal, request, context, result, now);
        String finalStatus = result.success() ? (result.partial() ? "EXECUTED" : "EXECUTED") : "FAILED";
        String message = result.success() ? result.message() : result.errorMessage();
        proposalMapper.updateResult(proposalId, finalStatus, truncate(message, 500), null, now, auditId);
        secretStore.purge(proposalId);

        conversationService.audit(proposal.getConversationId(), context.userId(),
                result.success() ? "OPERATION_EXECUTED" : "OPERATION_FAILED",
                "proposalNo=" + proposal.getProposalNo() + " result=" + message,
                proposal.getTraceId());

        // SYS-C-08：推回会话；流已关闭时落库，保证下次进入会话可见
        boolean pushed = eventPublisher.publishResult(proposal.getConversationId(), proposalId,
                finalStatus, message, auditId, now);
        if (!pushed) {
            appendResultMessage(proposal.getConversationId(), "[变更结果] " + statusName(finalStatus) + "："
                    + (message == null ? "" : message) + "（审计编号 " + auditId + "）");
        }

        AiOperationProposal updated = proposalMapper.selectById(proposalId);
        log.info("提案执行完成 id={} no={} status={} auditId={}",
                proposalId, proposal.getProposalNo(), finalStatus, auditId);
        return toPayload(updated, readPreview(updated), null);
    }

    // ==================================================================
    // 拒绝
    // ==================================================================

    /** 拒绝提案；**不允许无痕拒绝**（SYS-C-10 / AC-16）。 */
    @Transactional
    public ProposalPayload reject(Long proposalId, Long userId, String reason) {
        AiOperationProposal proposal = requireOwner(proposalId, userId);
        if (!"PENDING".equals(proposal.getStatus())) {
            throw new BizException(statusRejectionMessage(proposal));
        }
        LocalDateTime now = LocalDateTime.now();
        Long auditId = auditService.record(
                new OperationAuditService.AuditEntry("AI", proposal.getAction(), proposal.getTargetType(),
                        proposal.getTargetId(), proposal.getTargetName(), null, null,
                        "REJECTED", reason, Set.of()),
                operator(userId, null, null, null), proposalId, proposal.getConversationId(),
                proposal.getTraceId());
        proposalMapper.updateResult(proposalId, "REJECTED",
                reason == null || reason.isBlank() ? "用户拒绝了该变更" : "用户拒绝：" + reason,
                truncate(reason, 255), now, auditId);
        secretStore.purge(proposalId);
        conversationService.audit(proposal.getConversationId(), userId, "PROPOSAL_REJECTED",
                "proposalNo=" + proposal.getProposalNo() + " reason=" + reason, proposal.getTraceId());

        boolean pushed = eventPublisher.publishResult(proposal.getConversationId(), proposalId,
                "REJECTED", reason == null || reason.isBlank() ? "已拒绝，系统未做任何变更" : "已拒绝：" + reason,
                auditId, now);
        if (!pushed) {
            appendResultMessage(proposal.getConversationId(),
                    "[变更结果] 已拒绝，系统未做任何变更。（审计编号 " + auditId + "）");
        }
        return toPayload(proposalMapper.selectById(proposalId), readPreview(proposal), null);
    }

    /**
     * 把提案结果落成一条会话消息（SYS-C-08 的"流已关闭"分支）。
     *
     * <p>{@code conversationId} 可能为 null（提案不是从对话产生的场景）。
     * {@code ai_message.conversation_id} 是 NOT NULL，因此必须显式跳过，
     * 否则会把"结果没推送到界面"升级成"整个确认/拒绝事务回滚"——
     * 用户已经点了拒绝，却因为消息落库失败而看到报错。</p>
     */
    private void appendResultMessage(Long conversationId, String content) {
        if (conversationId == null) {
            log.debug("提案没有关联会话，跳过结果消息落库：{}", content);
            return;
        }
        conversationService.appendMessage(conversationId, "ASSISTANT", content);
    }

    // ==================================================================
    // 过期清理（T-11 / SYS-C-09 / SYS-C-10）
    // ==================================================================

    /**
     * 把到期的 PENDING 提案置为 EXPIRED，并为**每一条**写审计。
     *
     * @return 本次处理条数
     */
    @Transactional
    public int expireOverdue() {
        LocalDateTime now = LocalDateTime.now();
        List<AiOperationProposal> overdue = proposalMapper.selectOverdue(now, EXPIRE_BATCH);
        if (overdue.isEmpty()) {
            return 0;
        }
        for (AiOperationProposal proposal : overdue) {
            markExpired(proposal);
        }
        log.info("过期提案清理完成：{} 条", overdue.size());
        return overdue.size();
    }

    /** 清理过期的敏感参数密文。 */
    @Transactional
    public int purgeExpiredSecrets() {
        return secretStore.purgeExpired(LocalDateTime.now());
    }

    private void markExpired(AiOperationProposal proposal) {
        int claimed = proposalMapper.updateResult(proposal.getId(), "EXPIRED",
                "提案已过期（有效期 " + VALID_MINUTES + " 分钟）", null, LocalDateTime.now(), null);
        if (claimed != 1) {
            return;
        }
        Long auditId = auditService.record(
                new OperationAuditService.AuditEntry("AI", proposal.getAction(), proposal.getTargetType(),
                        proposal.getTargetId(), proposal.getTargetName(), null, null, "EXPIRED",
                        "提案超时未确认（" + VALID_MINUTES + " 分钟）", Set.of()),
                operator(proposal.getUserId(), null, null, null), proposal.getId(),
                proposal.getConversationId(), proposal.getTraceId());
        proposalMapper.updateResult(proposal.getId(), "EXPIRED",
                "提案已过期（有效期 " + VALID_MINUTES + " 分钟）", null, LocalDateTime.now(), auditId);
        secretStore.purge(proposal.getId());
        conversationService.audit(proposal.getConversationId(), proposal.getUserId(), "PROPOSAL_EXPIRED",
                "proposalNo=" + proposal.getProposalNo(), proposal.getTraceId());
        boolean pushed = eventPublisher.publishResult(proposal.getConversationId(), proposal.getId(),
                "EXPIRED", "提案已过期，未执行任何变更", auditId, LocalDateTime.now());
        if (!pushed) {
            appendResultMessage(proposal.getConversationId(),
                    "[变更结果] 提案已过期，未执行任何变更。（审计编号 " + auditId + "）");
        }
    }

    private void invalidate(AiOperationProposal proposal, String reason) {
        proposalMapper.updateResult(proposal.getId(), "INVALIDATED", reason, null,
                LocalDateTime.now(), null);
        secretStore.purge(proposal.getId());
        conversationService.audit(proposal.getConversationId(), proposal.getUserId(), "PROPOSAL_CONFIRMED",
                "proposalNo=" + proposal.getProposalNo() + " 校验未通过：" + reason, proposal.getTraceId());
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private AiOperationProposal requireOwner(Long proposalId, Long userId) {
        AiOperationProposal proposal = proposalMapper.selectById(proposalId);
        if (proposal == null) {
            throw BizException.notFound("提案不存在: " + proposalId);
        }
        if (!proposal.getUserId().equals(userId)) {
            throw new BizException(ResultCode.FORBIDDEN, "无权访问他人的提案");
        }
        return proposal;
    }

    private static String statusRejectionMessage(AiOperationProposal proposal) {
        return switch (proposal.getStatus()) {
            case "EXECUTED" -> "该提案已执行，不可重复执行";
            case "EXECUTING" -> "该提案正在执行中，请勿重复提交";
            case "REJECTED" -> "该提案已被拒绝";
            case "EXPIRED" -> "该提案已过期（有效期 " + VALID_MINUTES + " 分钟），请重新发起";
            case "INVALIDATED" -> "该提案已失效（权限已变更），请重新发起";
            case "FAILED" -> "该提案执行失败，不可重复执行，请重新发起";
            default -> "提案状态为 " + proposal.getStatus() + "，无法执行";
        };
    }

    private boolean coversRequired(AiOperationProposal proposal, List<String> currentPermissions) {
        String required = proposal.getRequiredPerms();
        if (required == null || required.isBlank()) {
            return true;
        }
        Set<String> granted = new LinkedHashSet<>(currentPermissions == null ? List.of() : currentPermissions);
        for (String code : required.split(",")) {
            String trimmed = code.trim();
            if (!trimmed.isEmpty() && !granted.contains(trimmed)) {
                log.warn("提案 {} 确认时权限不足：缺少 {}", proposal.getProposalNo(), trimmed);
                return false;
            }
        }
        return true;
    }

    private ProposalExecutor findExecutor(String targetType) {
        List<ProposalExecutor> executors = executorsProvider.getIfAvailable(List::of);
        for (ProposalExecutor executor : executors) {
            if (executor.targetType().equals(targetType)) {
                return executor;
            }
        }
        throw new BizException("不支持的目标类型，无法执行: " + targetType);
    }

    private Long writeExecutionAudit(AiOperationProposal proposal, ProposalRequest request,
                                     ProposalExecutionContext context, ProposalExecutionResult result,
                                     LocalDateTime now) {
        Set<String> changed = new LinkedHashSet<>();
        if (result.before() != null && result.after() != null) {
            for (String key : result.after().keySet()) {
                Object before = result.before().get(key);
                Object after = result.after().get(key);
                if (!java.util.Objects.equals(before, after)) {
                    changed.add(key);
                }
            }
        }
        OperationAuditService.AuditEntry entry = new OperationAuditService.AuditEntry(
                "AI", proposal.getAction(), proposal.getTargetType(), proposal.getTargetId(),
                proposal.getTargetName(), result.before(), result.after(),
                result.success() ? "SUCCESS" : "FAILED", result.errorMessage(), changed);
        return auditService.record(entry,
                operator(context.userId(), context.username(), context.realName(), context.orgId()),
                proposal.getId(), proposal.getConversationId(), proposal.getTraceId());
    }

    private static OperationAuditService.OperatorContext operator(Long userId, String username,
                                                                  String realName, Long orgId) {
        return new OperationAuditService.OperatorContext(userId, username, realName, orgId);
    }

    /** 提案请求落库前把敏感值替换为占位符（SYS-A-09）。 */
    private static ProposalRequest maskRequest(ProposalRequest request) {
        return new ProposalRequest(request.id(), request.targetName(), request.userText(),
                request.realName(),
                request.phone() == null ? null : SensitiveFieldMasker.CHANGED_PLACEHOLDER,
                request.email() == null ? null : SensitiveFieldMasker.CHANGED_PLACEHOLDER,
                request.deptId(), request.status(), request.roleCodes(),
                request.orgCode(), request.orgName(), request.regionCode(), request.regionName(),
                request.orgLevel(), request.parentId(), request.sortNo(),
                request.deptCode(), request.deptName(),
                request.roleCode(), request.permCodes(),
                request.typeCode(), request.typeName(), request.category(),
                request.baseRate(), request.minAmount(), request.maxAmount(), request.description(),
                request.extra());
    }

    /** 执行前把加密暂存的敏感值合并回请求。 */
    private ProposalRequest mergeSecrets(AiOperationProposal proposal, ProposalRequest request) {
        String plain = secretStore.load(proposal.getId());
        if (plain == null || plain.isBlank()) {
            return request;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> secrets = objectMapper.readValue(plain, Map.class);
            return new ProposalRequest(request.id(), request.targetName(), request.userText(),
                    request.realName(),
                    secrets.containsKey("phone") ? str(secrets.get("phone")) : request.phone(),
                    secrets.containsKey("email") ? str(secrets.get("email")) : request.email(),
                    request.deptId(), request.status(), request.roleCodes(),
                    request.orgCode(), request.orgName(), request.regionCode(), request.regionName(),
                    request.orgLevel(), request.parentId(), request.sortNo(),
                    request.deptCode(), request.deptName(),
                    request.roleCode(), request.permCodes(),
                    request.typeCode(), request.typeName(), request.category(),
                    request.baseRate(), request.minAmount(), request.maxAmount(), request.description(),
                    request.extra());
        } catch (JacksonException ex) {
            log.error("合并提案敏感参数失败 proposalId={}，敏感字段将按未提供处理", proposal.getId(), ex);
            return request;
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private ProposalPayload toPayload(AiOperationProposal entity, ProposalPreview preview, String userText) {
        ProposalRequest request = readRequestQuietly(entity);
        String action = entity.getAction();
        return new ProposalPayload(
                entity.getId(), entity.getProposalNo(), entity.getConversationId(),
                entity.getToolName(),
                action, actionName(action),
                entity.getTargetType(), targetTypeName(entity.getTargetType()),
                entity.getTargetId(), entity.getTargetName(),
                preview == null ? entity.getStatus() : preview.summary(),
                preview == null ? List.of() : toPayloadChanges(preview.changes()),
                preview == null ? List.of() : preview.impact(),
                preview == null ? List.of() : preview.warnings(),
                preview != null && preview.dangerous(),
                userText != null ? userText : (request == null ? null : request.userText()),
                entity.getExpiresAt(),
                entity.getStatus());
    }

    /**
     * 预览的内部表示转为对外的卡片载荷。
     *
     * <p>两层结构刻意分开：{@code ProposalPreview} 是可持久化的领域对象，
     * {@code ProposalPayload} 是面向前端/模型的展示对象。直接复用同一个 record
     * 会让"落库结构"与"接口契约"绑死，任一侧调整都会破坏另一侧。</p>
     */
    private static List<ProposalPayload.ChangeItem> toPayloadChanges(List<ProposalPreview.ChangeItem> changes) {
        if (changes == null || changes.isEmpty()) {
            return List.of();
        }
        List<ProposalPayload.ChangeItem> result = new ArrayList<>(changes.size());
        for (ProposalPreview.ChangeItem item : changes) {
            result.add(new ProposalPayload.ChangeItem(item.field(), item.label(),
                    item.before(), item.after()));
        }
        return result;
    }

    private ProposalPreview readPreview(AiOperationProposal entity) {
        if (entity.getPreviewPayload() == null || entity.getPreviewPayload().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(entity.getPreviewPayload(), ProposalPreview.class);
        } catch (JacksonException ex) {
            log.warn("解析提案预览载荷失败 proposalId={}", entity.getId(), ex);
            return null;
        }
    }

    private ProposalRequest readRequest(AiOperationProposal entity) {
        ProposalRequest request = readRequestQuietly(entity);
        return request == null ? ProposalRequest.empty() : request;
    }

    private ProposalRequest readRequestQuietly(AiOperationProposal entity) {
        if (entity.getRequestPayload() == null || entity.getRequestPayload().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(entity.getRequestPayload(), ProposalRequest.class);
        } catch (JacksonException ex) {
            log.warn("解析提案请求载荷失败 proposalId={}", entity.getId(), ex);
            return null;
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            log.warn("提案载荷序列化失败：{}", ex.getMessage());
            return null;
        }
    }

    private static String generateNo() {
        return "OP" + LocalDateTime.now().format(NO_FMT)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10_000));
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** 动作中文名（确认卡标题用）。 */
    public static String actionName(String action) {
        if (action == null) {
            return "变更";
        }
        return switch (action) {
            case "CREATE" -> "新增";
            case "UPDATE" -> "修改";
            case "ENABLE" -> "启用";
            case "DISABLE" -> "停用";
            // 逻辑删除（LD-01 / 设计 §7.4）：DELETE 与 DISABLE 是**两个动作**，
            // 确认卡标题必须能一眼区分"停用"与"删除"，否则用户会误以为只是暂停业务。
            case "DELETE" -> "删除";
            case "RESTORE" -> "恢复";
            case "ASSIGN_ROLES" -> "角色分配";
            case "ASSIGN_PERMISSIONS" -> "权限授权";
            default -> action;
        };
    }

    /** 目标类型中文名。 */
    public static String targetTypeName(String targetType) {
        if (targetType == null) {
            return "对象";
        }
        return switch (targetType) {
            case "USER" -> "用户";
            case "ORG" -> "机构";
            case "DEPT" -> "部门";
            case "ROLE" -> "角色";
            case "PERMISSION" -> "权限";
            case "INSURANCE_TYPE" -> "险种";
            default -> targetType;
        };
    }

    private static String statusName(String status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case "EXECUTED" -> "执行成功";
            case "FAILED" -> "执行失败";
            case "REJECTED" -> "已拒绝";
            case "EXPIRED" -> "已过期";
            case "INVALIDATED" -> "已失效";
            default -> status;
        };
    }
}
