package com.guarantee.system.controller;

import com.guarantee.common.api.PageResult;
import com.guarantee.common.api.Result;
import com.guarantee.common.security.Permissions;
import com.guarantee.common.security.SessionAttributes;
import com.guarantee.system.dto.SessionDto;
import com.guarantee.system.service.SessionService;
import com.guarantee.system.vo.SessionKickVO;
import com.guarantee.system.vo.SessionVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 在线会话与强制下线接口（AUTH-05）。
 *
 * <p><b>权限</b>：两个端点都仅授予 ADMIN（{@code system:session:view} /
 * {@code system:session:kick}）。列表含全员登录 IP 与 User-Agent；踢出是"影响他人"的写操作，
 * 风险等级与删除相当。前端按钮显隐只是体验优化，真正的边界在这里（SYS-NF-04）。</p>
 *
 * <p><b>与"用户级撤销"的关系</b>：踢出是**会话级**的，只终止指定会话；用户若还有其它
 * 会话（换过浏览器、重新登录过），它们仍然有效。要一次性清空请用
 * {@code DELETE /api/system/sessions?userId=}。</p>
 *
 * <p><b>已知边界</b>：踢出对**下一个请求**生效，不会中断进行中的 SSE 流式对话
 * （该请求在过滤器阶段已通过认证）。多标签页共用同一 localStorage 令牌 →
 * 同一 jti → 列表里只显示一条，这是无状态 JWT 的固有形态。</p>
 */
@RestController
@RequestMapping("/api/system/sessions")
@Validated
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /** 在线会话列表。 */
    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.SESSION_VIEW + "')")
    public Result<PageResult<SessionVO>> list(@Valid SessionDto.Query query,
                                              HttpServletRequest request) {
        return Result.ok(sessionService.page(query, currentJti(request)));
    }

    /**
     * 踢出单个会话。
     *
     * <p>若目标是发起者自己的会话，响应里的 {@code selfKicked} 为 true，
     * 前端据此提示并跳登录页（D5：允许踢自己，但必须说清楚）。</p>
     */
    @DeleteMapping("/{jti}")
    @PreAuthorize("hasAuthority('" + Permissions.SESSION_KICK + "')")
    public Result<SessionKickVO> kick(@PathVariable String jti, HttpServletRequest request) {
        return Result.ok(sessionService.kick(jti, currentJti(request)));
    }

    /** 踢出某用户的全部会话。 */
    @DeleteMapping
    @PreAuthorize("hasAuthority('" + Permissions.SESSION_KICK + "')")
    public Result<SessionKickVO> kickAllOfUser(
            @RequestParam @NotNull(message = "用户 id 不能为空") Long userId,
            HttpServletRequest request) {
        return Result.ok(sessionService.kickAllOfUser(userId, currentJti(request)));
    }

    /** 当前请求所用令牌的 jti；由 JWT 过滤器写入，未认证请求为 null。 */
    private static String currentJti(HttpServletRequest request) {
        Object value = request.getAttribute(SessionAttributes.CURRENT_JTI);
        return value == null ? null : value.toString();
    }
}
