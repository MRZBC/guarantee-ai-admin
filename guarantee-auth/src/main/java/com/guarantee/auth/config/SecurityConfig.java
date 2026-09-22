package com.guarantee.auth.config;

import tools.jackson.databind.ObjectMapper;
import com.guarantee.auth.security.JwtAuthenticationFilter;
import com.guarantee.auth.security.JwtTokenProvider;
import com.guarantee.auth.security.RestAuthErrorHandlers;
import com.guarantee.auth.security.TokenRevocationService;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security 配置（无状态 JWT）。
 *
 * <p>URL 层只做「是否登录」的判定；细粒度权限由权限编码承载，通过
 * {@link EnableMethodSecurity} 开启的方法级鉴权（{@code @PreAuthorize("hasAuthority('xxx')")}）
 * 在 Service / Controller 上落地——这才是真正的安全边界（SYS-P-01、SYS-NF-04、RK-02）。</p>
 *
 * <p>方法级鉴权使用 JWT 中下发的权限码快照；权限变更后的实时性由
 * {@code TokenRevocationService} 撤销令牌保证（SYS-P-05）。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({JwtProperties.class, CorsProperties.class})
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtTokenProvider tokenProvider,
                                                   TokenRevocationService revocationService,
                                                   com.guarantee.auth.security.UserTokenRevocation userTokenRevocation,
                                                   CorsConfigurationSource corsConfigurationSource,
                                                   ObjectMapper objectMapper) throws Exception {
        RestAuthErrorHandlers errorHandlers = new RestAuthErrorHandlers(objectMapper);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 异步派发（SSE 流式响应结束后的 ASYNC dispatch）与错误派发不再重复鉴权：
                        // 此时初始请求已通过认证，且响应通常已提交，再鉴权会抛 Access Denied 并截断流。
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/auth/login").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        // 前端静态资源（管理后台首页）无需登录即可加载，登录由前端路由守卫处理
                        .requestMatchers("/", "/index.html", "/favicon.svg", "/assets/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(errorHandlers)
                        .accessDeniedHandler(errorHandlers))
                .addFilterBefore(new JwtAuthenticationFilter(tokenProvider, revocationService, userTokenRevocation),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 允许前端开发服务器直连后端。
     *
     * <p>白名单见 {@link CorsProperties}：除 localhost 外还必须覆盖局域网私有网段，
     * 否则用 {@code http://192.168.x.x:5273} 访问时，浏览器带上的 Origin 经 Vite 代理
     * 转发到后端会被 CorsFilter 拒绝为 403 {@code Invalid CORS request}。</p>
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorsProperties corsProperties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(corsProperties.getAllowedOriginPatterns());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("X-Trace-Id"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
