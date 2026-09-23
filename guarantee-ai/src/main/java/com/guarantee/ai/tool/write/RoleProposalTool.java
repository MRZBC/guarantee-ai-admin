package com.guarantee.ai.tool.write;

import com.guarantee.ai.service.ProposalPreview;
import com.guarantee.ai.service.ProposalRequest;
import com.guarantee.ai.service.ProposalService;
import com.guarantee.ai.tool.AiDataScopeResolver;
import com.guarantee.ai.tool.AiPermissionGuard;
import com.guarantee.common.exception.BizException;
import com.guarantee.common.security.Permissions;
import com.guarantee.common.security.Roles;
import com.guarantee.system.dto.RoleDto;
import com.guarantee.system.entity.SysRole;
import com.guarantee.system.service.RoleService;
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
 * {@code proposeRoleChange}（SYS-W-05）：角色变更提案。本方法不落库。
 *
 * <p>{@code ADMIN} 角色不可改、不可授权、不可删除；权限码只能在既有 {@code sys_permission}
 * 中选择，不接受自由构造。</p>
 */
@Component
public class RoleProposalTool extends BaseProposalTool {

    private final RoleService roleService;

    public RoleProposalTool(ProposalService proposalService, AiDataScopeResolver scopeResolver,
                            RoleService roleService) {
        super(proposalService, scopeResolver);
        this.roleService = roleService;
    }

    @Tool(name = "proposeRoleChange",
            description = """
                    提交一个【角色变更提案】。本工具**不会立即修改数据**，只生成待确认提案，
                    用户点击「确认执行」后才生效。
                    支持的动作：
                    - CREATE：新增角色（必填 roleCode / roleName，选填 description）。角色编码不得使用保留码 ADMIN。
                    - UPDATE：修改角色（必填 id 或 roleCode；可改 roleName / description）
                    - ASSIGN_PERMISSIONS：角色授权（必填 roleCode 与 permCodes）
                    - DELETE：删除角色（必填 id 或 roleCode）。角色**没有"停用"动作**：
                      停用角色等于不允许登录，本系统用「不授予权限」表达，因此删除角色的区别是
                      "从默认列表移除、需显式恢复才会重新出现"。
                    重要规则：
                    - 超级管理员（ADMIN）角色不允许修改、不允许变更权限，**也不允许删除**。
                    - 授权只能选择系统里**已经存在**的权限码（例如 system:org:view、ai:chat），
                      不接受自定义或新造权限码。可先用 queryRole(mode=PERMISSION) 查询可用权限码。
                    - 授权变更会让所有持有该角色的用户**立即被强制下线**，需要重新登录，权限随即生效。
                    - 删除的检查更严格：仍有未删除的用户持有该角色时会被拒绝，失败信息会带持有用户数，
                      请如实转述并建议"先解除这些用户的角色绑定，或改用其他方式停用其访问"。
                    - 删除属**危险动作**，确认卡上有二次确认；删除后可恢复（「显示已删除」），
                      且**不改变角色原有的权限与状态**。
                    - 用户只是想让某角色"暂时失效"时，**不要**用 DELETE（角色删除不可随意来回），
                      请说明当前不支持"停用角色"，并给出替代路径（例如改用角色分配把用户切到只读角色）。
                    示例：给 ANALYST 角色增加 system:audit:view → action=ASSIGN_PERMISSIONS, roleCode=ANALYST。""")
    public WriteToolResult proposeRoleChange(
            @ToolParam(description = "动作：CREATE / UPDATE / ASSIGN_PERMISSIONS / DELETE。例如 ASSIGN_PERMISSIONS。"
                    + "DELETE=逻辑删除（从默认列表移除、可恢复）；角色没有停用动作",
                    required = true)
            String action,
            @ToolParam(description = "目标角色 ID（UPDATE / DELETE 用）。例如 3", required = false)
            Long id,
            @ToolParam(description = "角色编码。ASSIGN_PERMISSIONS 必填；UPDATE / DELETE 可用于定位。例如 ANALYST",
                    required = false)
            String roleCode,
            @ToolParam(description = "角色名称。CREATE 必填。例如 区域运营专员", required = false)
            String roleName,
            @ToolParam(description = "角色描述", required = false)
            String description,
            @ToolParam(description = "权限编码列表（ASSIGN_PERMISSIONS）。例如 [\"system:org:view\",\"ai:chat\"]", required = false)
            List<String> permCodes,
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

        // 目标解析（SYS-W-10）：UPDATE / ASSIGN_PERMISSIONS 都需要角色
        SysRole target = null;
        if (id != null) {
            target = roleService.findEntityById(id);
        } else if (roleCode != null && !roleCode.isBlank() && !"CREATE".equals(normalized)) {
            List<SysRole> candidates = roleService.findCandidates(roleCode, RoleService.CANDIDATE_LIMIT);
            List<SysRole> exact = candidates.stream()
                    .filter(r -> roleCode.trim().equalsIgnoreCase(r.getRoleCode())).toList();
            if (exact.size() == 1) {
                target = exact.get(0);
            } else if (candidates.size() > 1) {
                List<TargetCandidate> list = new ArrayList<>();
                for (SysRole candidate : candidates) {
                    list.add(new TargetCandidate(candidate.getId(), candidate.getRoleCode(),
                            candidate.getRoleName(), candidate.getDescription()));
                }
                return WriteToolResult.ambiguous(list,
                        "角色关键字命中多个目标，请把候选列给用户确认后再调用本工具，不要自行选择");
            } else if (candidates.size() == 1) {
                target = candidates.get(0);
            }
        }
        if (!"CREATE".equals(normalized) && target == null) {
            return WriteToolResult.failed("缺少目标角色，或角色编码无法唯一定位。"
                    + "请先用 queryRole(mode=ROLE) 查询角色。");
        }

        ProposalRequest request = ProposalRequest.builder()
                .id(target == null ? null : target.getId())
                .targetName(target == null ? roleName : target.getRoleName())
                .userText(userText)
                .roleCode(roleCode != null ? roleCode : (target == null ? null : target.getRoleCode()))
                .typeName(roleName)
                .description(description)
                .permCodes(permCodes)
                .build();

        try {
            ProposalPreview preview = buildPreview(normalized, target, request);
            return submit(toolContext, draft(toolContext, "proposeRoleChange", normalized,
                    "ROLE", target == null ? null : target.getId(),
                    target == null ? roleName : target.getRoleName(),
                    request, preview, required, userText, null));
        } catch (BizException ex) {
            return WriteToolResult.failed("无法生成提案：" + ex.getMessage());
        }
    }

