package com.guarantee.ai.tool.write;

import com.guarantee.ai.service.ProposalPreview;
import com.guarantee.ai.service.ProposalRequest;
import com.guarantee.ai.service.ProposalService;
import com.guarantee.ai.tool.AiDataScopeResolver;
import com.guarantee.ai.tool.AiPermissionGuard;
import com.guarantee.common.exception.BizException;
import com.guarantee.common.security.Permissions;
import com.guarantee.system.dto.UserDto;
import com.guarantee.system.entity.SysUser;
import com.guarantee.system.service.UserService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code proposeUserChange}（SYS-W-04）：用户变更提案。本方法不落库。
 *
 * <p><b>D-2 收敛</b>：只支持 UPDATE / ENABLE / DISABLE / ASSIGN_ROLES / DELETE。
 * 用户要求"新建账号"或"重置密码"时**明确不支持**并给出替代路径（SYS-N-10 / AC-13），
 * 不生成提案、不落任何记录。</p>
 *
 * <p><b>危险动作保护</b>在预检阶段即生效（停用自己 / 停用最后一个 ADMIN /
 * 给自己加 ADMIN / 移除最后一个 ADMIN 的 ADMIN 角色 / 删除自己 / 删除最后一个启用 ADMIN），
 * 执行期会重新判定一遍（SYS-C-05）。</p>
 */
@Component
public class UserProposalTool extends BaseProposalTool {

    /** D-2 明确不支持的动作用户话术。 */
    private static final String UNSUPPORTED_CREATE =
            "本期不支持通过助手新建用户账号。新建账号涉及初始密码生成与线下安全分发，"
                    + "已作为独立能力立项（见需求 D-2/D-2a）。请在「系统管理 → 用户配置」页面或联系管理员处理。";
    private static final String UNSUPPORTED_RESET =
            "本期不支持通过助手重置密码。密码类操作需要独立的高保障渠道与责任链，"
                    + "已作为独立能力立项（见需求 D-2/D-2a）。请在「系统管理 → 用户配置」页面或联系管理员处理。";

    private final UserService userService;

    public UserProposalTool(ProposalService proposalService, AiDataScopeResolver scopeResolver,
                            UserService userService) {
        super(proposalService, scopeResolver);
        this.userService = userService;
    }

