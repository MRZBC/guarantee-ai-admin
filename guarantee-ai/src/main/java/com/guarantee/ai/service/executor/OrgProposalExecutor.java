package com.guarantee.ai.service.executor;

import com.guarantee.ai.entity.AiOperationProposal;
import com.guarantee.ai.service.ProposalExecutionContext;
import com.guarantee.ai.service.ProposalExecutionResult;
import com.guarantee.ai.service.ProposalExecutor;
import com.guarantee.ai.service.ProposalPreview;
import com.guarantee.ai.service.ProposalRequest;
import com.guarantee.system.dto.OrgDto;
import com.guarantee.system.entity.SysOrg;
import com.guarantee.system.service.OrgService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 机构提案执行器（SYS-W-02）。
 *
 * <p>执行期重新校验：编码唯一、上级存在且层级正确、不能形成环、停用前置检查
 * （存在启用下级机构或启用用户时禁止停用）、删除前置检查（被下级机构/部门/用户/订单引用即拒绝）。</p>
 */
@Component
public class OrgProposalExecutor implements ProposalExecutor {

    private final OrgService orgService;

    public OrgProposalExecutor(OrgService orgService) {
        this.orgService = orgService;
    }

    @Override
    public String targetType() {
        return "ORG";
    }

    @Override
    public ProposalExecutionResult execute(AiOperationProposal proposal, ProposalRequest request,
                                           ProposalExecutionContext context) {
        return switch (proposal.getAction()) {
            case "CREATE" -> create(request, context);
            case "UPDATE" -> update(proposal, request, context);
            case "ENABLE", "DISABLE" -> changeStatus(proposal, context);
            case "DELETE" -> delete(proposal, context);
            default -> ProposalExecutionResult.failed("机构不支持的动作: " + proposal.getAction());
        };
    }

    private ProposalExecutionResult create(ProposalRequest request, ProposalExecutionContext context) {
        OrgDto.CreateRequest dto = new OrgDto.CreateRequest();
        dto.setOrgCode(request.orgCode());
        dto.setOrgName(request.orgName());
        dto.setRegionCode(request.regionCode());
        dto.setRegionName(request.regionName());
        dto.setOrgLevel(request.orgLevel());
        dto.setParentId(request.parentId());
        dto.setSortNo(request.sortNo());
        var created = orgService.create(dto, context.scope());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("orgCode", created.getOrgCode());
        after.put("orgName", created.getOrgName());
        after.put("orgLevel", created.getOrgLevel());
        after.put("parentId", created.getParentId());
        after.put("status", created.getStatus());
        return ProposalExecutionResult.ok(
                "机构「" + created.getOrgName() + "」已新增（" + OrgService.levelName(created.getOrgLevel())
                        + "，id=" + created.getId() + "）", Map.of(), after,
                List.of(),
                List.of("新增机构会改变数据范围边界，请确认上级机构与层级正确"));
    }

    private ProposalExecutionResult update(AiOperationProposal proposal, ProposalRequest request,
                                           ProposalExecutionContext context) {
        SysOrg existing = orgService.getEntityById(proposal.getTargetId());
        OrgDto.UpdateRequest dto = new OrgDto.UpdateRequest();
        dto.setOrgName(request.orgName());
        dto.setRegionCode(request.regionCode());
        dto.setRegionName(request.regionName());
        dto.setParentId(request.parentId());
        dto.setOrgLevel(request.orgLevel());
        dto.setSortNo(request.sortNo());
        var updated = orgService.update(proposal.getTargetId(), dto, context.scope());

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("orgName", existing.getOrgName());
        before.put("regionCode", existing.getRegionCode());
        before.put("parentId", existing.getParentId());
        before.put("orgLevel", existing.getOrgLevel());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("orgName", updated.getOrgName());
        after.put("regionCode", updated.getRegionCode());
        after.put("parentId", updated.getParentId());
        after.put("orgLevel", updated.getOrgLevel());
        return ProposalExecutionResult.ok("机构「" + updated.getOrgName() + "」已更新", before, after);
    }

    private ProposalExecutionResult changeStatus(AiOperationProposal proposal,
                                                 ProposalExecutionContext context) {
        SysOrg existing = orgService.getEntityById(proposal.getTargetId());
        int target = "ENABLE".equals(proposal.getAction()) ? 1 : 0;
        var updated = orgService.changeStatus(proposal.getTargetId(), target, context.scope());
        Map<String, Object> before = Map.of("status", existing.getStatus());
        Map<String, Object> after = Map.of("status", updated.getStatus());
        String word = target == 1 ? "启用" : "停用";
        Map<String, Object> impact = orgService.stopImpact(existing);
        return ProposalExecutionResult.ok(
                "机构「" + updated.getOrgName() + "」已" + word, before, after,
                List.of(),
                List.of("影响面：" + ProposalPreview.formatImpact(impact)));
    }

    /**
     * 机构逻辑删除（LD-01 / 设计 §6.2）。
     *
     * <p><b>执行期必须重做删除前置检查</b>（SYS-C-05）："有没有下级机构 / 部门 / 用户 / 关联订单"
     * 会随他人操作变化——生成提案时没有引用，确认时可能已经有人挂了一个部门上来。
     * 这里显式重查一次再调用 {@code delete(...)}，后者内部还会再查一次：
     * 多查一次计数换的是"被引用即拒绝、并把引用数量写在失败信息里"的确定性。</p>
     *
     * <p>注意删除**不改变 status**（LD-02），因此 before/after 只记录 {@code isDeleted}。</p>
     */
    private ProposalExecutionResult delete(AiOperationProposal proposal,
                                           ProposalExecutionContext context) {
        SysOrg existing = orgService.getEntityById(proposal.getTargetId());
        List<String> blockers = orgService.deleteBlockers(existing);
        if (!blockers.isEmpty()) {
            throw new com.guarantee.common.exception.BizException(
                    "该机构不能删除：" + String.join("；", blockers)
                            + "。如只需暂停业务，请改用「停用」。");
        }
        var deleted = orgService.delete(proposal.getTargetId(), context.scope(), context.userId());
        Map<String, Object> before = Map.of("isDeleted", 0);
        Map<String, Object> after = Map.of("isDeleted", 1);
        return ProposalExecutionResult.ok(
                "机构「" + deleted.getOrgName() + "」已删除（默认不再出现在列表中，"
                        + "可在「显示已删除」中恢复）", before, after,
                List.of(),
                List.of("删除不改变启用/停用状态，恢复后回到删除前的状态（LD-02）"));
    }
}
