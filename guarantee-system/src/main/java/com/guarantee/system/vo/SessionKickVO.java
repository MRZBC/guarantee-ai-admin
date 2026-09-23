package com.guarantee.system.vo;

/**
 * 强制下线的结果（AUTH-05）。
 *
 * @param kicked     实际终止的会话数
 * @param selfKicked 被终止的会话中是否包含**发起者当前所用的会话**。
 *                   为 true 时前端应提示并跳转登录页——否则用户会看到"操作成功"却接着被踢到登录页，
 *                   不知道发生了什么（D5：允许踢自己，但必须说清楚）。
 */
public record SessionKickVO(int kicked, boolean selfKicked) {
}
