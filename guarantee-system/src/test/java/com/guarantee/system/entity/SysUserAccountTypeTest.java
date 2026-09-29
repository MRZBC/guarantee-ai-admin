package com.guarantee.system.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 账号类型归一的单元测试（T5-06）。
 *
 * <p>这条归一规则同时服务两条链路，方向必须都安全：</p>
 * <ul>
 *   <li><b>登录侧</b>：只有明确 {@code SERVICE} 才拒绝登录 —— NULL / 空 / 拼错的值都必须当成
 *       HUMAN，否则会凭空制造一批"密码对但登不进"的账号；</li>
 *   <li><b>机器凭据侧</b>：只有明确 {@code SERVICE} 才算机器身份 —— 未知值一律不通过
 *       （fail-closed），所以"归一成 HUMAN"在那边等价于"拒绝签发"。</li>
 * </ul>
 */
class SysUserAccountTypeTest {

    @Test
    @DisplayName("归一：NULL / 空 / 未知一律 HUMAN，只有明确 SERVICE（忽略大小写与空白）才是服务账号")
    void normalizesToHumanUnlessExplicitService() {
        assertThat(SysUser.normalizeAccountType(null)).isEqualTo(SysUser.ACCOUNT_TYPE_HUMAN);
        assertThat(SysUser.normalizeAccountType("")).isEqualTo(SysUser.ACCOUNT_TYPE_HUMAN);
        assertThat(SysUser.normalizeAccountType("   ")).isEqualTo(SysUser.ACCOUNT_TYPE_HUMAN);
        assertThat(SysUser.normalizeAccountType("human")).isEqualTo(SysUser.ACCOUNT_TYPE_HUMAN);
        assertThat(SysUser.normalizeAccountType("Human")).isEqualTo(SysUser.ACCOUNT_TYPE_HUMAN);
        // 拼错的值（真实世界里最常见的形态）：归 HUMAN，登录不受影响，机器凭据侧自然拒绝
        assertThat(SysUser.normalizeAccountType("SERVCE")).isEqualTo(SysUser.ACCOUNT_TYPE_HUMAN);
        assertThat(SysUser.normalizeAccountType("ROBOT")).isEqualTo(SysUser.ACCOUNT_TYPE_HUMAN);

        assertThat(SysUser.normalizeAccountType("SERVICE")).isEqualTo(SysUser.ACCOUNT_TYPE_SERVICE);
        assertThat(SysUser.normalizeAccountType("service")).isEqualTo(SysUser.ACCOUNT_TYPE_SERVICE);
        assertThat(SysUser.normalizeAccountType("  Service  ")).isEqualTo(SysUser.ACCOUNT_TYPE_SERVICE);
    }

    @Test
    @DisplayName("实体判定：字段缺失时 isServiceAccount() 为 false（不会把普通账号误拒登录）")
    void effectiveAccountTypeDefaultsToHuman() {
        SysUser user = new SysUser();
        assertThat(user.getAccountType()).isNull();
        assertThat(user.effectiveAccountType()).isEqualTo(SysUser.ACCOUNT_TYPE_HUMAN);
        assertThat(user.isServiceAccount()).isFalse();

        user.setAccountType(SysUser.ACCOUNT_TYPE_SERVICE);
        assertThat(user.effectiveAccountType()).isEqualTo(SysUser.ACCOUNT_TYPE_SERVICE);
        assertThat(user.isServiceAccount()).isTrue();

        user.setAccountType("robot");
        assertThat(user.effectiveAccountType()).isEqualTo(SysUser.ACCOUNT_TYPE_HUMAN);
        assertThat(user.isServiceAccount()).isFalse();
    }
}
