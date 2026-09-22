package com.guarantee.auth.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CORS 方法白名单的回归测试。
 *
 * <p><b>为什么必须有这一条</b>：{@code allowedMethods} 一旦漏掉控制器实际使用的某个方法，
 * 浏览器发起的该方法跨域请求会被 {@code DefaultCorsProcessor} 在**进入业务逻辑之前**
 * 直接拒为 403，且响应体是**纯文本** {@code Invalid CORS request}（没有 {@code message} 字段）。
 * 前端 {@code request.ts} 取不到 {@code data.message}，只能落到兜底文案
 * 「没有权限访问该资源」——与真正的权限不足**完全无法区分**。</p>
 *
 * <p>本系统**所有启停端点用的都是 {@code @PatchMapping}**，因此漏掉 PATCH 会让
 * 全部「启用/停用」按钮失效，而报错却指向权限，排查成本极高（真机踩过）。</p>
 *
 * <p>另一个必须记住的排查要点：不带 {@code Origin} 头用 curl / Invoke-WebRequest 探测
 * 是不经过 CORS 校验的（{@code DefaultCorsProcessor} 视其为非 CORS 请求直接放行），
 * 会得到"正常"的假象——验证 CORS 必须带上 {@code Origin}。</p>
 */
class SecurityConfigCorsTest {

    @Test
    @DisplayName("CORS 必须放行控制器实际使用的全部 HTTP 方法（尤其 PATCH：所有启停端点都用它）")
    void corsAllowsEveryMethodUsedByControllers() {
        CorsConfigurationSource source = new SecurityConfig().corsConfigurationSource(new CorsProperties());

        CorsConfiguration config = source.getCorsConfiguration(
                new MockHttpServletRequest("PATCH", "/api/system/users/1/status"));

        assertThat(config).as("CORS 配置必须覆盖业务路径").isNotNull();
        // 控制器实际用到 GET / POST / PUT / PATCH / DELETE（见各 Controller 的 @*Mapping）
        assertThat(config.getAllowedMethods())
                .as("任一方法缺失都会让该方法跨域被拒为 403，且报错伪装成权限不足")
                .contains("GET", "POST", "PUT", "PATCH", "DELETE");
    }

    @Test
    @DisplayName("CORS 仍必须拒绝白名单外的来源（修复 allowedMethods 不能把来源校验一起放开）")
    void corsStillRejectsUnknownOrigin() {
        CorsConfigurationSource source = new SecurityConfig().corsConfigurationSource(new CorsProperties());

        CorsConfiguration config = source.getCorsConfiguration(
                new MockHttpServletRequest("PATCH", "/api/system/users/1/status"));

        assertThat(config).isNotNull();
        assertThat(config.checkOrigin("http://evil.example.com"))
                .as("白名单外的来源必须被拒绝")
                .isNull();
        assertThat(config.checkOrigin("http://localhost:5273"))
                .as("localhost 必须被放行")
                .isNotNull();
    }
}
