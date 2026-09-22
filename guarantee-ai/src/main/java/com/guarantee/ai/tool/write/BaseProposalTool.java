package com.guarantee.ai.tool.write;

import com.guarantee.ai.service.ProposalPreview;
import com.guarantee.ai.service.ProposalRequest;
import com.guarantee.ai.service.ProposalService;
import com.guarantee.ai.tool.AiDataScopeResolver;
import com.guarantee.ai.tool.AiPermissionGuard;
import com.guarantee.ai.tool.AiToolContextKeys;
import com.guarantee.ai.tool.ToolResultMeta;
import com.guarantee.system.scope.DataScope;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 写工具基类（SYS-W-07 ~ SYS-W-12）。
 *
 * <p><b>写工具的硬性约束（SYS-W-08）</b>：本体系里 {@code propose*} 方法**禁止执行任何 DML**。
 * 它们的职责只有四步：① 权限校验 ② 参数校验 ③ 领域校验 ④ 生成差异预览，
 * 然后交给 {@link ProposalService#create} 记录提案。真正的变更由确认接口在
 * Web 线程上调用 domain Service 完成。</p>
 *
 * <p>基类提供三件共用能力：权限判定、目标解析结果封装、提案草稿构造。</p>
 */
public abstract class BaseProposalTool {

    /**
     * 恢复（RESTORE）不在助手支持的动作清单内：设计 §7.4 只要求写工具支持 {@code DELETE}，
     * 恢复由页面的「显示已删除」开关提供。
     *
     * <p><b>为什么必须显式拒绝而不是让它落进 default 分支</b>：各写工具的"停用/启用"分支
     * 用 {@code "ENABLE".equals(action) ? 1 : 0} 兜底，{@code RESTORE} 一旦走到那里会被
     * 当成"停用"，生成一张标题为"恢复"、预览却是"停用"的畸形确认卡。用户看到只会更困惑，
     * 因此统一给出可执行的页面恢复路径。</p>
     */
    protected static final String UNSUPPORTED_RESTORE =
            "本期助手不提供「恢复已删除数据」的提案能力。请在「系统管理」对应页面打开"
                    + "「显示已删除」开关，找到该记录后点击「恢复」。";

    protected final ProposalService proposalService;
    protected final AiDataScopeResolver scopeResolver;

    protected BaseProposalTool(ProposalService proposalService, AiDataScopeResolver scopeResolver) {
        this.proposalService = proposalService;
        this.scopeResolver = scopeResolver;
    }

    /** 写工具的统一返回（SYS-W-07 要求含 proposalId / summary / expiresAt）。 */
    public record WriteToolResult(
            boolean denied,
            String deniedReason,
            Long proposalId,
            String proposalNo,
            String summary,
            java.time.LocalDateTime expiresAt,
            /** 目标歧义或不存在时的候选列表（SYS-W-10）。 */
            List<TargetCandidate> ambiguousTargets,
            String hint,
            ToolResultMeta meta) {

        public static WriteToolResult denied(String reason) {
            return new WriteToolResult(true, reason, null, null, null, null, List.of(), null,
                    ToolResultMeta.denied(reason));
        }

        public static WriteToolResult ambiguous(List<TargetCandidate> candidates, String hint) {
            return new WriteToolResult(false, null, null, null, null, null, candidates, hint,
                    ToolResultMeta.ok(hint));
        }

        public static WriteToolResult ok(com.guarantee.ai.service.ProposalPayload payload) {
            return new WriteToolResult(false, null, payload.proposalId(), payload.proposalNo(),
                    payload.summary(), payload.expiresAt(), List.of(),
                    "提案已生成，但**尚未生效**。请告知用户：在确认卡上点击「确认执行」后才会真正修改数据。",
                    ToolResultMeta.ok("propose(" + payload.toolName() + ", action=" + payload.action()
                            + ", target=" + payload.targetType() + ":" + payload.targetId() + ")"));
        }

        public static WriteToolResult failed(String hint) {
            return new WriteToolResult(false, null, null, null, null, null, List.of(), hint,
                    ToolResultMeta.ok(hint));
        }
    }

    /** 目标候选（用于名称歧义澄清）。 */
    public record TargetCandidate(Long id, String code, String name, String extra) {
    }

    /**
     * 预览构建结果：除预览本身，还顺路带回「目标展示名」。
     *
     * <p><b>为什么名字要跟预览一起返回</b>：{@code buildPreview} 本来就按目标 id 加载了实体
     * （UPDATE / DELETE / 停用启用的原值都取自它）。让实体名从同一个方法里带出来，
     * 既避免了"为了取名字再查一次库"，又让调用方一定能把实体名回填进
     * {@code ai_operation_proposal.target_name}。</p>
     */
    protected record PreviewResult(ProposalPreview preview, String targetName) {
    }

    /**
     * 目标展示名回填：目标 id 已确定、但调用方给的名字为空/空白时，用实体名兜底。
     *
     * <p><b>为什么必须回填</b>：模型常常只传 {@code id}、不传名字，此时原来的
     * {@code targetName} 会一路保持 {@code null} 落库（真机数据确认过：同一目标的
     * ENABLE 有名字、DISABLE 为 NULL），而确认卡用
     * {@code v-if="proposal.targetName"} 渲染目标名，null 时整段不显示——
     * 用户只看到"停用"却看不到停用的是谁。</p>
     *
     * <p>实体名也取不到时（理论上不该发生）保持原样（可能是 {@code null}）：
     * 不抛新异常、不编造名字，卡片对 null 是容错的。</p>
     */
    protected static String resolveTargetName(String provided, String fromEntity) {
        return provided == null || provided.isBlank() ? fromEntity : provided;
    }

    /** 当前调用的数据范围。 */
    protected DataScope scope(ToolContext context) {
        return scopeResolver.resolve(context);
    }

    /** 是否持有全部权限。 */
    protected boolean allowed(ToolContext context, String... required) {
        return AiPermissionGuard.allowed(context, required);
    }

    /** 把提案草稿交给 ProposalService 落库并推送。 */
    protected WriteToolResult submit(ToolContext context, ProposalService.ProposalDraft draft) {
        var payload = proposalService.create(draft);
        return WriteToolResult.ok(payload);
    }

    /** 构造提案草稿。 */
    protected ProposalService.ProposalDraft draft(ToolContext context,
                                                  String toolName,
                                                  String action,
                                                  String targetType,
                                                  Long targetId,
                                                  String targetName,
                                                  ProposalRequest request,
                                                  ProposalPreview preview,
                                                  Set<String> requiredPerms,
                                                  String userText,
                                                  Map<String, Object> secretValues) {
        return new ProposalService.ProposalDraft(
                AiPermissionGuard.conversationId(context),
                AiPermissionGuard.userId(context),
                AiPermissionGuard.username(context),
                AiPermissionGuard.realName(context),
                toolName, action, targetType, targetId, targetName,
                request, preview, requiredPerms, userText, secretValues,
                AiPermissionGuard.traceId(context));
    }

    /** ToolContext 内的原始用户话术（SYS-C-15：卡片上要展示用户原话）。 */
    protected String userText(ToolContext context) {
        Object value = context == null || context.getContext() == null
                ? null : context.getContext().get(AiToolContextKeys.USER_TEXT);
        return value instanceof String text ? text : null;
    }
}
