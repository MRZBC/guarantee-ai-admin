package com.guarantee.ai.tool.write;

import com.guarantee.ai.service.ProposalPreview;
import com.guarantee.ai.service.ProposalRequest;
import com.guarantee.ai.service.ProposalService;
import com.guarantee.ai.tool.AiDataScopeResolver;
import com.guarantee.common.security.Permissions;
import com.guarantee.system.dto.OrgDto;
import com.guarantee.system.entity.SysOrg;
import com.guarantee.system.service.OrgService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code proposeOrgChange}（SYS-W-02）：机构变更提案。
 *
 * <p>本方法**不落库**，只生成提案（SYS-W-08）。</p>
 */
@Component
public class OrgProposalTool extends BaseProposalTool {

    private final OrgService orgService;

    public OrgProposalTool(ProposalService proposalService, AiDataScopeResolver scopeResolver,
                           OrgService orgService) {
        super(proposalService, scopeResolver);
        this.orgService = orgService;
    }

    @Tool(name = "proposeOrgChange",
            description = """
                    提交一个【机构变更提案】。本工具**不会立即修改数据**，只生成一张待确认的变更提案，
                    用户必须在确认卡上点击「确认执行」后才会生效。
                    支持的动作：
                    - CREATE：新增机构（必填 orgCode / orgName / regionCode / orgLevel / parentId）
                    - UPDATE：修改机构（必填 id，可改 orgName / regionCode / parentId / orgLevel / sortNo）
                    - DISABLE / ENABLE：机构停用/启用（必填 id）
                    - DELETE：删除机构（必填 id）。**与停用完全不同**：停用=暂停业务、可随时启用、
                      数据仍在默认列表中；删除=从默认列表移除、需要显式恢复才会重新出现。
                    重要规则：
                    - 机构编码 orgCode 新增后不可修改；机构创建会新增数据范围边界，请务必与用户确认层级与上级机构。
                    - 停用时若存在启用中的下级机构或启用中的用户，系统会拒绝。
                    - 删除比停用**更严格**：机构下存在未删除的下级机构 / 部门 / 用户 / 关联订单时会被拒绝，
                      失败信息会带具体数量。遇此情况请如实转述，并建议"先处理引用的数据，或改用停用"。
                    - 删除属**危险动作**，确认卡上有二次确认；删除**不改变启用/停用状态**，
                      恢复后回到删除前的状态。删除后可恢复（「显示已删除」）。
                    - 用户说"停用/暂停/禁用"时用 DISABLE，**不要**用 DELETE；用户说"删掉/移除"时
                      必须先与用户确认是"删除"还是"停用"，不得自行降级或升格。
                    - 若机构名命中多个目标，返回值会给出 ambiguousTargets 候选，必须先向用户确认，不要猜 id。
                    - 必须把"用户原话 + 解析出的参数"一起展示给用户核对。
                    示例：把「广东省第2保函运营机构」停用 → action=DISABLE, id=13（先查询拿到 id）。""")
    public WriteToolResult proposeOrgChange(
            @ToolParam(description = "动作：CREATE / UPDATE / DISABLE / ENABLE / DELETE。例如 DISABLE。"
                    + "DELETE=逻辑删除（从默认列表移除、可恢复），与 DISABLE=停用（暂停业务）语义不同",
                    required = true)
            String action,
            @ToolParam(description = "目标机构 ID；UPDATE / DISABLE / ENABLE / DELETE 必填。例如 13",
                    required = false)
            Long id,
            @ToolParam(description = "机构名称，用于在缺 id 时解析目标。例如 广东省第2保函运营机构", required = false)
            String orgName,
            @ToolParam(description = "机构编码，仅 CREATE 必填。例如 ORG4402", required = false)
            String orgCode,
            @ToolParam(description = "行政区划编码。例如 440000", required = false)
            String regionCode,
            @ToolParam(description = "机构层级：1=总部 2=省级 3=市级", required = false)
            Integer orgLevel,
            @ToolParam(description = "上级机构 ID，0 表示顶级（仅总部）", required = false)
            Long parentId,
            @ToolParam(description = "排序号", required = false)
            Integer sortNo,
            @ToolParam(description = "用户的原话，用于确认卡上核对模型理解是否正确", required = false)
            String userText,
            ToolContext toolContext) {

        String normalized = action == null ? "" : action.trim().toUpperCase();
        // 恢复（RESTORE）本期只在页面的「显示已删除」中提供：显式拒绝，避免它落进下面的
        // "停用/启用"兜底分支生成一张标题与预览不一致的畸形确认卡
        if ("RESTORE".equals(normalized)) {
            return WriteToolResult.failed(UNSUPPORTED_RESTORE);
        }
        Set<String> required = requiredPermissions(normalized);
        if (!allowed(toolContext, required.toArray(String[]::new))) {
            return WriteToolResult.denied(com.guarantee.ai.tool.AiPermissionGuard
                    .deniedReason(required.toArray(String[]::new)));
        }

        var scope = scope(toolContext);
        Long targetId = id;
        String targetName = orgName;

        // 目标解析（SYS-W-10）：缺 id 时按名称解析，命中多个一律要求澄清
        if (targetId == null && orgName != null && !orgName.isBlank() && !"CREATE".equals(normalized)) {
            List<SysOrg> candidates = orgService.findCandidates(orgName, scope, OrgService.CANDIDATE_LIMIT);
            if (candidates.isEmpty()) {
                return WriteToolResult.failed("没有找到名称包含「" + orgName + "」的机构"
                        + "（也可能不在你的数据范围内）。请先确认机构名称或换用 queryOrg 查询。");
            }
            if (candidates.size() > 1) {
                List<TargetCandidate> list = new ArrayList<>();
                for (SysOrg candidate : candidates) {
                    list.add(new TargetCandidate(candidate.getId(), candidate.getOrgCode(),
                            candidate.getOrgName(),
                            OrgService.levelName(candidate.getOrgLevel()) + " / "
                                    + candidate.getRegionName()));
                }
                return WriteToolResult.ambiguous(list,
                        "机构名称命中多个目标，请把候选列给用户确认后再调用本工具，不要自行选择");
            }
            targetId = candidates.get(0).getId();
            targetName = candidates.get(0).getOrgName();
        }
        if (!"CREATE".equals(normalized) && targetId == null) {
            return WriteToolResult.failed("缺少目标机构 id，且机构名称无法唯一定位。"
                    + "请先用 queryOrg 查询机构 id，或提供更精确的机构名称。");
        }

        ProposalRequest request = ProposalRequest.builder()
                .id(targetId)
                .targetName(targetName)
                .userText(userText)
                .orgCode(orgCode)
                .orgName(orgName)
                .regionCode(regionCode)
                .orgLevel(orgLevel)
                .parentId(parentId)
                .sortNo(sortNo)
                .build();

        try {
            ProposalPreview preview = buildPreview(normalized, targetId, targetName, request, scope,
                    toolContext);
            Long proposalTargetId = "CREATE".equals(normalized) ? null : targetId;
            return submit(toolContext, draft(toolContext, "proposeOrgChange", normalized,
                    "ORG", proposalTargetId, targetName, request, preview, required, userText, null));
        } catch (com.guarantee.common.exception.BizException ex) {
            return WriteToolResult.failed("无法生成提案：" + ex.getMessage());
        }
    }

