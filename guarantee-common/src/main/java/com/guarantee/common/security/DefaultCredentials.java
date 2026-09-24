package com.guarantee.common.security;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 初始密码的**单一来源**（P-10 / D1=C + D7=B）。
 *
 * <p>新建账号与管理员重置密码都写入这个值，并要求该用户下次登录必须修改。</p>
 *
 * <p><b>为什么放在 guarantee-common</b>：演示数据的种子在 {@code guarantee-web} 的
 * {@code DataInitializer}，而写密码的业务代码在 {@code guarantee-system} 的
 * {@code UserService}；依赖方向是 {@code web → system}，**system 引用不到
 * DataInitializer 的常量**。放 common 是两个模块唯一都能用的位置，从而让"同一个值"
 * 只有一处定义。</p>
 *
 * <p><b>关于这个值本身的风险（已决策接受）</b>：{@link #BUILT_IN_DEFAULT_PASSWORD}
 * 是写在源码里、且被 README 与多份文档反复引用的**公开值**。因此账号从"创建/重置完成"
 * 到"本人首次登录改密"之间存在一个窗口，知道该值的人可抢先登录并改密。缓解手段：
 * ① 强制闸门——改密前除改密/登出/读自己外一切请求被拒（服务端强制）；
 * ② 改密后撤销该用户全部令牌，且创建/重置/改密全部落审计，冒用可追溯到人；
 * ③ 可用配置 {@code app.security.default-password} 覆盖，换掉它该风险直接消失。</p>
 *
 * <p>启动时若仍在用内置值会打一条 WARN——与 {@code JwtTokenProvider} 打印密钥指纹、
 * 拒绝已知弱密钥是同一思路：让"生产还在用仓库里的值"这件事**可见**，而不是禁止它。</p>
 */
@Component
public class DefaultCredentials {

    private static final Logger log = LoggerFactory.getLogger(DefaultCredentials.class);

    /**
     * 内置默认密码（与演示数据种子一致）。
     *
     * <p>刻意与 {@code DataInitializer} 使用同一个常量：两份同值定义会漂移，
     * 而"用户以为改了一处、实际生效的是另一处"是最难排查的一类问题。</p>
     */
    public static final String BUILT_IN_DEFAULT_PASSWORD = "User@123";

    /** 配置键。默认值即内置值，因此不配置也能工作。 */
    public static final String CONFIG_KEY = "app.security.default-password";

    private final String defaultPassword;

    public DefaultCredentials(
            @Value("${" + CONFIG_KEY + ":" + BUILT_IN_DEFAULT_PASSWORD + "}") String defaultPassword) {
        this.defaultPassword = defaultPassword;
    }

    /** 新建账号与重置密码使用的初始密码。 */
    public String defaultPassword() {
        return defaultPassword;
    }

    /**
     * 启动自检：仍在用内置默认密码时给出可见提示。
     *
     * <p>只记 WARN 不失败——这是产品决策（D7=B 明确接受该值），把它变成启动失败
     * 会让"决策"与"构建"互相打架。</p>
     */
    @PostConstruct
    void warnIfBuiltIn() {
        if (BUILT_IN_DEFAULT_PASSWORD.equals(defaultPassword)) {
            log.warn("仍在使用内置默认密码（{}）：新建/重置账号的初始密码为公开值，"
                            + "存在「创建到首次登录改密」之间的抢注窗口。"
                            + "如需收紧，请配置 {} 覆盖它。",
                    BUILT_IN_DEFAULT_PASSWORD, CONFIG_KEY);
        } else {
            log.info("已通过 {} 覆盖内置默认密码（不再使用仓库公开值）", CONFIG_KEY);
        }
    }
}
