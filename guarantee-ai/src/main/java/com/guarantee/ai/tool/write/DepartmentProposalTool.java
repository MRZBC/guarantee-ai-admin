package com.guarantee.ai.tool.write;

import com.guarantee.ai.service.ProposalPreview;
import com.guarantee.ai.service.ProposalRequest;
import com.guarantee.ai.service.ProposalService;
import com.guarantee.ai.tool.AiDataScopeResolver;
import com.guarantee.ai.tool.AiPermissionGuard;
import com.guarantee.common.exception.BizException;
import com.guarantee.common.security.Permissions;
import com.guarantee.system.dto.DepartmentDto;
import com.guarantee.system.entity.SysDepartment;
import com.guarantee.system.service.DepartmentService;
import com.guarantee.system.service.OrgService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * {@code proposeDepartmentChange}（SYS-W-03）：部门变更提案。本方法不落库。
 */
@Component
public class DepartmentProposalTool extends BaseProposalTool {

    private final DepartmentService departmentService;

    public DepartmentProposalTool(ProposalService proposalService, AiDataScopeResolver scopeResolver,
                                  DepartmentService departmentService) {
        super(proposalService, scopeResolver);
        this.departmentService = departmentService;
    }

    @Tool(name = "proposeDepartmentChange",
            description = """
                    提交一个【部门变更提案】。本工具**不会立即修改数据**，只生成待确认提案，
                    用户点击「确认执行」后才生效。
                    支持的动作：
                    - CREATE：新增部门（必填 deptCode / deptName；parentId 选填，0 表示顶级）
                    - UPDATE：修改部门（必填 id，可改 deptName / parentId / sortNo）
                    - DISABLE / ENABLE：部门停用/启用（必填 id）
                    - DELETE：删除部门（必填 id）。**与停用完全不同**：停用=暂停业务、可随时启用；
                      删除=从默认列表移除、需要显式恢复才会重新出现。
                    重要规则：
                    - deptCode 不可修改（改编码请停用后新建）。
                    - 停用时若部门下仍有**下级部门**或**启用中的用户**，系统会拒绝
                      （该项不能有子项、也不能有关联的正常用户）；此时改用停用同样不行，
                      应建议"先处理下级部门 / 关联用户"。
                    - 删除比停用**更严格**：部门下存在未删除的用户或未删除的下级部门时会被拒绝，
                      失败信息会带具体数量。遇此情况请如实转述，并建议"先处理引用的数据，或改用停用"。
                    - 删除属**危险动作**，确认卡上有二次确认；删除**不改变启用/停用状态**，
                      恢复后回到删除前的状态。删除后可恢复（「显示已删除」）。
                    - 用户说"停用/暂停/禁用"时用 DISABLE，**不要**用 DELETE；用户说"删掉"时
                      必须先与用户确认是"删除"还是"停用"。
                    - 若部门名命中多个目标，返回值会给出 ambiguousTargets 候选，必须先向用户确认。
                    示例：在总部下新增「法务合规部」→ action=CREATE, deptCode=DEPT0025,
                    deptName=法务合规部（parentId 不传表示顶级）。""")
    public WriteToolResult proposeDepartmentChange(
            @ToolParam(description = "动作：CREATE / UPDATE / DISABLE / ENABLE / DELETE。例如 DISABLE。"
                    + "DELETE=逻辑删除（从默认列表移除、可恢复），与 DISABLE=停用（暂停业务）语义不同",
                    required = true)
            String action,
            @ToolParam(description = "目标部门 ID；UPDATE / DISABLE / ENABLE / DELETE 必填。例如 25",
                    required = false)
            Long id,
            @ToolParam(description = "部门名称，用于在缺 id 时解析目标。例如 业务受理部", required = false)
            String deptName,
            @ToolParam(description = "部门编码，仅 CREATE 必填。例如 DEPT0025", required = false)
            String deptCode,
            @ToolParam(description = "上级部门 ID，0 表示顶级。不传表示顶级", required = false)
            Long parentId,
            @ToolParam(description = "排序号", required = false)
            Integer sortNo,
            @ToolParam(description = "用户的原话，用于确认卡上核对模型理解是否正确", required = false)
            String userText,
            ToolContext toolContext) {

        String normalized = action == null ? "" : action.trim().toUpperCase();
        // 恢复（RESTORE）本期只在页面的「显示已删除」中提供（见 BaseProposalTool 说明）
        if ("RESTORE".equals(normalized)) {
            return WriteToolResult.failed(UNSUPPORTED_RESTORE);
        }
        Set<String> required = requiredPermissions(normalized);
        if (!allowed(toolContext, required.toArray(String[]::new))) {
            return WriteToolResult.denied(AiPermissionGuard.deniedReason(required.toArray(String[]::new)));
        }

        var scope = scope(toolContext);
        Long targetId = id;
        String targetName = deptName;

        if (targetId == null && deptName != null && !deptName.isBlank() && !"CREATE".equals(normalized)) {
            List<SysDepartment> candidates = departmentService.findCandidates(
                    deptName, scope, DepartmentService.CANDIDATE_LIMIT);
            if (candidates.isEmpty()) {
                return WriteToolResult.failed("没有找到名称包含「" + deptName + "」的部门"
                        + "（也可能不在你的数据范围内）。请先用 queryDepartment 查询。");
            }
            if (candidates.size() > 1) {
                List<TargetCandidate> list = new ArrayList<>();
                for (SysDepartment candidate : candidates) {
                    list.add(new TargetCandidate(candidate.getId(), candidate.getDeptCode(),
                            candidate.getDeptName(), "上级部门 ID=" + candidate.getParentId()));
                }
                return WriteToolResult.ambiguous(list,
                        "部门名称命中多个目标，请把候选列给用户确认后再调用本工具，不要自行选择");
            }
            targetId = candidates.get(0).getId();
            targetName = candidates.get(0).getDeptName();
        }
        if (!"CREATE".equals(normalized) && targetId == null) {
            return WriteToolResult.failed("缺少目标部门 id，且部门名称无法唯一定位。"
                    + "请先用 queryDepartment 查询部门 id。");
        }

        ProposalRequest request = ProposalRequest.builder()
                .id(targetId).targetName(targetName).userText(userText)
                .deptCode(deptCode).deptName(deptName)
                .parentId(parentId).sortNo(sortNo)
                .build();

        try {
            PreviewResult built = buildPreview(normalized, targetId, request, scope);
            // 模型只传 id 时 targetName 一直是 null，必须用 buildPreview 已加载的实体名回填，
            // 否则落库的 target_name 为 NULL，确认卡上就丢了目标名
            String resolvedTargetName = resolveTargetName(targetName, built.targetName());
            Long proposalTargetId = "CREATE".equals(normalized) ? null : targetId;
            return submit(toolContext, draft(toolContext, "proposeDepartmentChange", normalized,
                    "DEPT", proposalTargetId, resolvedTargetName, request, built.preview(), required,
                    userText, null));
        } catch (BizException ex) {
            return WriteToolResult.failed("无法生成提案：" + ex.getMessage());
        }
    }

