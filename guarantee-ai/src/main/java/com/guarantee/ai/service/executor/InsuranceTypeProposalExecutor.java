package com.guarantee.ai.service.executor;

import com.guarantee.ai.entity.AiOperationProposal;
import com.guarantee.ai.service.ProposalExecutionContext;
import com.guarantee.ai.service.ProposalExecutionResult;
import com.guarantee.ai.service.ProposalExecutor;
import com.guarantee.ai.service.ProposalRequest;
import com.guarantee.system.service.InsuranceTypeService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 险种提案执行器（SYS-W-01）。
 *
 * <p>执行期重新做全部校验（SYS-C-05）：目标是否存在、费率区间、编码唯一性、
 * 分类变更是否影响历史口径、删除前是否被订单引用——不能沿用生成提案时的结论。</p>
 */
@Component
public class InsuranceTypeProposalExecutor implements ProposalExecutor {

    private final InsuranceTypeService insuranceTypeService;

    public InsuranceTypeProposalExecutor(InsuranceTypeService insuranceTypeService) {
        this.insuranceTypeService = insuranceTypeService;
    }

    @Override
    public String targetType() {
        return "INSURANCE_TYPE";
    }

    @Override
    public ProposalExecutionResult execute(AiOperationProposal proposal, ProposalRequest request,
                                           ProposalExecutionContext context) {
        return switch (proposal.getAction()) {
            case "CREATE" -> create(request);
            case "UPDATE" -> update(proposal, request);
            case "ENABLE", "DISABLE" -> changeStatus(proposal, request);
            case "DELETE" -> delete(proposal, context);
            default -> ProposalExecutionResult.failed("险种不支持的动作: " + proposal.getAction());
        };
    }

    private ProposalExecutionResult create(ProposalRequest request) {
        var dto = new com.guarantee.system.dto.InsuranceTypeDto.CreateRequest();
        dto.setTypeCode(request.typeCode());
        dto.setTypeName(request.typeName());
        dto.setCategory(request.category());
        dto.setBaseRate(request.baseRate());
        dto.setMinAmount(request.minAmount());
        dto.setMaxAmount(request.maxAmount());
        dto.setDescription(request.description());
        var created = insuranceTypeService.create(dto);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("typeCode", created.typeCode());
        after.put("typeName", created.typeName());
        after.put("category", created.category());
        after.put("baseRate", created.baseRate() == null ? null : created.baseRate().toPlainString());
        after.put("status", created.status());
        return ProposalExecutionResult.ok(
                "险种「" + created.typeName() + "」已新增（id=" + created.id() + "）", Map.of(), after);
    }

    private ProposalExecutionResult update(AiOperationProposal proposal, ProposalRequest request) {
        var existing = insuranceTypeService.getById(proposal.getTargetId());
        var dto = new com.guarantee.system.dto.InsuranceTypeDto.UpdateRequest();
        dto.setTypeName(request.typeName());
        dto.setCategory(request.category());
        dto.setBaseRate(request.baseRate());
        dto.setMinAmount(request.minAmount());
        dto.setMaxAmount(request.maxAmount());
        dto.setDescription(request.description());
        var updated = insuranceTypeService.update(proposal.getTargetId(), dto);

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("typeName", existing.typeName());
        before.put("category", existing.category());
        before.put("baseRate", existing.baseRate() == null ? null : existing.baseRate().toPlainString());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("typeName", updated.typeName());
        after.put("category", updated.category());
        after.put("baseRate", updated.baseRate() == null ? null : updated.baseRate().toPlainString());
        return ProposalExecutionResult.ok(
                "险种「" + updated.typeName() + "」已更新（费率 "
                        + (updated.baseRate() == null ? "未变" : updated.baseRate().toPlainString()) + "）",
                before, after);
    }

    private ProposalExecutionResult changeStatus(AiOperationProposal proposal, ProposalRequest request) {
        var existing = insuranceTypeService.getById(proposal.getTargetId());
        int target = "ENABLE".equals(proposal.getAction()) ? 1 : 0;
        var updated = insuranceTypeService.changeStatus(proposal.getTargetId(), target);
        Map<String, Object> before = Map.of("status", existing.status());
        Map<String, Object> after = Map.of("status", updated.status());
        String word = target == 1 ? "启用" : "停用";
        return ProposalExecutionResult.ok(
                "险种「" + updated.typeName() + "」已" + word, before, after,
                java.util.List.of(),
                java.util.List.of("停用后该险种不再出现在新订单的可选列表中，历史订单不受影响"));
    }

    /**
     * 险种逻辑删除（LD-01 / 设计 §6.2）。
     *
     * <p><b>与停用的关键差异</b>：停用只提示被引用订单数、不禁；删除**被引用即拒绝**
     * ——删除后历史订单会指向一条"不存在"的险种。因此执行期必须重新统计引用数
     * （订单随时可能新增），并把数量写进失败信息，同时给出"改用停用"的替代路径。</p>
     */
    private ProposalExecutionResult delete(AiOperationProposal proposal,
                                           ProposalExecutionContext context) {
        var existing = insuranceTypeService.getEntityById(proposal.getTargetId());
        java.util.List<String> blockers = insuranceTypeService.deleteBlockers(existing);
        if (!blockers.isEmpty()) {
            throw new com.guarantee.common.exception.BizException(
                    "该险种不能删除：" + String.join("；", blockers)
                            + "。如只需暂停业务，请改用「停用」。");
        }
        var deleted = insuranceTypeService.delete(proposal.getTargetId(), context.userId());
        Map<String, Object> before = Map.of("isDeleted", 0);
        Map<String, Object> after = Map.of("isDeleted", 1);
        return ProposalExecutionResult.ok(
                "险种「" + deleted.typeName() + "」已删除（默认不再出现在列表中，"
                        + "可在「显示已删除」中恢复）", before, after,
                java.util.List.of(),
                java.util.List.of("删除不改变启用/停用状态，恢复后回到删除前的状态（LD-02）；"
                        + "恢复时要求险种编码未被有效险种占用"));
    }
}