    private ProposalPreview buildPreview(String action, SysRole target, ProposalRequest request) {
        if ("CREATE".equals(action)) {
            RoleDto.CreateRequest dto = new RoleDto.CreateRequest();
            dto.setRoleCode(request.roleCode());
            dto.setRoleName(request.typeName());
            dto.setDescription(request.description());
            roleService.validateCreate(dto);
            List<ProposalPreview.ChangeItem> changes = List.of(
                    ProposalPreview.ChangeItem.created("roleCode", "角色编码", request.roleCode()),
                    ProposalPreview.ChangeItem.created("roleName", "角色名称", request.typeName()),
                    ProposalPreview.ChangeItem.created("description", "描述", request.description()));
            return ProposalPreview.of("新增角色：" + request.typeName(), changes,
                    List.of("新角色初始不含任何权限，请在创建后单独授权"),
                    List.of("不得使用保留角色编码 ADMIN（已由服务端拦截）"), false);
        }

        if ("ASSIGN_PERMISSIONS".equals(action)) {
            var current = roleService.getById(target.getId());
            List<String> currentCodes = current.getPermissionCodes() == null
                    ? List.of() : current.getPermissionCodes();
            List<String> targetCodes = request.permCodes() == null ? List.of() : request.permCodes();
            // 预检：ADMIN 不可授权 + 权限码必须已存在
            roleService.validateAssignPermissions(request.roleCode(), targetCodes);
            List<ProposalPreview.ChangeItem> changes = List.of(new ProposalPreview.ChangeItem(
                    "permCodes", "权限", String.join(", ", currentCodes), String.join(", ", targetCodes)));
            List<String> impact = List.of("影响面："
                    + ProposalPreview.formatImpact(roleService.assignPermissionsImpact(target, targetCodes)));
            List<String> warnings = new ArrayList<>();
            warnings.add("授权会立即改变该角色下所有用户的权限");
            if (targetCodes.contains(Permissions.AUDIT_VIEW)) {
                warnings.add("本次授权包含 system:audit:view（全局操作审计），"
                        + "该权限按 D-1a 仅应授予超级管理员");
            }
            if (Roles.ADMIN.equalsIgnoreCase(target.getRoleCode())) {
                throw new BizException("超级管理员（ADMIN）角色不允许变更权限");
            }
            return ProposalPreview.of("角色授权：" + target.getRoleName(), changes, impact, warnings, true);
        }

        // DELETE（逻辑删除，LD-01 / 设计 §7.4）：ADMIN 不可删除、仍有用户持有时拒绝（§6.2）
        if ("DELETE".equals(action)) {
            List<String> blockers = roleService.deleteBlockers(target);
            if (!blockers.isEmpty()) {
                throw new BizException("该角色不能删除：" + String.join("；", blockers));
            }
            List<ProposalPreview.ChangeItem> changes = List.of(new ProposalPreview.ChangeItem(
                    "isDeleted", "是否已删除", "否", "是"));
            List<String> impact = new ArrayList<>();
            impact.add("影响面：" + ProposalPreview.formatImpact(roleService.deleteImpact(target)));
            return ProposalPreview.of("删除角色：" + target.getRoleName(), changes, impact,
                    List.of("删除后该角色**默认不再出现在列表中**，可通过「显示已删除」恢复",
                            "删除**不改变启用/停用状态**，恢复后回到删除前的状态",
                            "持有该角色的用户会被强制下线，需要重新登录",
                            "删除属危险动作，需二次确认"),
                    true);
        }

        // UPDATE
        RoleDto.UpdateRequest dto = new RoleDto.UpdateRequest();
        dto.setRoleName(request.typeName());
        dto.setDescription(request.description());
        roleService.validateUpdate(target.getId(), dto);
        List<ProposalPreview.ChangeItem> changes = new ArrayList<>();
        addIfChanged(changes, "roleName", "角色名称", target.getRoleName(), request.typeName());
        addIfChanged(changes, "description", "描述", target.getDescription(), request.description());
        if (changes.isEmpty()) {
            throw new BizException("没有任何字段发生变化，无需提交提案");
        }
        return ProposalPreview.of("修改角色：" + target.getRoleName(), changes,
                List.of(), List.of("roleCode 不可修改（已由服务端拦截）"), false);
    }

    private static Set<String> requiredPermissions(String action) {
        Set<String> required = new LinkedHashSet<>();
        required.add(Permissions.AI_SYSTEM_WRITE);
        switch (action) {
            case "CREATE" -> required.add(Permissions.ROLE_CREATE);
            case "UPDATE" -> required.add(Permissions.ROLE_UPDATE);
            case "ASSIGN_PERMISSIONS" -> required.add(Permissions.ROLE_ASSIGN_PERMISSION);
            case "DELETE" -> required.add(Permissions.ROLE_DELETE);
            default -> required.add(Permissions.ROLE_UPDATE);
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
}