    private ProposalPreview buildPreview(String action, Long targetId, String targetName,
                                         ProposalRequest request,
                                         com.guarantee.system.scope.DataScope scope,
                                         ToolContext context) {
        if ("CREATE".equals(action)) {
            OrgDto.CreateRequest dto = new OrgDto.CreateRequest();
            dto.setOrgCode(request.orgCode());
            dto.setOrgName(request.orgName());
            dto.setRegionCode(request.regionCode());
            dto.setOrgLevel(request.orgLevel());
            dto.setParentId(request.parentId() == null ? 0L : request.parentId());
            dto.setSortNo(request.sortNo());
            // 领域预检：编码唯一、上级层级正确、上级在数据范围内
            orgService.validateCreate(dto, scope);
            List<ProposalPreview.ChangeItem> changes = List.of(
                    ProposalPreview.ChangeItem.created("orgCode", "机构编码", request.orgCode()),
                    ProposalPreview.ChangeItem.created("orgName", "机构名称", request.orgName()),
                    ProposalPreview.ChangeItem.created("regionCode", "行政区划", request.regionCode()),
                    ProposalPreview.ChangeItem.created("orgLevel", "机构层级",
                            OrgService.levelName(request.orgLevel())),
                    ProposalPreview.ChangeItem.created("parentId", "上级机构",
                            String.valueOf(dto.getParentId())));
            return ProposalPreview.of("新增机构：" + request.orgName(), changes,
                    List.of("新增机构会改变数据范围边界，请确认层级与上级机构正确"),
                    List.of(), false);
        }

        SysOrg existing = orgService.getEntityById(targetId);
        if ("UPDATE".equals(action)) {
            OrgDto.UpdateRequest dto = new OrgDto.UpdateRequest();
            dto.setOrgName(request.orgName());
            dto.setRegionCode(request.regionCode());
            dto.setParentId(request.parentId());
            dto.setOrgLevel(request.orgLevel());
            dto.setSortNo(request.sortNo());
            orgService.validateUpdate(targetId, dto, scope);
            List<ProposalPreview.ChangeItem> changes = new ArrayList<>();
            addIfChanged(changes, "orgName", "机构名称", existing.getOrgName(), request.orgName());
            addIfChanged(changes, "regionCode", "行政区划", existing.getRegionCode(), request.regionCode());
            addIfChanged(changes, "parentId", "上级机构", existing.getParentId(), request.parentId());
            addIfChanged(changes, "orgLevel", "机构层级", existing.getOrgLevel(), request.orgLevel());
            addIfChanged(changes, "sortNo", "排序号", existing.getSortNo(), request.sortNo());
            if (changes.isEmpty()) {
                throw new com.guarantee.common.exception.BizException("没有任何字段发生变化，无需提交提案");
            }
            return ProposalPreview.of("修改机构：" + existing.getOrgName(), changes,
                    List.of(), List.of(), false);
        }

        // DELETE（逻辑删除，LD-01 / 设计 §7.4）
        // 与 DISABLE 严格区分：删除的前置检查比停用更严格——**被引用即拒绝**（§6.2），
        // 因为删除后被引用的历史会指向一条"不存在"的记录。
        if ("DELETE".equals(action)) {
            List<String> blockers = orgService.deleteBlockers(existing);
            if (!blockers.isEmpty()) {
                throw new com.guarantee.common.exception.BizException(
                        "该机构不能删除：" + String.join("；", blockers)
                                + "。如只需暂停业务，请改用「停用」。");
            }
            List<ProposalPreview.ChangeItem> changes = List.of(new ProposalPreview.ChangeItem(
                    "isDeleted", "是否已删除", "否", "是"));
            List<String> impact = new ArrayList<>();
            impact.add("影响面：" + orgService.deleteImpact(existing));
            return ProposalPreview.of("删除机构：" + existing.getOrgName(), changes, impact,
                    List.of("删除后该机构**默认不再出现在列表中**，可通过「显示已删除」恢复",
                            "删除**不改变启用/停用状态**，恢复后回到删除前的状态",
                            "删除属危险动作，需二次确认"),
                    true);
        }

        // DISABLE / ENABLE
        int targetStatus = "ENABLE".equals(action) ? 1 : 0;
        if (targetStatus == 0) {
            List<String> blockers = orgService.stopBlockers(existing);
            if (!blockers.isEmpty()) {
                throw new com.guarantee.common.exception.BizException(
                        "该机构不能停用：" + String.join("；", blockers));
            }
        }
        List<ProposalPreview.ChangeItem> changes = List.of(new ProposalPreview.ChangeItem(
                "status", "状态", statusName(existing.getStatus()), statusName(targetStatus)));
        List<String> impact = new ArrayList<>();
        impact.add("影响面：" + orgService.stopImpact(existing));
        return ProposalPreview.of((targetStatus == 0 ? "停用机构：" : "启用机构：") + existing.getOrgName(),
                changes, impact,
                targetStatus == 0 ? List.of("停用后该机构下的用户将无法通过该机构登录业务，请确认影响面") : List.of(),
                targetStatus == 0);
    }

    private static Set<String> requiredPermissions(String action) {
        Set<String> required = new LinkedHashSet<>();
        required.add(Permissions.AI_SYSTEM_WRITE);
        switch (action) {
            case "CREATE" -> required.add(Permissions.ORG_CREATE);
            case "UPDATE" -> required.add(Permissions.ORG_UPDATE);
            case "ENABLE", "DISABLE" -> required.add(Permissions.ORG_DISABLE);
            case "DELETE" -> required.add(Permissions.ORG_DELETE);
            default -> required.add(Permissions.ORG_UPDATE);
        }
        return required;
    }

    private static void addIfChanged(List<ProposalPreview.ChangeItem> changes, String field,
                                     String label, Object before, Object after) {
        if (after == null) {
            return;
        }
        String b = before == null ? null : String.valueOf(before);
        String a = String.valueOf(after);
        if (!java.util.Objects.equals(b, a)) {
            changes.add(new ProposalPreview.ChangeItem(field, label, b, a));
        }
    }

    private static String statusName(Integer status) {
        return OrgService.statusName(status);
    }
}
