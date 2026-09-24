package com.guarantee.system.service;

import com.guarantee.common.security.UserTokenRevoker;
import com.guarantee.system.entity.SysPermission;
import com.guarantee.system.entity.SysRole;
import com.guarantee.system.mapper.SysRoleMapper;
import com.guarantee.system.mapper.SysUserMapper;
import com.guarantee.system.vo.RoleVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * 权限编码 → 中文权限名（角色授权确认卡的「权限」一列与影响面）。
 *
 * <p><b>为什么值得单独测</b>：真机上角色授权卡的「权限」直接铺着
 * {@code dashboard:view, order:tender:view, analysis:overview:view, …}，业务用户读不出改了什么。
 * 角色分配卡早在决策四就改成了中文名，授权卡是漏网的一处（使用者当场问"这里应该显示中文吧"）。</p>
 */
@ExtendWith(MockitoExtension.class)
class RoleServicePermissionDisplayTest {

    @Mock
    private SysRoleMapper sysRoleMapper;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private ObjectProvider<UserTokenRevoker> tokenRevokerProvider;
    @Mock
    private WebAuditor webAuditor;

    private RoleService roleService() {
        return new RoleService(sysRoleMapper, sysUserMapper, tokenRevokerProvider, webAuditor);
    }

    private static SysPermission permission(String code, String name) {
        SysPermission permission = new SysPermission();
        permission.setPermCode(code);
        permission.setPermName(name);
        return permission;
    }

    @Test
    @DisplayName("编码翻译为中文名，且保持传入顺序（卡片上按顺序读）")
    void translatesCodesToNames() {
        when(sysRoleMapper.selectPermissionEntitiesByCodes(anyList())).thenReturn(List.of(
                permission("order:tender:view", "投标订单查询"),
                permission("dashboard:view", "数据概览")));

        assertThat(roleService().permissionDisplayNames(
                List.of("dashboard:view", "order:tender:view")))
                .containsExactly("数据概览", "投标订单查询");
    }

    @Test
    @DisplayName("查不到的编码原样返回：宁可露出一个编码，也不能静默丢掉一项权限")
    void unknownCodeFallsBackToItself() {
        when(sysRoleMapper.selectPermissionEntitiesByCodes(anyList())).thenReturn(List.of(
                permission("dashboard:view", "数据概览")));

        assertThat(roleService().permissionDisplayNames(
                List.of("dashboard:view", "ghost:perm:view")))
                .containsExactly("数据概览", "ghost:perm:view");
    }

    @Test
    @DisplayName("权限名为空时退回编码（不产出空字符串）")
    void blankNameFallsBackToCode() {
        when(sysRoleMapper.selectPermissionEntitiesByCodes(anyList()))
                .thenReturn(List.of(permission("dashboard:view", "  ")));

        assertThat(roleService().permissionDisplayNames(List.of("dashboard:view")))
                .containsExactly("dashboard:view");
    }

    @Test
    @DisplayName("空/全空白输入直接返回空表，不查库")
    void emptyInputShortCircuits() {
        assertThat(roleService().permissionDisplayNames(List.of())).isEmpty();
        assertThat(roleService().permissionDisplayNames(null)).isEmpty();
        assertThat(roleService().permissionDisplayNames(java.util.Arrays.asList(null, " "))).isEmpty();
    }

    @Test
    @DisplayName("重复编码只翻译一次（保持去重后的顺序）")
    void duplicatesAreCollapsed() {
        when(sysRoleMapper.selectPermissionEntitiesByCodes(anyList()))
                .thenReturn(List.of(permission("ai:chat", "AI 助手对话")));

        assertThat(roleService().permissionDisplayNames(List.of("ai:chat", "ai:chat")))
                .containsExactly("AI 助手对话");
    }

    @Test
    @DisplayName("授权影响面用中文名：授权卡的「当前权限 / 变更后权限」不再出现权限码")
    void impactUsesChineseNames() {
        SysRole role = new SysRole();
        role.setId(425L);
        role.setRoleCode("OPER_NO_SYS");
        role.setRoleName("业务运营（无系统配置）");

        RoleVO current = new RoleVO();
        current.setPermissionCodes(List.of());
        when(sysRoleMapper.selectVoById(425L)).thenReturn(current);
        when(sysRoleMapper.selectUserIdsByRoleCode("OPER_NO_SYS")).thenReturn(List.of());
        when(sysRoleMapper.selectPermissionEntitiesByCodes(anyList())).thenReturn(List.of(
                permission("dashboard:view", "数据概览"),
                permission("ai:chat", "AI 助手对话")));

        Map<String, Object> impact = roleService().assignPermissionsImpact(
                role, List.of("dashboard:view", "ai:chat"));

        assertThat(impact.get("当前权限")).isEqualTo(List.of());
        assertThat(impact.get("变更后权限")).isEqualTo(List.of("数据概览", "AI 助手对话"));
        assertThat(String.valueOf(impact)).as("整段影响面里不得再出现权限码")
                .doesNotContain("dashboard:view").doesNotContain("ai:chat");
    }
}
