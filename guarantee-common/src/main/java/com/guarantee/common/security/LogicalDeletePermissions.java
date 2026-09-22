package com.guarantee.common.security;

import com.guarantee.common.api.ResultCode;
import com.guarantee.common.exception.BizException;

/**
 * 逻辑删除相关的**服务端强制**权限判定（设计文档 §7.1）。
 *
 * <p>为什么单独抽一个类：{@code includeDeleted=true} 的权限要求是"**参数级**"的
 * （同一个 URL，参数不同权限不同），这是 {@code @PreAuthorize} 表达不了的。
 * 若把它写在每个 Controller 的私有方法里，就会出现"某条查询路径忘了校验"的静默缺口——
 * 例如 {@code /system/orgs/tree} 与 {@code /system/orgs} 共用同一套 SQL 条件，
 * 只补了一个就会有绕过点。集中一处 + 单测（LD-T14 的补充断言）可以避免这种遗漏。</p>
 *
 * <p>前端隐藏「显示已删除」开关只是体验优化（SYS-NF-04），真正的边界在这里。</p>
 */
public final class LogicalDeletePermissions {

    private LogicalDeletePermissions() {
    }

    /**
     * 校验"显示已删除"的权限；不足时抛 403。
     *
     * @param includeDeleted 请求参数（null / false 表示默认视图，无需该权限）
     * @param permission     该域的删除权限码（如 {@link Permissions#ORG_DELETE}）
     * @param domainLabel    域的中文名，用于错误提示（如"机构"）
     */
    public static void requireIncludeDeleted(Boolean includeDeleted, String permission, String domainLabel) {
        if (Boolean.TRUE.equals(includeDeleted) && !CurrentUser.hasPermission(permission)) {
            throw new BizException(ResultCode.FORBIDDEN,
                    domainLabel + "的「显示已删除」需要权限：" + permission);
        }
    }
}
