package com.guarantee.common.security;

import java.util.Collection;

/**
 * 撤销某个用户已签发令牌的端口（SYS-C-07 / AC-21）。
 *
 * <p><b>为什么定义在 guarantee-common</b>：令牌撤销的实现依赖 Redis，位于
 * {@code guarantee-auth}；而触发撤销的业务动作（用户停用、角色分配、角色权限变更）
 * 属于 {@code guarantee-system}，并且 AI 工具的提案执行在 {@code guarantee-ai}。
 * 若由业务模块直接依赖 auth，会形成反向依赖。这里用一个极小的端口把依赖方向摆正：
 * 业务模块只声明"需要撤销这个人"，具体怎么撤由 auth 决定。</p>
 *
 * <p>实现必须保证：撤销是**用户级**的（令牌有效期最长 12 小时，仅撤销单个 jti
 * 无法覆盖该用户已签发的其它令牌）。</p>
 */
public interface UserTokenRevoker {

    /**
     * 撤销指定用户的全部已签发令牌，使其下次请求立即失效。
     *
     * <p>实现应"尽力而为"：撤销失败不得让业务回滚——业务变更已经提交，
     * 让用户重新登录的要求已经通过接口返回值告知操作者；把撤销失败升级为异常
     * 会造成"数据改了但接口报错"的更难排查的状态。</p>
     *
     * @param userIds 目标用户
     * @param reason  撤销原因（仅用于日志与可观测）
     * @return 实际被标记撤销的用户数
     */
    int revokeUsers(Collection<Long> userIds, String reason);

    /** 撤销单个用户。 */
    default int revokeUser(Long userId, String reason) {
        return userId == null ? 0 : revokeUsers(java.util.List.of(userId), reason);
    }
}
