package com.guarantee.ai.tool.write;

import com.guarantee.ai.service.ProposalPreview;
import com.guarantee.ai.service.ProposalRequest;
import com.guarantee.ai.service.ProposalService;
import com.guarantee.ai.tool.AiDataScopeResolver;
import com.guarantee.ai.tool.AiPermissionGuard;
import com.guarantee.common.exception.BizException;
import com.guarantee.common.security.Permissions;
import com.guarantee.system.dto.InsuranceTypeDto;
import com.guarantee.system.entity.InsuranceType;
import com.guarantee.system.service.InsuranceTypeService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * {@code proposeInsuranceTypeChange}（SYS-W-01）：险种变更提案。本方法不落库。
 */
@Component
public class InsuranceTypeProposalTool extends BaseProposalTool {

    private final InsuranceTypeService insuranceTypeService;

    public InsuranceTypeProposalTool(ProposalService proposalService, AiDataScopeResolver scopeResolver,
                                     InsuranceTypeService insuranceTypeService) {
        super(proposalService, scopeResolver);
        this.insuranceTypeService = insuranceTypeService;
    }

    @Tool(name = "proposeInsuranceTypeChange",
            description = """
                    提交一个【险种变更提案】。本工具**不会立即修改数据**，只生成待确认提案，
                    用户点击「确认执行」后才生效。
                    支持的动作：
                    - CREATE：新增险种（必填 typeCode / typeName / category / baseRate；选填 minAmount / maxAmount / description）
                    - UPDATE：修改险种（必填 id；可改 typeName / category / baseRate / minAmount / maxAmount / description）
                    - DISABLE / ENABLE：险种停用/启用（必填 id）
                    - DELETE：删除险种（必填 id）。**与停用完全不同**：停用=暂停业务、可随时启用、
                      数据仍在默认列表中；删除=从默认列表移除、需显式恢复才会重新出现。
                    重要规则：
                    - category 只能是 TENDER（投标）/ PERFORMANCE（履约）/ OTHER。
                    - baseRate 是**小数**形式的基准费率，必须落在 (0, 0.1] 区间；例如 0.013 表示 1.3%。
                      不要传百分数（传 1.3 会被服务端拒绝）。
                    - typeCode 不可修改；minAmount 必须小于 maxAmount。
                    - 已产生订单的险种禁止修改 category（会影响历史口径）。
                    - 停用前系统会给出被引用订单数，请在确认卡上明示（停用被引用**不禁止**）。
                    - 删除与停用的关键差异：**被订单引用时删除会被拒绝**（这是设计刻意的差异，
                      因为删除后历史订单会指向一条不存在的险种）。失败信息会带引用订单数，
                      请如实转述并建议"这些订单需要先处理，或改用停用"。
                    - 删除属**危险动作**，确认卡上有二次确认；删除**不改变启用/停用状态**，
                      恢复后回到删除前的状态。删除后可恢复（「显示已删除」）。
                    - 用户说"停用/禁用/下架"时用 DISABLE，**不要**用 DELETE；用户说"删掉这个险种"时
                      必须先与用户确认是"删除"还是"停用"。
                    示例：把履约保函（标准）的基准费率改成 0.013 → action=UPDATE, id=4, baseRate=0.013。""")
    public WriteToolResult proposeInsuranceTypeChange(
            @ToolParam(description = "动作：CREATE / UPDATE / DISABLE / ENABLE / DELETE。"
                    + "DELETE=逻辑删除（从默认列表移除、可恢复；被订单引用时会被拒绝），"
                    + "与 DISABLE=停用（暂停业务、可随时启用）语义不同",
                    required = true)
            String action,
            @ToolParam(description = "目标险种 ID；UPDATE / DISABLE / ENABLE / DELETE 必填。例如 4",
                    required = false)
            Long id,
            @ToolParam(description = "险种名称，用于在缺 id 时解析目标。例如 履约保函（标准）", required = false)
            String typeName,
            @ToolParam(description = "险种编码，仅 CREATE 必填。例如 PERF_STD", required = false)
            String typeCode,
            @ToolParam(description = "险种分类：TENDER / PERFORMANCE / OTHER", required = false)
            String category,
            @ToolParam(description = "基准费率（小数），取值区间 (0, 0.1]。例如 0.013 表示 1.3%", required = false)
            BigDecimal baseRate,
            @ToolParam(description = "最小保额", required = false)
            BigDecimal minAmount,
            @ToolParam(description = "最大保额", required = false)
            BigDecimal maxAmount,
            @ToolParam(description = "描述", required = false)
            String description,
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

        Long targetId = id;
        String targetName = typeName;

        if (targetId == null && typeName != null && !typeName.isBlank() && !"CREATE".equals(normalized)) {
            List<InsuranceType> candidates = insuranceTypeService.findCandidates(
                    typeName, 20);
            if (candidates.isEmpty()) {
                return WriteToolResult.failed("没有找到名称或编码包含「" + typeName + "」的险种。"
                        + "请先用 queryInsuranceType 查询。");
            }
            if (candidates.size() > 1) {
                List<TargetCandidate> list = new ArrayList<>();
                for (InsuranceType candidate : candidates) {
                    list.add(new TargetCandidate(candidate.getId(), candidate.getTypeCode(),
                            candidate.getTypeName(), candidate.getCategory()));
                }
                return WriteToolResult.ambiguous(list,
                        "险种名称命中多个目标，请把候选列给用户确认后再调用本工具，不要自行选择");
            }
            targetId = candidates.get(0).getId();
            targetName = candidates.get(0).getTypeName();
        }
        if (!"CREATE".equals(normalized) && targetId == null) {
            return WriteToolResult.failed("缺少目标险种 id，且名称无法唯一定位。"
                    + "请先用 queryInsuranceType 查询险种 id。");
        }

        ProposalRequest request = ProposalRequest.builder()
                .id(targetId).targetName(targetName).userText(userText)
                .typeCode(typeCode).typeName(typeName).category(category)
                .baseRate(baseRate).minAmount(minAmount).maxAmount(maxAmount)
                .description(description)
                .build();

        try {
            PreviewResult built = buildPreview(normalized, targetId, request);
            // 模型只传 id 时 targetName 一直是 null，必须用 buildPreview 已加载的实体名回填，
            // 否则落库的 target_name 为 NULL，确认卡上就丢了目标名
            String resolvedTargetName = resolveTargetName(targetName, built.targetName());
            Long proposalTargetId = "CREATE".equals(normalized) ? null : targetId;
            return submit(toolContext, draft(toolContext, "proposeInsuranceTypeChange", normalized,
                    "INSURANCE_TYPE", proposalTargetId, resolvedTargetName, request, built.preview(),
                    required, userText, null));
        } catch (BizException ex) {
            return WriteToolResult.failed("无法生成提案：" + ex.getMessage());
        }
    }