    private PreviewResult buildPreview(String action, Long targetId, ProposalRequest request,
                                         com.guarantee.system.scope.DataScope scope) {
        if ("CREATE".equals(action)) {
            DepartmentDto.CreateRequest dto = new DepartmentDto.CreateRequest();
            dto.setDeptCode(request.deptCode());
            dto.setDeptName(request.deptName());
            dto.setParentId(request.parentId());
            dto.setSortNo(request.sortNo());
            departmentService.validateCreate(dto, scope);
            List<ProposalPreview.ChangeItem> changes = List.of(
                    ProposalPreview.ChangeItem.created("deptCode", "部门编码", request.deptCode()),
                    ProposalPreview.ChangeItem.created("deptName", "部门名称", request.deptName()),
                    ProposalPreview.ChangeItem.created("parentId", "上级部门",
                            String.valueOf(dto.getParentId() == null ? 0L : dto.getParentId())));
            // CREATE 没有既有实体可回填，名字仍取模型传入的 deptName（语义不变）
            return new PreviewResult(ProposalPreview.of("新增部门：" + request.deptName(),
                    changes, List.of(), List.of(), false), null);
        }

        SysDepartment existing = departmentService.requireVisible(targetId, scope);
        // 目标名回填：模型常只传 id 不传名字，这里直接用上面已加载的实体取名（不再查库），
        // 否则 target_name 落 NULL，确认卡上「— 目标名」这一整段会消失
        String existingName = existing == null ? null : existing.getDeptName();
        if ("UPDATE".equals(action)) {
            DepartmentDto.UpdateRequest dto = new DepartmentDto.UpdateRequest();
            dto.setDeptName(request.deptName());
            dto.setParentId(request.parentId());
            dto.setSortNo(request.sortNo());
            departmentService.validateUpdate(targetId, dto, scope);
            List<ProposalPreview.ChangeItem> changes = new ArrayList<>();
            addIfChanged(changes, "deptName", "部门名称", existing.getDeptName(), request.deptName());
            addIfChanged(changes, "parentId", "上级部门", existing.getParentId(), request.parentId());
            addIfChanged(changes, "sortNo", "排序号", existing.getSortNo(), request.sortNo());
            if (changes.isEmpty()) {
                throw new BizException("没有任何字段发生变化，无需提交提案");
            }
            return new PreviewResult(ProposalPreview.of("修改部门：" + existing.getDeptName(), changes,
                    List.of(), List.of(), false), existingName);
        }

        // DELETE（逻辑删除，LD-01 / 设计 §7.4）：被引用即拒绝，比停用更严格（§6.2）
        if ("DELETE".equals(action)) {
            List<String> blockers = departmentService.deleteBlockers(existing);
            if (!blockers.isEmpty()) {
                throw new BizException("该部门不能删除：" + String.join("；", blockers)
                        + "。如只需暂停业务，请改用「停用」。");
            }
            List<ProposalPreview.ChangeItem> changes = List.of(new ProposalPreview.ChangeItem(
                    "isDeleted", "是否已删除", "否", "是"));
            List<String> impact = new ArrayList<>();
            impact.add("影响面：" + ProposalPreview.formatImpact(departmentService.deleteImpact(existing)));
            return new PreviewResult(ProposalPreview.of("删除部门：" + existing.getDeptName(), changes,
                    impact,
                    List.of("删除后该部门**默认不再出现在列表中**，可通过「显示已删除」恢复",
                            "删除**不改变启用/停用状态**，恢复后回到删除前的状态",
                            "删除属危险动作，需二次确认"),
                    true), existingName);
        }

        int targetStatus = "ENABLE".equals(action) ? 1 : 0;
        if (targetStatus == 0) {
            long users = departmentService.countEnabledUsers(existing.getId());
            if (users > 0) {
                throw new BizException("该部门下仍有 " + users + " 个启用中的用户，不能停用");
            }
        }
        List<ProposalPreview.ChangeItem> changes = List.of(new ProposalPreview.ChangeItem(
                "status", "状态", statusName(existing.getStatus()), statusName(targetStatus)));
        return new PreviewResult(ProposalPreview.of(
                (targetStatus == 0 ? "停用部门：" : "启用部门：") + existing.getDeptName(),
                changes,
                List.of("影响面：" + ProposalPreview.formatImpact(departmentService.stopImpact(existing))),
                targetStatus == 0 ? List.of("停用后该部门不再出现在用户归属的可选项中") : List.of(),
                targetStatus == 0), existingName);
    }

    private static Set<String> requiredPermissions(String action) {
        Set<String> required = new LinkedHashSet<>();
        required.add(Permissions.AI_SYSTEM_WRITE);
        switch (action) {
            case "CREATE" -> required.add(Permissions.DEPT_CREATE);
            case "UPDATE" -> required.add(Permissions.DEPT_UPDATE);
            case "ENABLE", "DISABLE" -> required.add(Permissions.DEPT_DISABLE);
            case "DELETE" -> required.add(Permissions.DEPT_DELETE);
            default -> required.add(Permissions.DEPT_UPDATE);
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
        if (!Objects.equals(b, a)) {
            changes.add(new ProposalPreview.ChangeItem(field, label, b, a));
        }
    }

    private static String statusName(Integer status) {
        return OrgService.statusName(status);
    }
}
