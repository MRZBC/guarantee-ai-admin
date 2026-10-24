package com.guarantee.ai.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class PermissionGuidanceTest {
    @Test
    void pageWritePermissionDoesNotFollowReadOrAssistantPermission() {
        assertThat(PermissionGuidance.context(List.of("system:role:view", "ai:system:write")))
                .contains("角色修改：无权限，请联系管理员");
        assertThat(PermissionGuidance.context(List.of("system:role:update")))
                .contains("角色修改：账号有此权限");
        assertThat(PermissionGuidance.context(null)).contains("角色修改：无权限");
    }
}
