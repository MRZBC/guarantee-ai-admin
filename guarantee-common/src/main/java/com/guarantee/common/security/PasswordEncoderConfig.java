package com.guarantee.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码编码器的**唯一定义**（P-10 / D5）。
 *
 * <p>原定义在 {@code guarantee-auth} 的 {@code SecurityConfig} 里。P-10 起
 * {@code guarantee-system} 的 {@code UserService} 也需要编码密码（新建账号写入初始密码、
 * 自助改密、管理员重置），但：</p>
 * <ul>
 *   <li>依赖方向是 {@code guarantee-auth → guarantee-system}，system **不能**反向依赖 auth；</li>
 *   <li>也**不能**在 system 里再定义一个同类型 bean：{@code guarantee-web} 同时加载两个模块，
 *       按类型注入 {@code PasswordEncoder} 会抛 {@code NoUniqueBeanDefinitionException}。</li>
 * </ul>
 *
 * <p>因此把 bean 下沉到两端都依赖的 {@code guarantee-common}，并把 auth 里的原定义**移除**
 * （不是并存）。这样编码算法只有一处，将来升级（如换 Argon2）不会出现"只改了一处"的静默漂移。</p>
 *
 * <p>本类位于 {@code com.guarantee.common.security}，扫描根 {@code com.guarantee} 覆盖它
 * （同包下已有被扫到的 {@code TraceIdFilter}）。</p>
 */
@Configuration(proxyBeanMethods = false)
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
