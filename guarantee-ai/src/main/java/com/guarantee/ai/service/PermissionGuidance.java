package com.guarantee.ai.service;

import java.util.Collection;
import java.util.List;

/** 页面引导使用账号权限，与助手工具是否开放分开判断。 */
public final class PermissionGuidance {
    private PermissionGuidance() {}

    public static String context(Collection<?> permissions) {
        var granted = permissions == null ? List.of() : permissions;
        StringBuilder out = new StringBuilder("\n\n# 本轮账号操作权限（服务端事实）\n");
        out.append("没有对应页面动作权限时，只能建议联系管理员，不得建议去页面直接操作。")
                .append("工具缺席也可能是助手能力未开放，不能据此推断页面权限。")
                .append("下列未列出的动作权限未知，请联系管理员确认。\n");
        String[][] domains = {{"role", "角色"}, {"user", "用户"}, {"org", "机构"},
                {"dept", "部门"}, {"insurance", "险种"}};
        String[][] actions = {{"view", "查看"}, {"create", "新增"}, {"update", "修改"},
                {"disable", "启停"}, {"delete", "删除/恢复"},
                {"assign-role", "分配角色"}, {"assign-permission", "分配权限"}};
        for (String[] domain : domains) {
            for (String[] action : actions) {
                out.append("- ").append(domain[1]).append(action[1]).append("：")
                        .append(granted.contains("system:" + domain[0] + ":" + action[0])
                                ? "账号有此权限；仅在页面确有该入口时可引导操作" : "无权限，请联系管理员")
                        .append('\n');
            }
        }
        return out.toString();
    }
}
