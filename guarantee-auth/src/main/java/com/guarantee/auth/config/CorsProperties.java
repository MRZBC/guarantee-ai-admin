package com.guarantee.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * CORS 配置，前缀 {@code guarantee.auth.cors}。
 *
 * <p>为什么需要局域网网段：开发期 Vite dev server 监听 {@code 0.0.0.0}
 * （见 {@code frontend/vite.config.ts}），用 {@code http://192.168.x.x:5273} 打开页面时，
 * 浏览器会给 POST 等请求带上该地址作为 {@code Origin}，并由 Vite 代理**原样**转发到后端
 * —— {@code changeOrigin: true} 只改写 {@code Host}，不改写 {@code Origin}。</p>
 *
 * <p>若来源不在白名单内，Spring 的 {@code CorsFilter} 会在进入业务逻辑前直接返回
 * 403 且响应体为纯文本 {@code Invalid CORS request}。前端
 * {@code src/api/request.ts} 对该 403 取不到 {@code data.message}，会落到兜底文案
 * 「没有权限访问该资源」，与真正的权限不足无法区分，排查时极易误判。</p>
 *
 * <p>生产环境应通过 {@code guarantee.auth.cors.allowed-origin-patterns} 收敛为真实域名。</p>
 */
@ConfigurationProperties(prefix = "guarantee.auth.cors")
public class CorsProperties {

    /** 允许的来源模式，支持端口通配（如 {@code http://localhost:*}）。 */
    private List<String> allowedOriginPatterns = List.of(
            "http://localhost:*",
            "http://127.0.0.1:*",
            // 局域网私有网段：允许同网段设备访问本机 Vite dev server
            "http://192.168.*:*",
            "http://10.*:*");

    public List<String> getAllowedOriginPatterns() {
        return allowedOriginPatterns;
    }

    public void setAllowedOriginPatterns(List<String> allowedOriginPatterns) {
        this.allowedOriginPatterns = allowedOriginPatterns;
    }
}
