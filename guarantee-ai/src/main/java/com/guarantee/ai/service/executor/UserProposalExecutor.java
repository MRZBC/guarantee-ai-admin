package com.guarantee.ai.service.executor;

import com.guarantee.ai.entity.AiOperationProposal;
import com.guarantee.ai.service.ProposalExecutionContext;
import com.guarantee.ai.service.ProposalExecutionResult;
import com.guarantee.ai.service.ProposalExecutor;
import com.guarantee.ai.service.ProposalRequest;
import com.guarantee.common.security.Roles;
import com.guarantee.system.dto.UserDto;
import com.guarantee.system.entity.SysUser;
import com.guarantee.system.service.UserService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户提案执行器（SYS-W-04，D-2 收敛后的动作）。
 *
 * <p><b>执行期必须重新判定危险条件</b>（SYS-C-05）："停用最后一个 ADMIN""给自己加 ADMIN"
 * "删除自己 / 删除最后一个启用 ADMIN"这些结论会随他人操作而变化，绝不能沿用生成提案时的判定结果。
 * 具体判定复用 {@code UserService} 的预检方法，保证预览与执行口径一致。</p>
 */
@Component
public class UserProposalExecutor implements ProposalExecutor {

    private final UserService userService;

    public UserProposalExecutor(UserService userService) {
        this.userService = userService;
    }

    @Override
    public String targetType() {
        return "USER";
    }

    @Override
    public ProposalExecutionResult execute(AiOperationProposal proposal, ProposalRequest request,
                                           ProposalExecutionContext context) {
        return switch (proposal.getAction()) {
            case "UPDATE" -> updateProfile(proposal, request, context);
            case "ENABLE", "DISABLE" -> changeStatus(proposal, request, context);
            case "ASSIGN_ROLES" -> assignRoles(proposal, request, context);
            case "DELETE" -> delete(proposal, context);
            case "CREATE", "RESET_PASSWORD" ->
                // D-2：这两类动作本期不支持，即使在执行期也必须明确拒绝
                    ProposalExecutionResult.failed(
                            "本期不支持用户新建与密码重置（见需求 D-2），请在系统管理页面或由管理员线下处理");
            default -> ProposalExecutionResult.failed("用户不支持的动作: " + proposal.getAction());
        };
    }

    private ProposalExecutionResult updateProfile(AiOperationProposal proposal, ProposalRequest request,
                                                  ProposalExecutionContext context) {
        SysUser existing = userService.requireVisible(proposal.getTargetId(), context.scope());
        UserDto.UpdateRequest dto = new UserDto.UpdateRequest();
        dto.setRealName(request.realName());
        dto.setPhone(request.phone());
        dto.setEmail(request.email());
        dto.setDeptId(request.deptId());
        dto.setClearDept(request.clearDept());
        // 执行期重新预检（危险动作保护 + 格式校验 + 不得改自己的部门）
        userService.validateUpdateProfile(proposal.getTargetId(), dto, context.scope(), context.userId());
        var updated = userService.updateProfile(proposal.getTargetId(), dto, context.scope(), context.userId());

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("realName", existing.getRealName());
        before.put("phone", existing.getPhone());
        before.put("email", existing.getEmail());
        before.put("deptId", existing.getDeptId());
        // 注意：审计会对 phone/email 做 D-4 脱敏，这里给出原文只是为了 diff 判定，
        // 落库前由 OperationAuditService.maskSnapshotForAudit 统一处理。
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("realName", updated.getRealName());
        after.put("phone", dto.getPhone());
        after.put("email", dto.getEmail());
        after.put("deptId", Boolean.TRUE.equals(dto.getClearDept()) ? null : dto.getDeptId());
        return ProposalExecutionResult.ok("用户「" + updated.getUsername() + "」资料已更新", before, after);
    }

