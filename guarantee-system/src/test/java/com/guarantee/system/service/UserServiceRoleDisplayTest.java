package com.guarantee.system.service;

import com.guarantee.common.security.UserTokenRevoker;
import com.guarantee.system.entity.SysRole;
import com.guarantee.system.entity.SysUser;
import com.guarantee.system.mapper.SysDepartmentMapper;
import com.guarantee.system.mapper.SysRoleMapper;
import com.guarantee.system.mapper.SysUserMapper;
import com.guarantee.system.scope.DataScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 角色编码 → 中文展示名（确认卡与影响面文案）。
 *
 * <p><b>为什么值得单独测</b>：这些名称直接进确认卡的「原值/新值」与「影响面」，
 * 是给业务用户看的正文。真机上出现过同一屏里正文写「该用户将从超级管理员降为数据分析师」、
 * 确认卡却写「当前角色 ADMIN；变更后角色 ANALYST」的割裂——本类钉住这个转换。</p>
 */
@ExtendWith(MockitoExtension.class)
class UserServiceRoleDisplayTest {

    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private SysRoleMapper sysRoleMapper;
    @Mock
    private SysDepartmentMapper sysDepartmentMapper;
    @Mock
    private DataScopeService dataScopeService;
    @Mock
    private ObjectProvider<UserTokenRevoker> tokenRevokerProvider;
    @Mock
    private WebAuditor webAuditor;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(sysUserMapper, sysRoleMapper, sysDepartmentMapper,
                dataScopeService, tokenRevokerProvider, webAuditor);
    }

    private static SysRole role(String code, String name) {
        SysRole role = new SysRole();
        role.setRoleCode(code);
        role.setRoleName(name);
        return role;
    }

    @Test
    @DisplayName("编码翻译为中文名，且保持传入顺序")
    void translatesCodesToNames() {
        when(sysRoleMapper.selectEntityByCodes(List.of("ANALYST", "ADMIN")))
                .thenReturn(List.of(role("ADMIN", "超级管理员"), role("ANALYST", "数据分析师")));

        assertThat(userService.roleDisplayNames(List.of("ANALYST", "ADMIN")))
                .as("顺序必须与传入一致（影响面按这个顺序读）")
                .containsExactly("数据分析师", "超级管理员");
    }

    @Test
    @DisplayName("查不到的编码原样返回，绝不静默丢弃")
    void unknownCodeFallsBackToItself() {
        when(sysRoleMapper.selectEntityByCodes(List.of("GHOST")))
                .thenReturn(List.of());

        assertThat(userService.roleDisplayNames(List.of("GHOST")))
                .as("宁可偶尔露出一个编码，也不能吞掉一个角色——"
                        + "那会让确认卡的影响面与实际变更不一致，比不好看严重得多")
                .containsExactly("GHOST");
    }

    @Test
    @DisplayName("角色名缺失时退回编码，不产出 null")
    void missingNameFallsBackToCode() {
        when(sysRoleMapper.selectEntityByCodes(List.of("ADMIN")))
                .thenReturn(List.of(role("ADMIN", null)));

        assertThat(userService.roleDisplayNames(List.of("ADMIN"))).containsExactly("ADMIN");
    }

    @Test
    @DisplayName("空集合与 null 返回空列表，不查库")
    void emptyInputReturnsEmptyWithoutQuery() {
        assertThat(userService.roleDisplayNames(List.of())).isEmpty();
        assertThat(userService.roleDisplayNames(null)).isEmpty();
    }

    @Test
    @DisplayName("assignRolesImpact 的当前/变更后角色都是中文名（截图里的那个割裂）")
    void assignRolesImpactUsesChineseNames() {
        SysUser user = new SysUser();
        user.setId(7L);
        when(sysUserMapper.listRoleCodesByUserId(7L)).thenReturn(List.of("ADMIN"));
        when(sysRoleMapper.selectEntityByCodes(List.of("ADMIN")))
                .thenReturn(List.of(role("ADMIN", "超级管理员")));
        when(sysRoleMapper.selectEntityByCodes(List.of("ANALYST")))
                .thenReturn(List.of(role("ANALYST", "数据分析师")));

        Map<String, Object> impact = userService.assignRolesImpact(user, List.of("ANALYST"));

        assertThat(impact.get("当前角色")).isEqualTo(List.of("超级管理员"));
        assertThat(impact.get("变更后角色")).isEqualTo(List.of("数据分析师"));
        assertThat(String.valueOf(impact))
                .as("整段影响面里不应再出现任何角色编码")
                .doesNotContain("ADMIN")
                .doesNotContain("ANALYST");
    }

    @Test
    @DisplayName("停用/删除影响面的持有角色同样用中文名，但「是否最后一个管理员」的逻辑判定仍按编码")
    void stopImpactKeepsCodeBasedLogic() {
        SysUser user = new SysUser();
        user.setId(9L);
        when(sysUserMapper.listRoleCodesByUserId(9L)).thenReturn(List.of("ADMIN"));
        when(sysRoleMapper.selectEntityByCodes(List.of("ADMIN")))
                .thenReturn(List.of(role("ADMIN", "超级管理员")));
        when(sysUserMapper.countOtherEnabledAdmins(9L)).thenReturn(0L);

        Map<String, Object> impact = userService.stopImpact(user);

        assertThat(impact.get("持有角色")).isEqualTo(List.of("超级管理员"));
        assertThat(impact.get("是否最后一个启用管理员"))
                .as("这是逻辑判定，必须继续基于编码（Roles.ADMIN），不能被展示名影响")
                .isEqualTo(true);
    }

    // ==================================================================
    // 面向用户的文案里不得出现内部技术术语
    // ==================================================================

    @Test
    @DisplayName("影响面文案不出现 JWT / 令牌 —— 业务用户看不懂（真机截图里的那句）")
    void impactTextsAvoidInternalJargon() {
        SysUser user = new SysUser();
        user.setId(11L);
        when(sysUserMapper.listRoleCodesByUserId(11L)).thenReturn(List.of("ADMIN", "ANALYST"));
        when(sysRoleMapper.selectEntityByCodes(List.of("ADMIN", "ANALYST")))
                .thenReturn(List.of(role("ADMIN", "超级管理员"), role("ANALYST", "数据分析师")));
        when(sysRoleMapper.selectEntityByCodes(List.of("VIEWER")))
                .thenReturn(List.of(role("VIEWER", "只读用户")));
        when(sysUserMapper.countOtherEnabledAdmins(11L)).thenReturn(1L);

        // 三处会进确认卡影响面 / 二次确认弹窗的文案
        String stop = String.valueOf(userService.stopImpact(user));
        String assign = String.valueOf(userService.assignRolesImpact(user, List.of("VIEWER")));
        String delete = String.valueOf(userService.deleteImpact(user));

        assertThat(List.of(stop, assign, delete))
                .as("这些文案会出现在确认卡与二次确认弹窗上，读者是运营人员而不是开发。"
                        + "出现 JWT / 令牌 / claims 这类术语就是缺陷（真机截图踩过）")
                .allSatisfy(text -> assertThat(text)
                        .doesNotContain("JWT")
                        .doesNotContain("令牌")
                        .doesNotContain("claims"));

        assertThat(assign)
                .as("改用业务用户能理解的说法")
                .contains("强制下线");
    }

    @Test
    @DisplayName("影响面文案里不出现角色编码（展示与逻辑必须分开）")
    void impactTextsAvoidRoleCodes() {
        SysUser user = new SysUser();
        user.setId(13L);
        when(sysUserMapper.listRoleCodesByUserId(13L)).thenReturn(List.of("ADMIN"));
        when(sysRoleMapper.selectEntityByCodes(List.of("ADMIN")))
                .thenReturn(List.of(role("ADMIN", "超级管理员")));
        when(sysRoleMapper.selectEntityByCodes(List.of("ANALYST")))
                .thenReturn(List.of(role("ANALYST", "数据分析师")));

        String assign = String.valueOf(userService.assignRolesImpact(user, List.of("ANALYST")));
        String stop = String.valueOf(userService.stopImpact(user));

        assertThat(assign)
                .as("同一屏的正文写「超级管理员」，确认卡却写「ADMIN」就是自相矛盾")
                .doesNotContain("ADMIN")
                .doesNotContain("ANALYST")
                .contains("超级管理员")
                .contains("数据分析师");
        assertThat(stop).doesNotContain("ADMIN").contains("超级管理员");
    }
}
