package com.guarantee.ai.tool;

import com.guarantee.ai.entity.AiOperationProposal;
import com.guarantee.ai.service.ProposalService;
import com.guarantee.common.security.Permissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code queryMyProposals}（SYS-Q-06b）：**当前登录用户 + 当前会话**的待确认提案。
 *
 * <p><b>为什么必须新增这个只读工具</b>（真机证据，已复现两次）：助手会输出
 * 「待确认提案：提案编号 OP2026...…请在确认卡上点击」，而该编号在库里并不存在
 * （拿上一轮真实提案 {@code ...258651} 改尾数编成 {@code ...258712}），
 * 同一轮 {@code ai_tool_call} 里一次工具调用都没有。根因不是模型"不肯查"，
 * 而是**当时的读工具集合里没有任何一个能回答"有没有待确认提案"**：
 * 只有订单/机构/部门/用户/角色/险种/审计/我的工具调用。没有数据源，就只能猜。</p>
 *
 * <p><b>强制范围</b>：数据范围固定为当前用户 + 当前会话，
 * 服务端从 {@code ToolContext} 取 {@code USER_ID} 与 {@code CONVERSATION_ID}，
 * **不接受任何入参**——模型无法用参数把范围放大到别人或别的会话。
 * 这也是它与 {@code queryMyToolCalls} 的分工：那个查"我调过哪些工具"，
 * 这个查"我名下还挂着哪些没确认的提案"。</p>
 *
 * <p><b>与写工具的分工</b>：本工具只读，永远不会生成提案。需要变更时仍然必须
 * 调用 {@code proposeXxx} 写工具生成确认卡（确认只做一次，就在卡片上）。</p>
 *
 * <p><b>权限</b>：仅需 {@code ai:chat} + {@code ai:system:query}
 * （与 {@code queryMyToolCalls} 同码，不新增权限码、不改权限矩阵）。</p>
 */
@Component
public class MyProposalsQueryTool {

    private static final Logger log = LoggerFactory.getLogger(MyProposalsQueryTool.class);

    /** 单次返回上限：待确认提案本来就不会多，50 与页面列表口径一致。 */
    private static final int MAX_LIMIT = 50;

    private final ProposalService proposalService;

    public MyProposalsQueryTool(ProposalService proposalService) {
        this.proposalService = proposalService;
    }

    @Tool(name = "queryMyProposals",
            description = """
                    查询**当前登录用户在当前会话里**的待确认变更提案（状态 PENDING，即还没点确认的那些）。
                    返回每条提案的 proposalId、proposalNo、action、targetType、targetName、status、expiresAt、是否已过期。
                    必须在以下时机调用本工具：
                      1) 用户问"有没有待确认的提案""刚才那个提案还在吗""提案编号是多少"；
                      2) 你准备在正文里提到任何提案编号、或说"已生成/已发起提案""请在确认卡上点击"之前。
                    重要限制：本工具**不需要任何参数**，范围由服务端强制限定为当前用户 + 当前会话，
                    你不能查询其他用户或其他会话的提案。
                    只覆盖**当前会话**：返回 0 条只代表本会话没有待确认提案，
                    此时**绝不允许**在正文里写出任何提案编号，也不允许说"我已生成提案""请在确认卡上点击"——
                    需要变更就直接调用 proposeXxx 写工具生成确认卡。
                    **提案编号只能来自本工具或写工具（proposeXxx）本次的返回值，禁止凭记忆、推测或改写得到。**
                    如果返回的提案 expired=true，说明它已过期：确认会被拒绝，应当说明需要重新发起，不要引导用户去点卡片。""")
    public MyProposalsToolResult queryMyProposals(ToolContext toolContext) {

        // 与 queryMyToolCalls 同码：不依赖 system:audit:view，也不新增权限码
        if (!AiPermissionGuard.allowed(toolContext, Permissions.AI_SYSTEM_QUERY)) {
            return MyProposalsToolResult.denied(
                    AiPermissionGuard.deniedReason(Permissions.AI_SYSTEM_QUERY));
        }
        Long userId = AiPermissionGuard.userId(toolContext);
        if (userId == null) {
            return MyProposalsToolResult.denied("无法确定当前登录用户，请重新登录后再试");
        }
        Long conversationId = AiPermissionGuard.conversationId(toolContext);

        List<AiOperationProposal> pending =
                proposalService.listPendingInConversation(userId, conversationId, MAX_LIMIT);

        LocalDateTime now = LocalDateTime.now();
        List<MyProposalsToolResult.MyProposalItem> items = new ArrayList<>(pending.size());
        for (AiOperationProposal entity : pending) {
            LocalDateTime expiresAt = entity.getExpiresAt();
            items.add(new MyProposalsToolResult.MyProposalItem(
                    entity.getId(),
                    entity.getProposalNo(),
                    entity.getAction(),
                    ProposalService.actionName(entity.getAction()),
                    entity.getTargetType(),
                    ProposalService.targetTypeName(entity.getTargetType()),
                    entity.getTargetId(),
                    entity.getTargetName(),
                    entity.getStatus(),
                    expiresAt == null ? null : expiresAt.toString(),
                    expiresAt != null && expiresAt.isBefore(now),
                    entity.getToolName()));
        }

        Map<String, Object> parts = new LinkedHashMap<>();
        parts.put("userId", userId);
        parts.put("conversationId", conversationId == null ? "未提供（已退化为该用户全部待确认提案）" : conversationId);
        parts.put("status", "PENDING");
        parts.put("limit", MAX_LIMIT);
        parts.put("数据范围", "仅本人 + 本会话");
        String dataSource = "queryMyProposals(" + OrgQueryTool.render(parts) + ")";
        log.info("Tool queryMyProposals 执行完成 userId={} conversationId={} 命中={} 未过期={}",
                userId, conversationId, items.size(),
                items.stream().filter(item -> !item.expired()).count());
        return new MyProposalsToolResult(items.size(), conversationId != null, items,
                ToolResultMeta.ok(dataSource));
    }
}
