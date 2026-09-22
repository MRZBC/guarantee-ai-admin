package com.guarantee.ai.tool;

import com.guarantee.common.api.ResultCode;
import com.guarantee.common.exception.BizException;
import com.guarantee.common.security.Permissions;
import com.guarantee.common.security.Roles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具权限守卫单元测试（TEST-02 / TEST-10 / SYS-P-12a）。
 *
 * <p>重点验证"空权限列表 = 没有权限快照"，而不是"不需要权限"：
 * 前者必须 fail-closed，否则任何一个忘记填充 ToolContext 的调用路径都会变成越权入口。</p>
 */
class AiPermissionGuardTest {

    private static ToolContext context(List<String> permissions, List<String> roles) {
        Map<String, Object> map = new HashMap<>();
        map.put(AiToolContextKeys.PERMISSIONS, permissions);
        map.put(AiToolContextKeys.ROLES, roles);
        map.put(AiToolContextKeys.USER_ID, 42L);
        // 机构不再随身份下传（机构服务于订单，不是人的归属属性），因此这里不再写 ORG_ID
        return new ToolContext(map);
    }

    @Test
    @DisplayName("持有权限时 allowed 为 true")
    void shouldAllowWhenPermissionGranted() {
        ToolContext ctx = context(List.of(Permissions.ORG_VIEW, Permissions.AI_CHAT), List.of(Roles.ANALYST));
        assertThat(AiPermissionGuard.allowed(ctx, Permissions.ORG_VIEW)).isTrue();
        assertThat(AiPermissionGuard.userId(ctx)).isEqualTo(42L);
    }

    @Test
    @DisplayName("缺少任一所需权限时 allowed 为 false")
    void shouldDenyWhenAnyPermissionMissing() {
        ToolContext ctx = context(List.of(Permissions.AI_SYSTEM_WRITE, Permissions.ORG_CREATE),
                List.of(Roles.ADMIN));
        assertThat(AiPermissionGuard.allowed(ctx, Permissions.AI_SYSTEM_WRITE, Permissions.ORG_UPDATE))
                .isFalse();
    }

    @Test
    @DisplayName("空权限列表按 fail-closed 处理：需要权限的工具一律不放行")
    void shouldFailClosedOnEmptyPermissions() {
        ToolContext ctx = context(List.of(), List.of());
        assertThat(AiPermissionGuard.allowed(ctx, Permissions.ORG_VIEW)).isFalse();
        // 不声明任何权限要求的调用仍然放行（业务域只读工具走这条）
        assertThat(AiPermissionGuard.allowed(ctx)).isTrue();
    }

    @Test
    @DisplayName("ToolContext 缺失时也 fail-closed，不抛 NPE")
    void shouldFailClosedOnNullContext() {
        assertThat(AiPermissionGuard.allowed(null, Permissions.ORG_VIEW)).isFalse();
        assertThat(AiPermissionGuard.permissions(null)).isEmpty();
        assertThat(AiPermissionGuard.userId(null)).isNull();
    }

    @Test
    @DisplayName("require 在缺权限时抛 403，且文案统一为「没有 XXX 权限」（SYS-N-06）")
    void shouldThrowForbiddenOnRequire() {
        ToolContext ctx = context(List.of(), List.of());
        assertThatThrownBy(() -> AiPermissionGuard.require(ctx, Permissions.AUDIT_VIEW))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(ResultCode.FORBIDDEN.code()))
                .hasMessageContaining(Permissions.AUDIT_VIEW)
                .hasMessageContaining("请联系管理员");
    }

    @Test
    @DisplayName("拒绝话术不暴露数据存在性")
    void deniedReasonShouldNotLeakExistence() {
        String reason = AiPermissionGuard.deniedReason(Permissions.USER_VIEW);
        assertThat(reason).doesNotContain("存在").doesNotContain("有数据").doesNotContain("共");
    }
}
