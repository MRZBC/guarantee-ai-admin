package com.guarantee.auth.config;

import tools.jackson.databind.ObjectMapper;
import com.guarantee.auth.security.JwtAuthenticationFilter;
import com.guarantee.auth.security.JwtTokenProvider;
import com.guarantee.auth.security.PasswordChangeRequiredFilter;
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
@EnableConfigurationProperties({JwtProperties.class, CorsProperties.class,
        LoginGuardProperties.class, RevocationProperties.class})
public class SecurityConfig {

    /*
      PasswordEncoder bean **不在本类**：它已下沉到 guarantee-common 的 PasswordEncoderConfig（P-10 / D5）。
      guarantee-system 的 UserService 也需要编码密码（新建账号写入初始密码、自助改密、管理员重置），
      而依赖方向是 auth → system，system 拿不到本模块的 bean。
      ⚠️ 不要在 auth 或 system 里再定义一个同类型 bean：guarantee-web 同时加载两个模块，
      按类型注入会抛 NoUniqueBeanDefinitionException。
     */

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtTokenProvider tokenProvider,
                                                   TokenRevocationService revocationService,
                                                   com.guarantee.auth.security.UserTokenRevocation userTokenRevocation,
                                                   com.guarantee.common.security.SessionRegistry sessionRegistry,
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
                        // 登出必须放行（AUTH-06）：令牌已过期时，请求在本过滤器之后仍是匿名的，
                        // 若不放行会被 AuthenticationEntryPoint 拦成 401 —— 而登出的语义承诺是
                        // "即使令牌已过期也返回成功"。前端拿到 401 会走「登录已失效」分支并
                        // window.location.reload()，用户主动登出反而看到报错 + 整页刷新。
                        // 安全性：登出只删除**请求中呈现的令牌**对应的 jti，不提供其它能力；
                        // 要调用它必须先持有该令牌，不构成新的攻击面。
                        .requestMatchers("/api/auth/logout").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        // 指标端点免登录（T5-01 / REQ-MCP-08，AC-MCP-07）：Prometheus 抓取端没有
                        // 平台账号，而本项目 JWT 走 Redis 白名单且会过期，用令牌抓取不现实。
                        // 免登录是**内网/白名单采集**的通行做法；该端点只暴露**低基数**指标名与
                        // 枚举标签（tool/model/status/source/direction/domain/hit/outcome/capped/result，
                        // 已由 AiChatMetrics.sanitize() 卡死），**不含**用户 id、会话 id、问题正文与参数值
                        // （AC-MCP-08）。**生产部署必须靠网络层收敛**（仅采集网可达），不要对公网暴露。
                        // 故意只放行这一个路径，不写 "/actuator/**"：其余端点（env/beans/heapdump 等）
                        // 仍必须鉴权。
                        .requestMatchers("/actuator/prometheus").permitAll()
                        // 前端静态资源（管理后台首页）无需登录即可加载，登录由前端路由守卫处理
                        .requestMatchers("/", "/index.html", "/favicon.svg", "/assets/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(errorHandlers)
                        .accessDeniedHandler(errorHandlers))
                .addFilterBefore(new JwtAuthenticationFilter(tokenProvider, revocationService,
                                userTokenRevocation, sessionRegistry),
                        UsernamePasswordAuthenticationFilter.class)
                // 强制改密闸门必须**在 JWT 过滤器之后**：它只认后者附加的
                // PWD_CHANGE_REQUIRED authority。
                //
                // 锚点用 UsernamePasswordAuthenticationFilter 而不是 JwtAuthenticationFilter：
                // 后者是自定义过滤器、不是 Spring Security 已知的排序锚点（addFilterAfter 对它
                // 会抛错）。而 addFilterBefore(jwt, UPAF) 让 jwt 落在 UPAF-1、
                // addFilterAfter(gate, UPAF) 让 gate 落在 UPAF+1，顺序是确定的。
                .addFilterAfter(new PasswordChangeRequiredFilter(objectMapper),
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
        // 必须与控制器实际使用的方法集一致。**漏掉任何一个**都会让浏览器发起的该方法的
        // 跨域请求被 DefaultCorsProcessor 直接拒为 403（纯文本 "Invalid CORS request"，
        // 响应体没有 message），前端只能落到兜底文案「没有权限访问该资源」，
        // 与真正的权限不足完全无法区分——系统里所有启停端点都是 PATCH，
        // 漏掉 PATCH 会让「启用/停用」全部失效且报错指向权限，极难排查。
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("X-Trace-Id"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
