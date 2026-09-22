package com.guarantee.ai.service.executor;

import com.guarantee.ai.entity.AiOperationProposal;
import com.guarantee.ai.service.ProposalExecutionContext;
import com.guarantee.ai.service.ProposalExecutionResult;
import com.guarantee.ai.service.ProposalExecutor;
import com.guarantee.ai.service.ProposalRequest;
import com.guarantee.system.dto.DepartmentDto;
import com.guarantee.system.entity.SysDepartment;
import com.guarantee.system.service.DepartmentService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 部门提案执行器（SYS-W-03）。
 *
 * <p>执行期重新校验：编码唯一、机构在数据范围内、上级部门同机构、
 * 停用前置检查（部门下有启用用户时禁止停用）、删除前置检查（下有用户或下级部门即拒绝）。</p>
 */
@Component
public class DepartmentProposalExecutor implements ProposalExecutor {

    private final DepartmentService departmentService;

    public DepartmentProposalExecutor(DepartmentService departmentService) {
        this.departmentService = departmentService;
    }

    @Override
    public String targetType() {
        return "DEPT";
    }

    @Override
    public ProposalExecutionResult execute(AiOperationProposal proposal, ProposalRequest request,
                                           ProposalExecutionContext context) {
        return switch (proposal.getAction()) {
            case "CREATE" -> create(request, context);
            case "UPDATE" -> update(proposal, request, context);
            case "ENABLE", "DISABLE" -> changeStatus(proposal, context);
            case "DELETE" -> delete(proposal, context);
            default -> ProposalExecutionResult.failed("部门不支持的动作: " + proposal.getAction());
        };
    }

    private ProposalExecutionResult create(ProposalRequest request, ProposalExecutionContext context) {
        DepartmentDto.CreateRequest dto = new DepartmentDto.CreateRequest();
        dto.setDeptCode(request.deptCode());
        dto.setDeptName(request.deptName());
        dto.setOrgId(request.orgId());
        dto.setParentId(request.parentId());
        dto.setSortNo(request.sortNo());
        var created = departmentService.create(dto, context.scope());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("deptCode", created.getDeptCode());
        after.put("deptName", created.getDeptName());
        after.put("orgId", created.getOrgId());
        after.put("orgName", created.getOrgName());
        after.put("parentId", created.getParentId());
        return ProposalExecutionResult.ok(
                "部门「" + created.getDeptName() + "」已新增（" + created.getOrgName() + "，id="
                        + created.getId() + "）", Map.of(), after);
    }

    private ProposalExecutionResult update(AiOperationProposal proposal, ProposalRequest request,
                                           ProposalExecutionContext context) {
        SysDepartment existing = departmentService.requireVisible(proposal.getTargetId(), context.scope());
        DepartmentDto.UpdateRequest dto = new DepartmentDto.UpdateRequest();
        dto.setDeptName(request.deptName());
        dto.setParentId(request.parentId());
        dto.setSortNo(request.sortNo());
        var updated = departmentService.update(proposal.getTargetId(), dto, context.scope());

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("deptName", existing.getDeptName());
        before.put("parentId", existing.getParentId());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("deptName", updated.getDeptName());
        after.put("parentId", updated.getParentId());
        return ProposalExecutionResult.ok("部门「" + updated.getDeptName() + "」已更新", before, after);
    }

    private ProposalExecutionResult changeStatus(AiOperationProposal proposal,
                                                 ProposalExecutionContext context) {
        SysDepartment existing = departmentService.requireVisible(proposal.getTargetId(), context.scope());
        int target = "ENABLE".equals(proposal.getAction()) ? 1 : 0;
        var updated = departmentService.changeStatus(proposal.getTargetId(), target, context.scope());
        Map<String, Object> before = Map.of("status", existing.getStatus());
        Map<String, Object> after = Map.of("status", updated.getStatus());
        String word = target == 1 ? "启用" : "停用";
        return ProposalExecutionResult.ok(
                "部门「" + updated.getDeptName() + "」已" + word, before, after,
                List.of(), List.of("影响面：" + departmentService.stopImpact(existing)));
    }

    /**
     * 部门逻辑删除（LD-01 / 设计 §6.2）。
     *
     * <p>执行期重做前置检查（SYS-C-05）：部门下是否新增了用户 / 下级部门会随他人操作变化。
     * 被引用时**拒绝并给出引用数量**，同时给出"改用停用"的替代路径——
     * 这正是删除与停用的差异点（停用允许被引用）。</p>
     */
    private ProposalExecutionResult delete(AiOperationProposal proposal,
                                           ProposalExecutionContext context) {
        SysDepartment existing = departmentService.requireVisible(proposal.getTargetId(), context.scope());
        List<String> blockers = departmentService.deleteBlockers(existing);
        if (!blockers.isEmpty()) {
            throw new com.guarantee.common.exception.BizException(
                    "该部门不能删除：" + String.join("；", blockers)
                            + "。如只需暂停业务，请改用「停用」。");
        }
        var deleted = departmentService.delete(proposal.getTargetId(), context.scope(), context.userId());
        Map<String, Object> before = Map.of("isDeleted", 0);
        Map<String, Object> after = Map.of("isDeleted", 1);
        return ProposalExecutionResult.ok(
                "部门「" + deleted.getDeptName() + "」已删除（默认不再出现在列表中，"
                        + "可在「显示已删除」中恢复）", before, after,
                List.of(),
                List.of("删除不改变启用/停用状态，恢复后回到删除前的状态（LD-02）；"
                        + "恢复时要求其所属机构已恢复"));
    }
}