    @Tool(name = "proposeUserChange",
            description = """
                    提交一个【用户变更提案】。本工具**不会立即修改数据**，只生成待确认提案，
                    用户点击「确认执行」后才生效。
                    支持的动作：
                    - UPDATE：修改资料（必填 id；可改 realName / phone / email / deptId）
                    - DISABLE / ENABLE：停用/启用（必填 id）
                    - ASSIGN_ROLES：角色分配（必填 id 与 roleCodes，例如 ["ANALYST","VIEWER"]）
                    - DELETE：删除用户（必填 id）。**与停用完全不同**：停用=暂停业务、可随时启用、
                      数据仍在默认列表中；删除=从默认列表移除、需显式恢复才会重新出现。
                    **不支持**（必须明确告知用户"本期不支持"，不要生成提案）：
                    - 新建用户账号（CREATE）
                    - 重置密码（RESET_PASSWORD）
                    - 恢复已删除用户（RESTORE）：请引导用户到页面用「显示已删除」操作
                    重要规则：
                    - username 不可修改；不允许修改自己的所属部门。
                    - 禁止停用自己；禁止停用最后一个启用状态的超级管理员（ADMIN）。
                    - 禁止删除自己；禁止删除最后一个启用状态的超级管理员（ADMIN）。
                    - 禁止给自己增加或移除 ADMIN 角色；禁止移除最后一个启用 ADMIN 的 ADMIN 角色。
                    - 停用与角色分配会让该用户**立即被强制下线**、需要重新登录，请在确认卡上明示。
                    - 删除同样会**立即强制下线**（否则其登录状态最长还能持续 12 小时，等于没删），
                      且删除后该账号无法登录，登录失败提示与密码错误完全一致（防账号枚举）。
                    - 删除属**危险动作**，确认卡上有二次确认；删除**不改变启用/停用状态**，
                      恢复后回到删除前的状态。删除后可恢复（「显示已删除」）。
                    - 用户说"停用/暂停/禁用"时用 DISABLE，**不要**用 DELETE；用户说"删掉这个账号"时
                      必须先与用户确认是"删除"还是"停用"，不得自行降级或升格。
                    - 用户名命中多个目标时返回值会给出 ambiguousTargets 候选，必须先让用户确认。
                    示例：把 user0123 停用 → action=DISABLE, id=123（id 先用 queryUser 查）。""")
    public WriteToolResult proposeUserChange(
            @ToolParam(description = "动作：UPDATE / DISABLE / ENABLE / ASSIGN_ROLES / DELETE。"
                    + "DELETE=逻辑删除（从默认列表移除、可恢复、账号无法登录），"
                    + "与 DISABLE=停用（暂停业务、可随时启用）语义不同",
                    required = true)
            String action,
            @ToolParam(description = "目标用户 ID。例如 123", required = false)
            Long id,
            @ToolParam(description = "用户账号，用于在缺 id 时解析目标。例如 user0123", required = false)
            String username,
            @ToolParam(description = "真实姓名（UPDATE）", required = false)
            String realName,
            @ToolParam(description = "手机号（UPDATE），11 位。例如 13812345678", required = false)
            String phone,
            @ToolParam(description = "邮箱（UPDATE）。例如 analyst@guarantee.com", required = false)
            String email,
            @ToolParam(description = "所属部门 ID（UPDATE）。不允许修改自己的部门", required = false)
            Long deptId,
            @ToolParam(description = "变更后的角色编码列表（ASSIGN_ROLES），例如 [\"ANALYST\"]", required = false)
            List<String> roleCodes,
            @ToolParam(description = "用户的原话，用于确认卡上核对模型理解是否正确", required = false)
            String userText,
            ToolContext toolContext) {

        String normalized = action == null ? "" : action.trim().toUpperCase();

        // D-2：不支持的诉求必须明确回复，且不生成任何提案或工具记录
        if ("CREATE".equals(normalized)) {
            return WriteToolResult.failed(UNSUPPORTED_CREATE);
        }
        if ("RESET_PASSWORD".equals(normalized) || "RESETPASSWORD".equals(normalized)) {
            return WriteToolResult.failed(UNSUPPORTED_RESET);
        }
        // 恢复（RESTORE）本期只在页面的「显示已删除」中提供：助手侧不生成恢复提案，
        // 但仍要给用户可执行的替代路径，而不是一句"不支持"（设计 §7.4 只要求 DELETE）。
        if ("RESTORE".equals(normalized)) {
            return WriteToolResult.failed(UNSUPPORTED_RESTORE);
        }

        Set<String> required = requiredPermissions(normalized);
        if (!allowed(toolContext, required.toArray(String[]::new))) {
            return WriteToolResult.denied(AiPermissionGuard.deniedReason(required.toArray(String[]::new)));
        }

        Set<String> effectiveRequired = new LinkedHashSet<>(required);
        var scope = scope(toolContext);
        Long targetId = id;
        String targetName = username;

        if (targetId == null && username != null && !username.isBlank()) {
            UserDto.Query query = new UserDto.Query();
            query.setKeyword(username);
            query.setPageNum(1);
            query.setPageSize(10);
            var page = userService.page(query, scope);
            if (page.list().isEmpty()) {
                return WriteToolResult.failed("没有找到账号或姓名匹配「" + username + "」的用户"
                        + "（也可能不在你的数据范围内）。请先用 queryUser 确认目标。");
            }
            if (page.list().size() > 1) {
                List<TargetCandidate> list = new ArrayList<>();
                for (var candidate : page.list()) {
                    list.add(new TargetCandidate(candidate.getId(), candidate.getUsername(),
                            candidate.getRealName(), candidate.getDeptName()));
                }
                return WriteToolResult.ambiguous(list,
                        "用户关键字命中多个目标，请把候选列给用户确认后再调用本工具，不要自行选择");
            }
            targetId = page.list().get(0).getId();
            targetName = page.list().get(0).getUsername();
        }
        if (targetId == null) {
            return WriteToolResult.failed("缺少目标用户 id，且账号无法唯一定位。请先用 queryUser 查询用户 id。");
        }

        ProposalRequest request = ProposalRequest.builder()
                .id(targetId).targetName(targetName).userText(userText)
                .realName(realName).phone(phone).email(email)
                .deptId(deptId).roleCodes(roleCodes)
                .build();

        // 敏感值（phone / email）不落提案表，只加密暂存（SYS-A-09）
        Map<String, Object> secrets = new LinkedHashMap<>();
        if (phone != null && !phone.isBlank()) {
            secrets.put("phone", phone.trim());
        }
        if (email != null && !email.isBlank()) {
            secrets.put("email", email.trim());
        }

        try {
            PreviewResult built = buildPreview(normalized, targetId, request, scope,
                    AiPermissionGuard.userId(toolContext));
            // 模型只传 id 时 targetName 一直是 null，必须用 buildPreview 已加载的实体名（username）回填，
            // 否则落库的 target_name 为 NULL，确认卡上就丢了目标名
            String resolvedTargetName = resolveTargetName(targetName, built.targetName());
            return submit(toolContext, draft(toolContext, "proposeUserChange", normalized,
                    "USER", targetId, resolvedTargetName, request, built.preview(), effectiveRequired,
                    userText, secrets.isEmpty() ? null : secrets));
        } catch (BizException ex) {
            return WriteToolResult.failed("无法生成提案：" + ex.getMessage());
        }
    }

