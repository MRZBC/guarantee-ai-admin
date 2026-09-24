package com.guarantee.ai.service.executor;

import com.guarantee.ai.entity.AiOperationProposal;
import com.guarantee.ai.service.ProposalExecutionContext;
import com.guarantee.ai.service.ProposalExecutionResult;
import com.guarantee.ai.service.ProposalExecutor;
import com.guarantee.ai.service.ProposalRequest;
import com.guarantee.common.security.Roles;
import com.guarantee.system.dto.RoleDto;
import com.guarantee.system.entity.SysRole;
import com.guarantee.system.service.RoleService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色提案执行器（SYS-W-05）。
 *
 * <p>执行期重新校验：{@code ADMIN} 角色不可改、不可授权、不可删除，权限码必须已存在、
 * 不得使用保留码 {@code ADMIN} 新建、删除前不得有未删除用户仍持有该角色。
 * 授权与删除变更后撤销持有该角色用户的令牌（由 {@code RoleService} 完成）。</p>
 */
@Component
public class RoleProposalExecutor implements ProposalExecutor {

    private final RoleService roleService;

    public RoleProposalExecutor(RoleService roleService) {
        this.roleService = roleService;
    }

    @Override
    public String targetType() {
        return "ROLE";
    }

    @Override
    public ProposalExecutionResult execute(AiOperationProposal proposal, ProposalRequest request,
                                           ProposalExecutionContext context) {
        return switch (proposal.getAction()) {
            case "CREATE" -> create(request);
            case "UPDATE" -> update(proposal, request);
            case "ASSIGN_PERMISSIONS" -> assignPermissions(proposal, request);
            case "DELETE" -> delete(proposal, context);
            default -> ProposalExecutionResult.failed("角色不支持的动作: " + proposal.getAction());
        };
    }

    private ProposalExecutionResult create(ProposalRequest request) {
        RoleDto.CreateRequest dto = new RoleDto.CreateRequest();
        dto.setRoleCode(request.roleCode());
        dto.setRoleName(request.typeName() != null ? request.typeName() : request.targetName());
        dto.setDescription(request.description());
        var created = roleService.create(dto);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("roleCode", created.getRoleCode());
        after.put("roleName", created.getRoleName());
        after.put("description", created.getDescription());
        return ProposalExecutionResult.ok(
                "角色「" + created.getRoleName() + "」（" + created.getRoleCode() + "）已新增，"
                        + "尚未授予任何权限，请另行授权", Map.of(), after);
    }

    private ProposalExecutionResult update(AiOperationProposal proposal, ProposalRequest request) {
        SysRole existing = roleService.findEntityById(proposal.getTargetId());
        RoleDto.UpdateRequest dto = new RoleDto.UpdateRequest();
        dto.setRoleName(request.typeName() != null ? request.typeName() : request.targetName());
        dto.setDescription(request.description());
        var updated = roleService.update(proposal.getTargetId(), dto);

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("roleName", existing.getRoleName());
        before.put("description", existing.getDescription());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("roleName", updated.getRoleName());
        after.put("description", updated.getDescription());
        return ProposalExecutionResult.ok("角色「" + updated.getRoleName() + "」已更新", before, after);
    }

    private ProposalExecutionResult assignPermissions(AiOperationProposal proposal, ProposalRequest request) {
        SysRole role = roleService.findEntityById(proposal.getTargetId());
        if (request.roleCode() == null) {
            return ProposalExecutionResult.failed("缺少角色编码，无法授权");
        }
        var current = roleService.getById(role.getId());
        List<String> before = current.getPermissionCodes() == null ? List.of() : current.getPermissionCodes();
        var updated = roleService.assignPermissions(request.roleCode(), request.permCodes());

        Map<String, Object> beforeMap = Map.of("permissionCodes", before);
        Map<String, Object> afterMap = Map.of("permissionCodes",
                updated.getPermissionCodes() == null ? List.of() : updated.getPermissionCodes());
        List<String> notes = java.util.List.of(
                "持有该角色的用户已被立即强制下线，需要重新登录（新权限随即生效）");
        if (Roles.ADMIN.equalsIgnoreCase(role.getRoleCode())) {
            notes = java.util.List.of("超级管理员角色不允许变更权限");
        }
        // 执行结果消息会落成会话消息给用户看：必须用中文权限名。
        // 结构化 before/after 仍存编码（审计要的是"到底改了哪几项"，见 P-05 §227）。
        List<String> afterCodes = updated.getPermissionCodes() == null
                ? List.of() : updated.getPermissionCodes();
        String afterText = afterCodes.isEmpty()
                ? "（已清空该角色的全部权限）"
                : String.join("，", roleService.permissionDisplayNames(afterCodes));
        return ProposalExecutionResult.ok(
                "角色「" + updated.getRoleName() + "」的权限已变更为 " + afterText,
                beforeMap, afterMap, List.of(), notes);
    }

    /**
     * 角色逻辑删除（LD-01 / 设计 §6.2 / §6.3）。
     *
     * <p>执行期重做前置检查（SYS-C-05）："是否还有未删除用户持有该角色"在提案生成后可能变化。
     * 这里显式复用 {@code deleteBlockers}（含 ADMIN 保留角色判定），
     * 让"ADMIN 不可删除""仍被用户持有需先解绑"两条规则在确认那一刻依然成立。</p>
     */
    private ProposalExecutionResult delete(AiOperationProposal proposal,
                                           ProposalExecutionContext context) {
        SysRole existing = roleService.findEntityById(proposal.getTargetId());
        List<String> blockers = roleService.deleteBlockers(existing);
        if (!blockers.isEmpty()) {
            throw new com.guarantee.common.exception.BizException(
                    "该角色不能删除：" + String.join("；", blockers));
        }
        // 角色是全局配置、无机构数据范围，因此 delete 只需要操作者 id（写入 deleted_by）
        var deleted = roleService.delete(proposal.getTargetId(), context.userId());
        Map<String, Object> before = Map.of("isDeleted", 0);
        Map<String, Object> after = Map.of("isDeleted", 1);
        return ProposalExecutionResult.ok(
                "角色「" + deleted.getRoleName() + "」（" + deleted.getRoleCode() + "）已删除"
                        + "（默认不再出现在列表中，可在「显示已删除」中恢复）", before, after,
                List.of(),
                List.of("持有该角色的用户已被强制下线，需要重新登录",
                        "删除不改变启用/停用状态，恢复后回到删除前的状态（LD-02）；"
                                + "恢复时要求角色编码未被有效角色占用"));
    }
}
