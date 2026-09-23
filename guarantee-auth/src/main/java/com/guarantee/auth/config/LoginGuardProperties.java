package com.guarantee.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * 登录失败防爆破配置，前缀 {@code guarantee.auth.login-guard}（AUTH-01）。
 *
 * <p>时长一律用 {@link Duration}：既支持 {@code 15m} / {@code 30s} 这样的可读写法，
 * 也让集成测试能注入秒级窗口而不必 {@code Thread.sleep} 十几分钟。</p>
 *
 * <p><b>为什么 {@code ignoreUnknownFields = false}</b>（本类实测踩过这个坑）：
 * Spring Boot 默认会**静默忽略**绑定不上的键。此前 {@code application.yml} 里写的是
 * {@code failure-window-minutes}（按字段名猜的），而本类绑的是 {@code failureWindow}
 * （键名 {@code failure-window}）——两者对不上，于是三个时长配置**一个都没生效**，
 * 只是 Java 默认值恰好也是 15 分钟，所以谁都没发现。开启本项后这类拼写错误会直接
 * 导致启动失败并点名是哪个键。</p>
 */
@ConfigurationProperties(prefix = "guarantee.auth.login-guard", ignoreUnknownFields = false)
public class LoginGuardProperties {

    /** 总开关。关闭后登录不再计数与锁定。 */
    private boolean enabled = true;

    /** 账号维度失败阈值。 */
    private int maxFailuresPerUser = 5;

    /**
     * IP 维度失败阈值。
     *
     * <p>刻意高于账号维度：办公网 / NAT 出口下大量正常用户共用同一个公网 IP，
     * 阈值取 5 会让一个人输错几次就锁住整栋楼。</p>
     */
    private int maxFailuresPerIp = 20;

    /** 失败计数窗口：窗口内累计达到阈值即触发锁定。 */
    @DurationUnit(ChronoUnit.MINUTES)
    private Duration failureWindow = Duration.ofMinutes(15);

    /** 账号锁定时长。 */
    @DurationUnit(ChronoUnit.MINUTES)
    private Duration userLock = Duration.ofMinutes(15);

    /** IP 锁定时长。 */
    @DurationUnit(ChronoUnit.MINUTES)
    private Duration ipLock = Duration.ofMinutes(15);

    /**
     * 是否信任 {@code X-Forwarded-For} 取真实客户端 IP。
     *
     * <p><b>默认 false，且不可随意开启</b>：该头可被客户端任意伪造。若应用并非部署在
     * 可信反向代理之后却开启了它，攻击者每次请求换一个假 IP 即可完全绕过 IP 维度。
     * 关闭时直接用 {@code getRemoteAddr()}——在反向代理后它恒为代理地址，
     * IP 维度会退化为全局计数（阈值 20 仍高于账号维度的 5，因此不会先于账号锁定触发）。</p>
     */
    private boolean trustForwardedHeader = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxFailuresPerUser() {
        return maxFailuresPerUser;
    }

    public void setMaxFailuresPerUser(int maxFailuresPerUser) {
        this.maxFailuresPerUser = maxFailuresPerUser;
    }

    public int getMaxFailuresPerIp() {
        return maxFailuresPerIp;
    }

    public void setMaxFailuresPerIp(int maxFailuresPerIp) {
        this.maxFailuresPerIp = maxFailuresPerIp;
    }

    public Duration getFailureWindow() {
        return failureWindow;
    }

    public void setFailureWindow(Duration failureWindow) {
        this.failureWindow = failureWindow;
    }

    public Duration getUserLock() {
        return userLock;
    }

    public void setUserLock(Duration userLock) {
        this.userLock = userLock;
    }

    public Duration getIpLock() {
        return ipLock;
    }

    public void setIpLock(Duration ipLock) {
        this.ipLock = ipLock;
    }

    public boolean isTrustForwardedHeader() {
        return trustForwardedHeader;
    }

    public void setTrustForwardedHeader(boolean trustForwardedHeader) {
        this.trustForwardedHeader = trustForwardedHeader;
    }
}
