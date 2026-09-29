package com.guarantee.system.service;

import com.guarantee.system.ItMybatisConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code sys_user.account_type} 的真实库集成测试（T5-06）。
 *
 * <p>覆盖三件事：① 四个实体语句真的把 {@code account_type} 映射出来了；
 * ② 机器身份最小读（{@code selectAccountTypeById} / {@code selectIdentityById}）给出
 * "存在 + 启用 + 服务账号"三要素；③ 存量兼容——空串/未知值按 HUMAN，逻辑删除的行对机器链路不可见。</p>
 *
 * <p><b>为什么必须打真库</b>：这些都是"显式列清单 + 手写 WHERE"的 SQL，
 * 单测能证明 XML 里有这行字，证明不了它跑得通（列名拼错、拦截器注入 is_deleted 等）。
 * 本仓库已有先例：列名写错一次要等到运行期才炸。</p>
 *
 * <p><b>测试数据纪律</b>：夹具一律 {@code __sat_} 前缀建行，{@code @AfterEach} 物理删除，
 * 不碰任何演示数据（311 个真实用户全是 HUMAN，改错一个就是登录故障）。</p>
 */
@SpringBootTest(classes = ItMybatisConfig.class)
@TestPropertySource(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/schema.sql"
})
class SysUserAccountTypeIntegrationTest {

    private static final String P = "__sat_";

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> created = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long id : created) {
            jdbc.update("DELETE FROM sys_user WHERE id = ?", id);
        }
        created.clear();
    }

    @Test
    @DisplayName("服务账号：类型可读、是机器身份（存在 + 启用 + SERVICE）")
    void serviceAccountIsMachineIdentity() {
        long id = insert(P + "svc_" + System.nanoTime(), "SERVICE", 1);

        assertThat(userService.getAccountType(id)).isEqualTo("SERVICE");
        assertThat(userService.isServiceAccount(id)).isTrue();

        UserService.AccountIdentity identity = userService.getAccountIdentity(id);
        assertThat(identity).isNotNull();
        assertThat(identity.serviceAccount()).isTrue();
        assertThat(identity.enabled()).isTrue();
        assertThat(identity.machineIdentity()).as("可签发机器凭据").isTrue();
    }

    @Test
    @DisplayName("HUMAN 账号不是机器身份（MCP 签发必须拒绝它）")
    void humanAccountIsNotMachineIdentity() {
        long id = insert(P + "human_" + System.nanoTime(), "HUMAN", 1);

        assertThat(userService.getAccountType(id)).isEqualTo("HUMAN");
        assertThat(userService.isServiceAccount(id)).isFalse();

        UserService.AccountIdentity identity = userService.getAccountIdentity(id);
        assertThat(identity).isNotNull();
        assertThat(identity.serviceAccount()).isFalse();
        assertThat(identity.enabled()).isTrue();
        assertThat(identity.machineIdentity()).as("人类账号不可签发机器凭据").isFalse();
    }

    @Test
    @DisplayName("存量兼容：空串 / 未知取值按 HUMAN（登录放行，机器凭据侧自然拒绝）")
    void blankAndUnknownAccountTypeFallBackToHuman() {
        long blank = insert(P + "blank_" + System.nanoTime(), "", 1);
        assertThat(userService.getAccountType(blank)).isEqualTo("HUMAN");
        assertThat(userService.getAccountIdentity(blank).serviceAccount()).isFalse();

        long unknown = insert(P + "robot_" + System.nanoTime(), "ROBOT", 1);
        assertThat(userService.getAccountType(unknown)).isEqualTo("HUMAN");
        assertThat(userService.isServiceAccount(unknown)).isFalse();
        assertThat(userService.getAccountIdentity(unknown).machineIdentity())
                .as("未知类型不等于 SERVICE ⇒ 机器凭据 fail-closed").isFalse();

        // 说明：本库 account_type 是 NOT NULL DEFAULT 'HUMAN'，因此"NULL"这一态在库里不可达；
        // NULL 的归一由 SysUserAccountTypeTest 覆盖（实体层）。
    }

    @Test
    @DisplayName("停用的服务账号仍能被识别为 SERVICE，但不是可用的机器身份")
    void disabledServiceAccountIsNotUsable() {
        long id = insert(P + "svc_off_" + System.nanoTime(), "SERVICE", 0);

        UserService.AccountIdentity identity = userService.getAccountIdentity(id);
        assertThat(identity.serviceAccount()).isTrue();
        assertThat(identity.enabled()).isFalse();
        assertThat(identity.machineIdentity()).isFalse();
    }

    @Test
    @DisplayName("逻辑删除的账号对机器链路不可见（类型读不到、身份为 null）")
    void deletedAccountIsInvisibleToMachineChain() {
        long id = insert(P + "svc_del_" + System.nanoTime(), "SERVICE", 1);
        jdbc.update("UPDATE sys_user SET is_deleted = 1, deleted_at = CURRENT_TIMESTAMP(6) WHERE id = ?", id);

        assertThat(userService.getAccountIdentity(id)).as("已删除 ⇒ 没有机器身份").isNull();
        // 类型读接口也过滤了逻辑删除：读不到 → 归一为 HUMAN（不会凭残留类型放行）
        assertThat(userService.getAccountType(id)).isEqualTo("HUMAN");
        assertThat(userService.isServiceAccount(id)).isFalse();
    }

    @Test
    @DisplayName("登录链路用的实体语句也映射了 account_type（AuthService 才能判服务账号）")
    void entityByUsernameCarriesAccountType() {
        String username = P + "svc_login_" + System.nanoTime();
        insert(username, "SERVICE", 1);

        assertThat(userService.getEntityByUsername(username).isServiceAccount())
                .as("selectByUsername 没映射 account_type 的话，登录侧强校验会静默失效")
                .isTrue();
    }

    private long insert(String username, String accountType, int status) {
        jdbc.update("""
                INSERT INTO sys_user (username, password, real_name, dept_id, status, account_type)
                VALUES (?, ?, ?, ?, ?, ?)
                """, username, "$2a$10$0123456789012345678901234567890123456789012345678901", "账号类型测试",
                1001L, status, accountType);
        Long id = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
        assertThat(id).isNotNull();
        created.add(id);
        return id;
    }
}
