package com.guarantee.system.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 在线会话（AUTH-05）。
 *
 * <p><b>两个时间字段的语义必须分清</b>：{@code idleExpiresAt} 是"再没有请求就到此为止"，
 * 有活动会自动顺延；{@code absoluteExpiresAt} 是"自登录起算的硬上限"，不会因活跃而延长。
 * 页面应同时展示，只显示其中一个都会让人误判会话还能用多久。</p>
 */
@Data
public class SessionVO {

    /** 会话标识（令牌 jti）。允许展示：它不是凭据，无法据此构造出可通过签名校验的令牌。 */
    private String jti;

    private Long userId;
    private String username;
    private String realName;

    /** 登录时间。 */
    private LocalDateTime loginAt;

    /** 空闲到期时间（有请求即顺延）。 */
    private LocalDateTime idleExpiresAt;

    /** 绝对上限到期时间（自登录起算，不会延长）。 */
    private LocalDateTime absoluteExpiresAt;

    private String loginIp;
    private String userAgent;

    /**
     * 是否为**当前请求**所用的会话。
     *
     * <p>用于前端提示"你正在踢出自己"。</p>
     */
    private boolean current;
}
