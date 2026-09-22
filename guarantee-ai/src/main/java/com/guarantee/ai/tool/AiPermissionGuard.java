package com.guarantee.ai.tool;

import com.guarantee.common.api.ResultCode;
import com.guarantee.common.exception.BizException;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;

/**
 * AI 工具层的权限守卫（T-04 / SYS-P-12a）。
 *
 * <p><b>两道防线</b>：</p>
 * <ol>
 *   <li>{@code AiToolRegistry} 按权限**裁剪注册集**——没权限的工具根本不出现在模型面前，
 *       模型不会尝试调用（第一道，SYS-P-12a）；</li>
 *   <li>工具内部用本类再校验一次（第二道）。第二道不是冗余：工具也可能被非模型路径
 *       （测试、内部调用）触发，而且"注册裁剪有 bug"不应该等于"越权成功"。</li>
 * </ol>
 *
 * <p><b>空结果 vs 无权限必须可区分</b>（SYS-Q-11）：无权限时工具应返回
 * {@code denied=true} + {@code deniedReason}，绝不允许伪装成 0 条。因此本类提供两种用法：
 * {@link #allowed} 供工具自行决定如何应答，{@link #require} 供必须硬失败的场景。</p>
 */
public final class AiPermissionGuard {

    /** 未登录/无权限时的统一话术（SYS-N-06：不得暴露数据存在性）。 */
    public static final String DENIED_TEMPLATE = "你当前没有 %s 权限，请联系管理员";

    private AiPermissionGuard() {
    }

    /** 从 ToolContext 读取权限快照。 */
    @SuppressWarnings("unchecked")
    public static List<String> permissions(ToolContext context) {
        Object value = raw(context, AiToolContextKeys.PERMISSIONS);
        if (value instanceof List<?> list) {
            return (List<String>) list;
        }
        // 兼容单个权限码被误传为字符串的场景
        if (value instanceof String text) {
            return List.of(text);
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    public static List<String> roles(ToolContext context) {
        Object value = raw(context, AiToolContextKeys.ROLES);
        return value instanceof List<?> list ? (List<String>) list : List.of();
    }

    public static Long userId(ToolContext context) {
        return longValue(context, AiToolContextKeys.USER_ID);
    }

    public static Long orgId(ToolContext context) {
        return longValue(context, AiToolContextKeys.ORG_ID);
    }

    public static String username(ToolContext context) {
        Object value = raw(context, AiToolContextKeys.USERNAME);
        return value instanceof String text ? text : null;
    }

    public static String realName(ToolContext context) {
        Object value = raw(context, AiToolContextKeys.REAL_NAME);
        return value instanceof String text ? text : null;
    }

    public static Long conversationId(ToolContext context) {
        return longValue(context, AiToolContextKeys.CONVERSATION_ID);
    }

    public static String traceId(ToolContext context) {
        Object value = raw(context, AiToolContextKeys.TRACE_ID);
        return value instanceof String text ? text : null;
    }

    /** 是否持有全部给定权限码。 */
    public static boolean allowed(ToolContext context, String... required) {
        if (required == null || required.length == 0) {
            return true;
        }
        List<String> granted = permissions(context);
        for (String code : required) {
            if (code != null && !granted.contains(code)) {
                return false;
            }
        }
        return true;
    }

    /** 统一的拒绝话术。 */
    public static String deniedReason(String... required) {
        String subject = String.join(" / ", required);
        return String.format(DENIED_TEMPLATE, subject);
    }

    /**
     * 查询工具「显示已删除」（{@code includeDeleted=true}）无权限时的统一话术（设计 §7.4 / §7.1）。
     *
     * <p><b>为什么必须显式拒绝，而不是把 {@code true} 静默当成 {@code false}</b>：
     * 静默降级会让模型与用户都以为"系统里没有已删除数据"，而事实是"你看不到"。
     * SYS-Q-11 已经明确禁止把无权限伪装成空结果，这里沿用同一口径——
     * 无权限时工具返回 {@code denied=true} 且不携带任何业务数据。</p>
     *
     * @param deletePermission 该域的删除权限码（设计 §7.1 规定"查看已删除"复用同一个码）
     */
    public static String includeDeletedDeniedReason(String deletePermission) {
        return String.format(DENIED_TEMPLATE, deletePermission)
                + "。「显示已删除」需要该权限，本次查询未包含已删除数据。";
    }

    /**
     * 硬校验：不满足时抛 403。
     *
     * <p>用于"本工具存在即代表权限已满足"之外的路径（例如内部调用、测试），
     * 让越权在日志与响应码上明确可见。</p>
     */
    public static void require(ToolContext context, String... required) {
        if (!allowed(context, required)) {
            throw new BizException(ResultCode.FORBIDDEN, deniedReason(required));
        }
    }

    private static Object raw(ToolContext context, String key) {
        if (context == null || context.getContext() == null) {
            return null;
        }
        return context.getContext().get(key);
    }

    private static Long longValue(ToolContext context, String key) {
        Object value = raw(context, key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }
}
