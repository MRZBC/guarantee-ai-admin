package com.guarantee.common.exception;

import com.guarantee.common.api.Result;
import com.guarantee.common.api.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 全局异常处理单元测试。
 *
 * <p><b>为什么专门为"权限不足"写一条</b>：这个映射曾经是错的——{@code @PreAuthorize} 拒绝抛
 * {@link AccessDeniedException}，而本类当时没有对应分支，于是落进兜底的
 * {@code @ExceptionHandler(Exception.class)}，被报成 {@code code=500「系统内部错误」}。
 * 用户明明是权限不足，却看到"系统内部错误"（既不属实也不可操作），
 * 前端针对 403 的文案也永远命中不了。</p>
 *
 * <p>本测试同时钉住两件事：① 返回 403 与可读话术；② <b>子类也被覆盖</b>——
 * Spring Security 6.3+ 的 {@code @PreAuthorize} 实际抛的是
 * {@link AuthorizationDeniedException}，它是 {@link AccessDeniedException} 的子类，
 * 这两条断言一起才能证明"真实的 @PreAuthorize 拒绝"会走对分支
 * （若继承关系变了，本测试连编译都过不了）。</p>
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static HttpServletRequest request() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/ai/operation-audits");
        return request;
    }

    @Test
    @DisplayName("AccessDeniedException → 403 + 可读话术，而不是 500「系统内部错误」")
    void accessDeniedShouldMapToForbidden() {
        Result<Void> result = handler.handleAccessDenied(
                new AccessDeniedException("Access Denied"), request());

        assertThat(result.code()).isEqualTo(ResultCode.FORBIDDEN.code()).isEqualTo(403);
        assertThat(result.message())
                .as("话术必须让用户知道该找谁，而不是丢一句系统错误")
                .contains("权限")
                .contains("联系管理员");
        assertThat(result.data()).isNull();
        assertThat(result.message())
                .as("不得把权限问题说成服务端故障")
                .doesNotContain("系统内部错误");
    }

    @Test
    @DisplayName("@PreAuthorize 实际抛出的子类 AuthorizationDeniedException 同样映射为 403")
    void authorizationDeniedSubclassShouldAlsoMapToForbidden() {
        // 编译即证明：AuthorizationDeniedException 是 AccessDeniedException 的子类，
        // 因此 @ExceptionHandler(AccessDeniedException.class) 能覆盖 @PreAuthorize 的拒绝
        Result<Void> result = handler.handleAccessDenied(
                new AuthorizationDeniedException("Access Denied"), request());

        assertThat(result.code()).isEqualTo(403);
        assertThat(result.message()).contains("权限");
    }

    @Test
    @DisplayName("兜底分支仍然把真正的未知异常报成 500（修复没有削弱兜底）")
    void unknownExceptionStillMapsToInternalError() {
        Result<Void> result = handler.handleException(
                new IllegalStateException("boom"), request());

        assertThat(result.code()).isEqualTo(ResultCode.INTERNAL_ERROR.code()).isEqualTo(500);
    }
}
