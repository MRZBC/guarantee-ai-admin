package com.guarantee.common.security;

import java.util.Collection;
import java.util.List;

/**
 * 在线会话注册表端口（AUTH-05）。
 *
 * <p><b>为什么定义在 guarantee-common</b>：实现依赖 Redis 与令牌撤销，位于
 * {@code guarantee-auth}；而"谁在线 / 把谁踢下去"是系统管理域的查询与写操作，
 * 接口在 {@code guarantee-system}。若由 system 直接依赖 auth 会形成反向依赖。
 * 这里用一个极小的端口把依赖方向摆正——与 {@link UserTokenRevoker}、
 * {@link OperationAuditPort} 是同一手法。</p>
 *
 * <p><b>本端口不是鉴权输入</b>：授权的唯一依据是令牌白名单
 * （{@code guarantee:auth:token:{jti}}，见 {@code TokenRevocationService}）。
 * 会话记录只用于运维展示与定位。因此实现的写失败是**尽力而为**的——
 * 索引缺失只会让某个会话不出现在列表里，不会放宽或收紧任何访问权限。</p>
 *
 * <p>对照 {@link UserTokenRevoker}：那是"按用户撤销"，本端口是"按会话撤销 + 查看"。
 * 二者共用同一份令牌白名单。</p>
 */
public interface SessionRegistry {

    /**
     * 登记一个新会话（登录成功后调用）。
     *
     * @param info            会话信息；{@code idleExpiresAt} 由实现按 TTL 计算，入参可忽略
     * @param idleTtlSeconds  空闲超时（秒），同时也是令牌白名单的 TTL（AUTH-04）
     */
    void register(SessionInfo info, long idleTtlSeconds);

    /**
     * 活动续期（AUTH-04 的空闲滑动）。
     *
     * <p>实现必须**只延长 TTL，不得重新签发令牌**：用户级撤销按令牌 {@code iat} 比较，
     * 重新签发会产生新的 {@code iat}，使已被撤销用户的旧令牌复活（提权路径）。</p>
     *
     * <p>会话记录不存在时（例如本功能上线前签发的旧令牌）应静默跳过，不得创建残缺记录。</p>
     */
    void touch(String jti, long idleTtlSeconds);

    /**
     * 终止单个会话：令牌立即失效 + 会话记录清除。
     *
     * <p>登出与"踢出"共用本方法——两者语义完全相同，只是触发者不同。</p>
     *
     * @return 是否确实清理了会话记录（令牌已失效但记录本就不存在时为 false）
     */
    boolean terminate(String jti);

    /**
     * 终止这些用户的**全部**会话。
     *
     * <p>必须清理会话记录：否则会出现"用户已被强制下线、但在在线会话列表里还在"的
     * 误导性现象。</p>
     *
     * <p><b>失败语义由调用方决定</b>：实现**不吞异常**。"踢出某用户全部会话"必须让管理员
     * 看到真实结果；"用户级撤销"是尽力而为的，由 {@link UserTokenRevoker} 的实现自行包裹。</p>
     *
     * @return 实际终止的会话数
     */
    int terminateAllForUsers(Collection<Long> userIds);

    /**
     * 当前在线会话（已自动剔除过期项）。
     *
     * <p>实现应在读取时惰性清理，不依赖定时任务。</p>
     */
    List<SessionInfo> listAll();
}