    private PreviewResult buildPreview(String action, Long targetId, ProposalRequest request) {
        if ("CREATE".equals(action)) {
            InsuranceTypeDto.CreateRequest dto = new InsuranceTypeDto.CreateRequest();
            dto.setTypeCode(request.typeCode());
            dto.setTypeName(request.typeName());
            dto.setCategory(request.category());
            dto.setBaseRate(request.baseRate());
            dto.setMinAmount(request.minAmount());
            dto.setMaxAmount(request.maxAmount());
            dto.setDescription(request.description());
            insuranceTypeService.validateCreate(dto);
            List<ProposalPreview.ChangeItem> changes = List.of(
                    ProposalPreview.ChangeItem.created("typeCode", "险种编码", request.typeCode()),
                    ProposalPreview.ChangeItem.created("typeName", "险种名称", request.typeName()),
                    ProposalPreview.ChangeItem.created("category", "分类", request.category()),
                    ProposalPreview.ChangeItem.created("baseRate", "基准费率",
                            percent(request.baseRate())),
                    ProposalPreview.ChangeItem.created("minAmount", "最小保额",
                            plain(request.minAmount())),
                    ProposalPreview.ChangeItem.created("maxAmount", "最大保额",
                            plain(request.maxAmount())));
            // CREATE 没有既有实体可回填，名字仍取模型传入的 typeName（语义不变）
            return new PreviewResult(ProposalPreview.of("新增险种：" + request.typeName(), changes,
                    List.of(), List.of("费率将影响后续新订单的保费计算，请核对口径"), false), null);
        }

        InsuranceType existing = insuranceTypeService.getEntityById(targetId);
        // 目标名回填：模型常只传 id 不传名字，这里直接用上面已加载的实体取名（不再查库），
        // 否则 target_name 落 NULL，确认卡上「— 目标名」这一整段会消失
        String existingName = existing == null ? null : existing.getTypeName();
        if ("UPDATE".equals(action)) {
            InsuranceTypeDto.UpdateRequest dto = new InsuranceTypeDto.UpdateRequest();
            dto.setTypeName(request.typeName());
            dto.setCategory(request.category());
            dto.setBaseRate(request.baseRate());
            dto.setMinAmount(request.minAmount());
            dto.setMaxAmount(request.maxAmount());
            dto.setDescription(request.description());
            insuranceTypeService.validateUpdate(targetId, dto);

            List<ProposalPreview.ChangeItem> changes = new ArrayList<>();
            addIfChanged(changes, "typeName", "险种名称", existing.getTypeName(), request.typeName());
            addIfChanged(changes, "category", "分类", existing.getCategory(), request.category());
            // 费率必须同时给出小数与百分比，避免用户看错数量级
            if (request.baseRate() != null
                    && existing.getBaseRate().compareTo(request.baseRate()) != 0) {
                changes.add(new ProposalPreview.ChangeItem("baseRate", "基准费率",
                        existing.getBaseRate().toPlainString() + "（" + percent(existing.getBaseRate()) + "）",
                        request.baseRate().toPlainString() + "（" + percent(request.baseRate()) + "）"));
            }
            addIfChanged(changes, "minAmount", "最小保额", plain(existing.getMinAmount()),
                    plain(request.minAmount()));
            addIfChanged(changes, "maxAmount", "最大保额", plain(existing.getMaxAmount()),
                    plain(request.maxAmount()));
            addIfChanged(changes, "description", "描述", existing.getDescription(),
                    request.description());
            if (changes.isEmpty()) {
                throw new BizException("没有任何字段发生变化，无需提交提案");
            }
            List<String> warnings = new ArrayList<>();
            if (request.baseRate() != null) {
                warnings.add("费率变更只影响变更之后的新订单，历史订单保费不变");
            }
            return new PreviewResult(ProposalPreview.of("修改险种：" + existing.getTypeName(), changes,
                    List.of(), warnings, false), existingName);
        }

        // DELETE（逻辑删除，LD-01 / 设计 §7.4）
        // 这里最能体现"删除 ≠ 停用"：停用被引用只是提示，删除被引用直接拒绝（§6.2）
        if ("DELETE".equals(action)) {
            List<String> blockers = insuranceTypeService.deleteBlockers(existing);
            if (!blockers.isEmpty()) {
                throw new BizException("该险种不能删除：" + String.join("；", blockers)
                        + "。如只需暂停业务，请改用「停用」。");
            }
            List<ProposalPreview.ChangeItem> changes = List.of(new ProposalPreview.ChangeItem(
                    "isDeleted", "是否已删除", "否", "是"));
            List<String> impact = new ArrayList<>();
            impact.add("影响面："
                    + ProposalPreview.formatImpact(insuranceTypeService.deleteImpact(existing)));
            return new PreviewResult(ProposalPreview.of("删除险种：" + existing.getTypeName(), changes,
                    impact,
                    List.of("删除后该险种**默认不再出现在列表中**，可通过「显示已删除」恢复",
                            "删除**不改变启用/停用状态**，恢复后回到删除前的状态",
                            "与停用不同：被订单引用时删除会被拒绝",
                            "删除属危险动作，需二次确认"),
                    true), existingName);
        }

        int targetStatus = "ENABLE".equals(action) ? 1 : 0;
        List<ProposalPreview.ChangeItem> changes = List.of(new ProposalPreview.ChangeItem(
                "status", "状态", statusName(existing.getStatus()), statusName(targetStatus)));
        List<String> impact = List.of("影响面："
                + ProposalPreview.formatImpact(insuranceTypeService.stopImpact(existing)));
        return new PreviewResult(ProposalPreview.of(
                (targetStatus == 0 ? "停用险种：" : "启用险种：") + existing.getTypeName(),
                changes, impact,
                targetStatus == 0 ? List.of("停用后该险种不再出现在新订单的可选列表中") : List.of(),
                targetStatus == 0), existingName);
    }

    private static String percent(BigDecimal rate) {
        if (rate == null) {
            return null;
        }
        return rate.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%";
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static Set<String> requiredPermissions(String action) {
        Set<String> required = new LinkedHashSet<>();
        required.add(Permissions.AI_SYSTEM_WRITE);
        switch (action) {
            case "CREATE" -> required.add(Permissions.INSURANCE_CREATE);
            case "UPDATE" -> required.add(Permissions.INSURANCE_UPDATE);
            case "ENABLE", "DISABLE" -> required.add(Permissions.INSURANCE_DISABLE);
            case "DELETE" -> required.add(Permissions.INSURANCE_DELETE);
            default -> required.add(Permissions.INSURANCE_UPDATE);
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
        if (status == null) {
            return "未知";
        }
        return status == 1 ? "启用" : "停用";
    }
}
