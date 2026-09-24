package com.guarantee.ai.tool.write;

import com.guarantee.ai.service.ProposalPayload;
import com.guarantee.ai.service.ProposalPreview;
import com.guarantee.ai.service.ProposalService;
import com.guarantee.ai.tool.AiDataScopeResolver;
import com.guarantee.ai.tool.AiToolContextKeys;
import com.guarantee.common.security.Permissions;
import com.guarantee.system.entity.SysRole;
import com.guarantee.system.service.RoleService;
import com.guarantee.system.vo.RoleVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 角色授权确认卡的「权限」一列必须是**中文权限名**。
 *
 * <p>真机现场（截图）：卡片的「权限」一列铺着
 * {@code dashboard:view, order:tender:view, analysis:overview:view, project:view, enterprise:view, ai:chat}，
 * 影响面同样是编码——业务用户读不出这次到底授了什么。角色分配卡的同类问题已在决策四修掉，
 * 这里钉住授权卡的「原值/新值」与影响面（含风险提示点名的那一项）。</p>
 *
 * <p>判定仍全部按编码（{@code validateAssignPermissions} 等），因此本用例同时断言
 * 编码没有被"翻译"掉语义：查不到的中文名一律退回编码本身。</p>
 */
@ExtendWith(MockitoExtension.class)
class RoleProposalToolPermissionDisplayTest {

    private static final Map<String, String> DICT = Map.of(
            "dashboard:view", "数据概览",
            "order:tender:view", "投标订单查询",
            "ai:chat", "AI 助手对话",
            "system:audit:view", "全局操作审计");

    @Mock
    private ProposalService proposalService;
    @Mock
    private AiDataScopeResolver scopeResolver;
    @Mock
    private RoleService roleService;

    private RoleProposalTool tool() {
        return new RoleProposalTool(proposalService, scopeResolver, roleService);
    }

    private static ToolContext context() {
        return new ToolContext(Map.of(
                AiToolContextKeys.USER_ID, 1L,
                AiToolContextKeys.USERNAME, "admin",
                AiToolContextKeys.REAL_NAME, "超级管理员",
                AiToolContextKeys.CONVERSATION_ID, 484L,
                AiToolContextKeys.PERMISSIONS,
                List.of(Permissions.AI_SYSTEM_WRITE, Permissions.ROLE_ASSIGN_PERMISSION)));
    }

    /** 让工具内部的"中文名转换"用一份固定字典作答（真实实现由 RoleServicePermissionDisplayTest 覆盖）。 */
    private void stubDisplayNames() {
        when(roleService.permissionDisplayNames(anyList())).thenAnswer(invocation -> {
            List<String> codes = invocation.getArgument(0);
            return codes.stream().map(code -> DICT.getOrDefault(code, code)).toList();
        });
    }

    private void stubTarget(SysRole role, List<String> currentCodes) {
        when(roleService.findEntityById(role.getId())).thenReturn(role);
        RoleVO current = new RoleVO();
        current.setPermissionCodes(currentCodes);
        when(roleService.getById(role.getId())).thenReturn(current);
    }

    private static ProposalPayload payload() {
        return new ProposalPayload(1L, "OP202609250016423869", 484L,
                "proposeRoleChange", "ASSIGN_PERMISSIONS", "权限授权", "ROLE", "角色",
                425L, "业务运营（无系统配置）", "角色授权：业务运营（无系统配置）",
                List.of(), List.of(), List.of(), true, "给这个角色配业务权限",
                LocalDateTime.now().plusMinutes(15), "PENDING");
    }

    private static SysRole role() {
        SysRole role = new SysRole();
        role.setId(425L);
        role.setRoleCode("OPER_NO_SYS");
        role.setRoleName("业务运营（无系统配置）");
        return role;
    }

    @Test
    @DisplayName("「权限」一列与影响面显示中文名，不出现权限码")
    void assignPermissionsPreviewShowsChinesePermissionNames() {
        SysRole role = role();
        stubTarget(role, List.of());
        stubDisplayNames();
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("当前权限", List.of());
        impact.put("变更后权限", List.of("数据概览", "AI 助手对话"));
        impact.put("影响用户数", 0);
        impact.put("影响", "持有该角色的用户会被立即强制下线，需要重新登录（新权限随即生效）");
        when(roleService.assignPermissionsImpact(eq(role), anyList())).thenReturn(impact);
        when(proposalService.create(any())).thenReturn(payload());

        tool().proposeRoleChange("ASSIGN_PERMISSIONS", 425L, "OPER_NO_SYS", null, null,
                List.of("dashboard:view", "order:tender:view", "ai:chat"),
                "给这个角色配业务权限", context());

        ArgumentCaptor<ProposalService.ProposalDraft> captor =
                ArgumentCaptor.forClass(ProposalService.ProposalDraft.class);
        verify(proposalService).create(captor.capture());
        ProposalPreview preview = captor.getValue().preview();

        assertThat(preview.changes()).hasSize(1);
        ProposalPreview.ChangeItem permissionRow = preview.changes().get(0);
        assertThat(permissionRow.label()).isEqualTo("权限");
        assertThat(permissionRow.before()).as("原本没有权限时留空，不显示 - 之类的占位")
                .isEmpty();
        assertThat(permissionRow.after())
                .as("必须逐项显示中文名")
                .isEqualTo("数据概览，投标订单查询，AI 助手对话");
        assertThat(permissionRow.after())
                .as("不得再出现权限码")
                .doesNotContain("dashboard:view").doesNotContain("order:tender:view")
                .doesNotContain("ai:chat");

        assertThat(String.join("；", preview.impact()))
                .as("影响面同样必须中文化")
                .contains("数据概览").doesNotContain("dashboard:view");
    }

    @Test
    @DisplayName("风险提示点名的高危权限也用中文名（不再直接甩 system:audit:view）")
    void auditPermissionWarningUsesChineseName() {
        SysRole role = role();
        stubTarget(role, List.of());
        stubDisplayNames();
        when(roleService.assignPermissionsImpact(eq(role), anyList())).thenReturn(Map.of());
        when(proposalService.create(any())).thenReturn(payload());

        tool().proposeRoleChange("ASSIGN_PERMISSIONS", 425L, "OPER_NO_SYS", null, null,
                List.of("system:audit:view"), "给它加上审计权限", context());

        ArgumentCaptor<ProposalService.ProposalDraft> captor =
                ArgumentCaptor.forClass(ProposalService.ProposalDraft.class);
        verify(proposalService).create(captor.capture());

        assertThat(String.join("；", captor.getValue().preview().warnings()))
                .contains("全局操作审计")
                .doesNotContain("system:audit:view");
    }

    @Test
    @DisplayName("查不到中文名的编码原样显示：不能因为翻译失败就吞掉一项权限")
    void unknownPermissionCodeIsKept() {
        SysRole role = role();
        stubTarget(role, List.of());
        stubDisplayNames();
        when(roleService.assignPermissionsImpact(eq(role), anyList())).thenReturn(Map.of());
        when(proposalService.create(any())).thenReturn(payload());

        tool().proposeRoleChange("ASSIGN_PERMISSIONS", 425L, "OPER_NO_SYS", null, null,
                List.of("ghost:perm:view"), "加个不存在的权限", context());

        ArgumentCaptor<ProposalService.ProposalDraft> captor =
                ArgumentCaptor.forClass(ProposalService.ProposalDraft.class);
        verify(proposalService).create(captor.capture());

        assertThat(captor.getValue().preview().changes().get(0).after()).isEqualTo("ghost:perm:view");
    }
}
