package com.guarantee.common.security;

/**
 * 请求级会话属性名（AUTH-05）。
 *
 * <p>JWT 过滤器在认证通过后，把当前令牌的 {@code jti} 写入请求属性，
 * 供系统管理域标记"这条会话就是我自己"（在线会话列表的 {@code current} 列）。</p>
 *
 * <p><b>为什么用请求属性而不是扩充 {@link CurrentUser.Principal}</b>：需求方还需要
 * 用户 id / 账号 / 角色 / 权限，而 {@code jti} 只是"当前这次请求用的是哪个凭据"，
 * 生命周期与请求一致、与身份无关。放进 Principal 会让它在 AI 工具线程的权限快照里
 * 被无意义地携带，并且要改动全部既有构造点。请求属性随请求自动销毁，语义更准。</p>
 */
public final class SessionAttributes {

    /** 当前请求所用令牌的 {@code jti}；未认证请求上不存在。 */
    public static final String CURRENT_JTI = "com.guarantee.auth.currentJti";

    private SessionAttributes() {
    }
}
