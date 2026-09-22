package com.guarantee.system.mybatis;

import com.guarantee.common.api.ResultCode;
import com.guarantee.common.exception.BizException;
import com.guarantee.common.security.CurrentUser;
import com.guarantee.common.security.LogicalDeletePermissions;
import com.guarantee.common.security.Permissions;
import com.guarantee.common.security.Roles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 「显示已删除」（includeDeleted）权限判定的单元测试（设计文档 §7.1 的服务端强制校验）。
 *
 * <p>这是**参数级**权限：同一个 URL，带 {@code includeDeleted=true} 时才要求
 * {@code system:*:delete}。{@code @PreAuthorize} 表达不了它，因此必须有独立测试，
 * 否则一旦某条查询路径漏掉校验，就能绕过列表接口读到已删除数据——
 * 例如 {@code /system/orgs/tree} 与分页列表共用同一套 SQL 条件。</p>
 */
class LogicalDeletePermissionsTest {

    @AfterEach
    void clear() {
        CurrentUser.clear();
    }

    private static void loginWith(List<String> permissions) {
        CurrentUser.set(new CurrentUser.Principal(1L, "tester", "测试", 1L,
                List.of(Roles.ANALYST), permissions));
    }

    @Test
    @DisplayName("includeDeleted=true 且无 delete 权限 → 403，且提示里带上所需权限码")
    void includeDeletedRequiresDeletePermission() {
        loginWith(List.of(Permissions.ORG_VIEW)); // 只有查看权限（ANALYST/VIEWER 的典型情况）

        assertThatThrownBy(() -> LogicalDeletePermissions.requireIncludeDeleted(
                Boolean.TRUE, Permissions.ORG_DELETE, "机构"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining(Permissions.ORG_DELETE)
                .satisfies(ex -> org.assertj.core.api.Assertions
                        .assertThat(((BizException) ex).getCode())
                        .isEqualTo(ResultCode.FORBIDDEN.code()));
    }

    @Test
    @DisplayName("includeDeleted=true 且持有 delete 权限 → 放行")
    void includeDeletedAllowedWithDeletePermission() {
        loginWith(List.of(Permissions.ORG_VIEW, Permissions.ORG_DELETE));

        assertThatCode(() -> LogicalDeletePermissions.requireIncludeDeleted(
                Boolean.TRUE, Permissions.ORG_DELETE, "机构"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("默认视图（includeDeleted 为 null / false）不需要 delete 权限")
    void defaultViewNeedsNoExtraPermission() {
        loginWith(List.of(Permissions.ORG_VIEW));

        assertThatCode(() -> LogicalDeletePermissions.requireIncludeDeleted(
                null, Permissions.ORG_DELETE, "机构"))
                .doesNotThrowAnyException();
        assertThatCode(() -> LogicalDeletePermissions.requireIncludeDeleted(
                Boolean.FALSE, Permissions.ORG_DELETE, "机构"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("未登录（无当前用户）时 includeDeleted=true 必须被拒绝")
    void anonymousRequestRejected() {
        CurrentUser.clear();
        assertThatThrownBy(() -> LogicalDeletePermissions.requireIncludeDeleted(
                Boolean.TRUE, Permissions.USER_DELETE, "用户"))
                .isInstanceOf(BizException.class);
    }
}
