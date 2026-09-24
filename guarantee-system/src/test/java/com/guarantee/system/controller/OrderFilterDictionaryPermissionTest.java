package com.guarantee.system.controller;

import com.guarantee.common.security.Permissions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.access.expression.SecurityExpressionRoot;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订单筛选下拉（机构 / 险种）的授权口径测试。
 *
 * <p><b>这条测试防的是什么</b>：{@code /system/orgs/options} 与
 * {@code /system/insurance-types/options} 同时是**订单页筛选下拉**的数据源，
 * 如果它们只挂 {@code system:*:view}，那么"有订单权限、没有系统配置权限"的角色
 * （例如自定义的「业务运营（无系统配置）」）一进投标/履约订单页就会吃到
 * {@code code=403「你当前没有该操作的权限」}：列表数据正常返回，筛选下拉却空着并弹报错。
 * 现场就是这么发生的——页面上机构和险种明明有数据，却筛不了。</p>
 *
 * <p><b>为什么直接求值 SpEL 而不是发 HTTP</b>：{@code @PreAuthorize} 的判定就是
 * "把表达式放在以 {@link SecurityExpressionRoot} 为根的上下文里求值"，这里复刻的正是这一步，
 * 因此能确定性地覆盖"哪种权限组合放行 / 拒绝"，不依赖数据库与演示账号的当前角色状态
 * （共享开发库里 user0005 的角色已经被改过，写死账号的断言会随数据漂移而失真）。</p>
 *
 * <p>Spring Security 7 把 {@link SecurityExpressionRoot} 的构造器标了 {@code @Deprecated}
 * （方法级鉴权整体转向 {@code AuthorizationManager}），但 {@code @PreAuthorize} 的 SpEL
 * 求值根仍是它，用别的替身反而测不到真实语义，因此这里显式压制该告警。</p>
 */
@SuppressWarnings("deprecation")
class OrderFilterDictionaryPermissionTest {

    private static final ExpressionParser PARSER = new SpelExpressionParser();

    // ==================================================================
    // 行为：表达式对"权限组合"的判定
    // ==================================================================

    @Test
    @DisplayName("只有 order:tender:view（无 system:org:view）→ 机构下拉放行：能看订单就能筛")
    void orgOptionsAllowedForOrderOnlyRole() {
        assertThat(granted(Permissions.ORG_OPTIONS_READ, Permissions.ORDER_TENDER_VIEW))
                .as("投标订单页的机构筛选不能因为没有系统配置权限而 403")
                .isTrue();
        assertThat(granted(Permissions.ORG_OPTIONS_READ, Permissions.ORDER_PERFORMANCE_VIEW))
                .as("履约订单页共用同一张表与同一个下拉，口径必须一致")
                .isTrue();
    }

    @Test
    @DisplayName("只有 system:org:view → 机构下拉仍放行（不能误伤机构配置页的用户）")
    void orgOptionsStillAllowedForConfigViewer() {
        assertThat(granted(Permissions.ORG_OPTIONS_READ, Permissions.ORG_VIEW)).isTrue();
    }

    @Test
    @DisplayName("险种下拉同口径：订单权限放行、险种配置只读放行")
    void insuranceOptionsFollowTheSameRule() {
        assertThat(granted(Permissions.INSURANCE_OPTIONS_READ, Permissions.ORDER_TENDER_VIEW)).isTrue();
        assertThat(granted(Permissions.INSURANCE_OPTIONS_READ, Permissions.ORDER_PERFORMANCE_VIEW)).isTrue();
        assertThat(granted(Permissions.INSURANCE_OPTIONS_READ, Permissions.INSURANCE_VIEW)).isTrue();
    }

    @Test
    @DisplayName("与业务无关的权限（如 ai:chat）→ 仍然拒绝：下拉没有被放宽成「登录即可」")
    void unrelatedPermissionStillDenied() {
        assertThat(granted(Permissions.ORG_OPTIONS_READ, Permissions.AI_CHAT))
                .as("放宽到任意登录用户会越过授权边界，这里刻意不放")
                .isFalse();
        assertThat(granted(Permissions.INSURANCE_OPTIONS_READ, Permissions.AI_CHAT)).isFalse();
        assertThat(granted(Permissions.ORG_OPTIONS_READ)).as("无任何权限同样拒绝").isFalse();
    }

    // ==================================================================
    // 接线：接口必须引用同一份共享表达式（防止改回去 / 只改一处）
    // ==================================================================

    @Test
    @DisplayName("两个 /options 接口引用共享表达式，且配置页其它读路径未被顺手放宽")
    void optionEndpointsUseSharedExpressions() throws Exception {
        assertThat(preAuthorize(OrgController.class, "options"))
                .as("机构下拉必须用 Permissions.ORG_OPTIONS_READ，而不是单一的 system:org:view")
                .isEqualTo(Permissions.ORG_OPTIONS_READ);
        assertThat(preAuthorize(InsuranceTypeController.class, "options"))
                .isEqualTo(Permissions.INSURANCE_OPTIONS_READ);

        // 反向护栏：同一个 Controller 里"配置数据"的读路径必须保持原样
        assertThat(preAuthorize(OrgController.class, "list", com.guarantee.system.dto.OrgDto.Query.class))
                .contains(Permissions.ORG_VIEW)
                .isNotEqualTo(Permissions.ORG_OPTIONS_READ);
        assertThat(preAuthorize(InsuranceTypeController.class, "list",
                com.guarantee.system.dto.InsuranceTypeDto.Query.class))
                .contains(Permissions.INSURANCE_VIEW)
                .isNotEqualTo(Permissions.INSURANCE_OPTIONS_READ);
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** 复刻 {@code @PreAuthorize} 的判定：以 {@link SecurityExpressionRoot} 为根求值。 */
    private static boolean granted(String expression, String... authorities) {
        List<GrantedAuthority> granted = Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new)
                .map(GrantedAuthority.class::cast)
                .toList();
        Authentication authentication =
                new TestingAuthenticationToken("tester", "n/a", granted.toArray(GrantedAuthority[]::new));
        StandardEvaluationContext context = new StandardEvaluationContext(
                new SecurityExpressionRoot<Object>(authentication) {
                });
        return Boolean.TRUE.equals(PARSER.parseExpression(expression).getValue(context, Boolean.class));
    }

    private static String preAuthorize(Class<?> type, String method, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method target = type.getMethod(method, parameterTypes);
        PreAuthorize annotation = target.getAnnotation(PreAuthorize.class);
        assertThat(annotation).as("%s#%s 必须有 @PreAuthorize（SYS-P-01）", type.getSimpleName(), method)
                .isNotNull();
        return annotation.value();
    }
}