    private PreviewResult buildPreview(String action, Long targetId, ProposalRequest request,
                                         com.guarantee.system.scope.DataScope scope,
                                         Long operatorUserId) {
        SysUser existing = userService.requireVisible(targetId, scope);
        // 目标名回填：模型常只传 id 不传名字，这里直接用上面已加载的实体取名（不再查库）；
        // 用户的名字字段是 username，不填就落 NULL，确认卡上「— 目标名」这一整段会消失
        String existingName = existing == null ? null : existing.getUsername();

        if ("UPDATE".equals(action)) {
            UserDto.UpdateRequest dto = new UserDto.UpdateRequest();
            dto.setRealName(request.realName());
            dto.setPhone(request.phone());
            dto.setEmail(request.email());
            dto.setDeptId(request.deptId());
            // 预检：格式校验 + 不得修改自己的部门
            userService.validateUpdateProfile(targetId, dto, scope, operatorUserId);

            List<ProposalPreview.ChangeItem> changes = new ArrayList<>();
            addIfChanged(changes, "realName", "姓名", existing.getRealName(), request.realName());
            // D-4：敏感字段在确认卡上也不展示具体值，只提示"将变更"
            if (request.phone() != null && !request.phone().isBlank()) {
                changes.add(new ProposalPreview.ChangeItem("phone", "手机号",
                        mask(existing.getPhone()), "将变更为新值（审计中只记录是否变更，不记录具体值）"));
            }
            if (request.email() != null && !request.email().isBlank()) {
                changes.add(new ProposalPreview.ChangeItem("email", "邮箱",
                        mask(existing.getEmail()), "将变更为新值（审计中只记录是否变更，不记录具体值）"));
            }
            addIfChanged(changes, "deptId", "所属部门", existing.getDeptId(), request.deptId());
            if (changes.isEmpty()) {
                throw new BizException("没有任何字段发生变化，无需提交提案");
            }
            List<String> warnings = new ArrayList<>();
            if (request.phone() != null || request.email() != null) {
                warnings.add("手机号/邮箱属于敏感字段：审计只记录\"是否变更\"，不记录具体值（D-4）");
            }
            return new PreviewResult(ProposalPreview.of("修改用户资料：" + existing.getUsername(), changes,
                    List.of(), warnings, false), existingName);
        }

        if ("ASSIGN_ROLES".equals(action)) {
            List<String> currentRoles = userService.listRoleCodesByUserId(targetId);
            List<String> targetRoles = userService.validateAssignRoles(existing, request.roleCodes(),
                    operatorUserId);
            // 确认卡的"原值/新值"是**给业务用户看的正文**，必须显示中文角色名
            // （ADMIN → 超级管理员）。显示编码会与同屏正文里的中文名自相矛盾。
            List<ProposalPreview.ChangeItem> changes = List.of(new ProposalPreview.ChangeItem(
                    "roleCodes", "角色",
                    String.join("，", userService.roleDisplayNames(currentRoles)),
                    String.join("，", userService.roleDisplayNames(targetRoles))));
            Map<String, Object> impact = userService.assignRolesImpact(existing, targetRoles);
            List<String> warnings = new ArrayList<>();
            warnings.add("角色分配会立即改变该用户的权限");
            return new PreviewResult(ProposalPreview.of("角色分配：" + existing.getUsername(), changes,
                    List.of("影响面：" + ProposalPreview.formatImpact(impact)), warnings, true),
                    existingName);
        }

        // DELETE（逻辑删除，LD-01 / 设计 §7.4）
        // 与 DISABLE 严格区分：删除的前置检查**不弱于停用**，且删除后会撤销令牌（§6.3）
        if ("DELETE".equals(action)) {
            List<String> blockers = userService.deleteBlockers(existing, operatorUserId);
            if (!blockers.isEmpty()) {
                throw new BizException("该用户不能删除：" + String.join("；", blockers));
            }
            List<ProposalPreview.ChangeItem> changes = List.of(new ProposalPreview.ChangeItem(
                    "isDeleted", "是否已删除", "否", "是"));
            Map<String, Object> impactMap = userService.deleteImpact(existing);
            List<String> impact = new ArrayList<>();
            impact.add("该用户当前持有角色：" + ProposalPreview.formatImpactValue(impactMap.get("持有角色")));
            impact.add("删除后该用户**无法登录**，且登录失败提示与密码错误完全一致（防账号枚举）");
            impact.add("该用户会被**立即强制下线**，需要重新登录");
            return new PreviewResult(ProposalPreview.of("删除用户：" + existing.getUsername(), changes,
                    impact,
                    List.of("删除后该用户**默认不再出现在列表中**，可通过「显示已删除」恢复",
                            "删除**不改变启用/停用状态**，恢复后回到删除前的状态",
                            "删除属危险动作，需二次确认"),
                    true), existingName);
        }

        // DISABLE / ENABLE
        int targetStatus = "ENABLE".equals(action) ? 1 : 0;
        userService.validateStatusChange(existing, targetStatus, operatorUserId);
        List<ProposalPreview.ChangeItem> changes = List.of(new ProposalPreview.ChangeItem(
                "status", "状态", statusName(existing.getStatus()), statusName(targetStatus)));
        List<String> impact = new ArrayList<>();
        Map<String, Object> impactMap = userService.stopImpact(existing);
        impact.add("该用户当前持有角色：" + ProposalPreview.formatImpactValue(impactMap.get("持有角色")));
        if (targetStatus == 0) {
            impact.add("该用户未完结的 AI 会话将失效；该用户会被立即强制下线，需要重新登录");
        }
        return new PreviewResult(ProposalPreview.of(
                (targetStatus == 0 ? "停用用户：" : "启用用户：") + existing.getUsername(),
                changes, impact,
                targetStatus == 0 ? List.of("停用会立即中断该用户的访问能力，请二次确认") : List.of(),
                targetStatus == 0), existingName);
    }

    /** D-4：确认卡上展示的旧值同样掩码，避免界面成为明文泄漏路径。 */
    private static String mask(String value) {
        return com.guarantee.common.security.SensitiveFieldMasker.maskValue("phone", value);
    }

    private static Set<String> requiredPermissions(String action) {
        Set<String> required = new LinkedHashSet<>();
        required.add(Permissions.AI_SYSTEM_WRITE);
        switch (action) {
            case "UPDATE" -> required.add(Permissions.USER_UPDATE);
            case "ENABLE", "DISABLE" -> required.add(Permissions.USER_DISABLE);
            case "ASSIGN_ROLES" -> required.add(Permissions.USER_ASSIGN_ROLE);
            case "DELETE" -> required.add(Permissions.USER_DELETE);
            default -> required.add(Permissions.USER_UPDATE);
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
