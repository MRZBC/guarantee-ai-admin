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
 * <p>只做「是否登录」的判定；细粒度权限由权限编码承载，后续可在业务层用
 * {@code @PreAuthorize} 扩展。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtTokenProvider tokenProvider,
                                                   TokenRevocationService revocationService,
                                                   ObjectMapper objectMapper) throws Exception {
        RestAuthErrorHandlers errorHandlers = new RestAuthErrorHandlers(objectMapper);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
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
                .addFilterBefore(new JwtAuthenticationFilter(tokenProvider, revocationService),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /** 允许 Vite 开发服务器直连后端。 */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("http://localhost:*", "http://127.0.0.1:*"));
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