    private ProposalExecutionResult changeStatus(AiOperationProposal proposal, ProposalRequest request,
                                                 ProposalExecutionContext context) {
        SysUser existing = userService.requireVisible(proposal.getTargetId(), context.scope());
        int target = "ENABLE".equals(proposal.getAction()) ? 1 : 0;
        // 执行期重新判定"停用自己 / 停用最后一个 ADMIN"（SYS-C-05 / AC-19）
        userService.validateStatusChange(existing, target, context.userId());
        var updated = userService.changeStatus(proposal.getTargetId(), target, context.scope(), context.userId());

        Map<String, Object> before = Map.of("status", existing.getStatus());
        Map<String, Object> after = Map.of("status", updated.getStatus());
        String word = target == 1 ? "启用" : "停用";
        List<String> notes = new ArrayList<>();
        if (target == 0) {
            notes.add("该用户未完结的 AI 会话将失效，其持有的 JWT 已被撤销，需重新登录");
        }
        return ProposalExecutionResult.ok(
                "用户「" + updated.getUsername() + "」已" + word, before, after,
                target == 0 ? List.of(proposal.getTargetId()) : List.of(), notes);
    }

    private ProposalExecutionResult assignRoles(AiOperationProposal proposal, ProposalRequest request,
                                                ProposalExecutionContext context) {
        SysUser existing = userService.requireVisible(proposal.getTargetId(), context.scope());
        List<String> currentRoles = userService.listRoleCodesByUserId(proposal.getTargetId());
        // 执行期重新判定"给自己增删 ADMIN""移除最后一个 ADMIN 的 ADMIN 角色"（SYS-C-05）
        List<String> targetRoles = userService.validateAssignRoles(existing, request.roleCodes(), context.userId());
        var updated = userService.assignRoles(proposal.getTargetId(), targetRoles,
                context.scope(), context.userId());

        Map<String, Object> before = Map.of("roleCodes", currentRoles);
        Map<String, Object> after = Map.of("roleCodes", targetRoles);
        boolean adminTouched = currentRoles.contains(Roles.ADMIN) || targetRoles.contains(Roles.ADMIN);
        List<String> notes = new ArrayList<>();
        notes.add("该用户持有的 JWT 已被撤销，权限变更立即生效，需重新登录");
        if (adminTouched) {
            notes.add("本次变更涉及超级管理员（ADMIN）角色，请确认影响面");
        }
        return ProposalExecutionResult.ok(
                "用户「" + updated.getUsername() + "」角色已变更为 " + targetRoles,
                before, after, List.of(proposal.getTargetId()), notes);
    }

    /**
     * 用户逻辑删除（LD-01 / 设计 §6.2 / §6.3）。
     *
     * <p>执行期重做危险条件判定（SYS-C-05）："是否在删自己""是否最后一个启用 ADMIN"会随
     * 他人操作变化，必须重新查一遍；这里直接复用 {@code deleteBlockers}，
     * 与写工具预检、页面删除三处口径完全一致。</p>
     *
     * <p>令牌撤销由 {@code UserService.delete} 内部完成（§6.3）：删除后旧 JWT 若不撤销，
     * 该用户最长还能访问 12 小时，删除就形同虚设。</p>
     */
    private ProposalExecutionResult delete(AiOperationProposal proposal,
                                           ProposalExecutionContext context) {
        SysUser existing = userService.requireVisible(proposal.getTargetId(), context.scope());
        List<String> blockers = userService.deleteBlockers(existing, context.userId());
        if (!blockers.isEmpty()) {
            throw new com.guarantee.common.exception.BizException(
                    "该用户不能删除：" + String.join("；", blockers));
        }
        var deleted = userService.delete(proposal.getTargetId(), context.scope(), context.userId());
        Map<String, Object> before = Map.of("isDeleted", 0);
        Map<String, Object> after = Map.of("isDeleted", 1);
        List<String> notes = new ArrayList<>();
        notes.add("该用户持有的 JWT 已被立即撤销，需重新登录");
        notes.add("删除不改变启用/停用状态，恢复后回到删除前的状态（LD-02）；"
                + "恢复时要求其所属机构与部门已恢复");
        notes.add("删除后账号无法登录，且登录失败提示与密码错误完全一致（防账号枚举，LD-05）");
        return ProposalExecutionResult.ok(
                "用户「" + deleted.getUsername() + "」已删除（默认不再出现在列表中，"
                        + "可在「显示已删除」中恢复）", before, after,
                List.of(proposal.getTargetId()), notes);
    }
}
