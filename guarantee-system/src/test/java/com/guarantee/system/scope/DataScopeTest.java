package com.guarantee.system.scope;

import com.guarantee.common.exception.BizException;
import com.guarantee.common.security.Roles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数据范围单元测试（TEST-03 / SYS-P-07 / SYS-P-09）。
 *
 * <p><b>本次重构后的语义</b>：机构已从用户与部门上移除，分级数据范围作为阶段一采用
 * <b>O3「显式全量」</b>——{@link DataScopeService#resolve(Long, List)} 不再接收 {@code orgId}，
 * 恒返回 {@code DataScope.all(...)}。{@link DataScope} 这个值对象本身的形状未变
 * （仍保留 {@code of} / {@code singleOrg} 作为阶段二重建授权模型时的构造入口），
 * 因此这里继续覆盖它的判定语义。</p>
 *
 * <p>原先"省级/市级用户只看本省/本市"的用例已随 {@code orgId} 输入源消失而删除，
 * 原因见 {@code DataScopeIntegrationTest} 类注释与
 * {@code docs/PLAN-移除用户与部门的机构归属.md} §2.3 / §8（C6、Q5）。</p>
 */
class DataScopeTest {

    @Test
    @DisplayName("全量范围包含任意机构")
    void unrestrictedContainsEverything() {
        DataScope scope = DataScope.all(1L, 1);
        assertThat(scope.contains(1L)).isTrue();
        assertThat(scope.contains(999L)).isTrue();
        assertThat(scope.contains(null)).isTrue();
        assertThat(scope.unrestricted()).isTrue();
        assertThat(scope.isSingleOrg()).isFalse();
    }

    @Test
    @DisplayName("O3 显式全量：任何角色（含只读角色）与空角色都解析为全量范围")
    void resolveAlwaysReturnsUnrestricted() {
        DataScopeService service = new DataScopeService(null);

        for (String role : List.of(Roles.ADMIN, Roles.OPERATOR, Roles.ANALYST, Roles.VIEWER)) {
            DataScope scope = service.resolve(42L, List.of(role));
            assertThat(scope.unrestricted()).as("%s 也应是全量（阶段一 O3）", role).isTrue();
            assertThat(scope.contains(999L)).as("%s 可见任意机构", role).isTrue();
        }

        // 机构不再是可见范围的输入：没有角色（或角色为空）同样是全量，
        // 绝不能退回"仅可见自己"，否则非 ADMIN 账号会集体失效
        assertThat(service.resolve(42L, List.of()).unrestricted()).as("空角色列表也是全量").isTrue();
        assertThat(service.resolve(42L, null).unrestricted()).as("角色为 null 也是全量").isTrue();
    }

    @Test
    @DisplayName("受限范围只包含列出的机构（阶段二重建授权模型时的构造语义）")
    void restrictedContainsOnlyListed() {
        DataScope scope = DataScope.of(2L, 2, List.of(2L, 3L, 4L, 5L, 6L, 7L), "本省范围：浙江省");
        assertThat(scope.contains(3L)).isTrue();
        assertThat(scope.contains(8L)).isFalse();
        assertThat(scope.contains(null)).isFalse();
        assertThat(scope.description()).isEqualTo("本省范围：浙江省");
    }

    @Test
    @DisplayName("单机构范围只包含该机构")
    void singleOrgScopeContainsOnlyItself() {
        DataScope scope = DataScope.singleOrg(3L, 3, "机构缺失（仅可见本机构）");
        assertThat(scope.isSingleOrg()).isTrue();
        assertThat(scope.contains(3L)).isTrue();
        assertThat(scope.contains(2L)).isFalse();
    }

    @Test
    @DisplayName("可见集合为空时不会误放开（fail-closed）")
    void emptyOrgIdsNeverOpenUp() {
        DataScope scope = DataScope.singleOrg(null, null, "无可见机构");
        assertThat(scope.contains(null)).isFalse();
        assertThat(scope.contains(1L)).isFalse();
    }

    @Test
    @DisplayName("越权校验的文案与「不存在」一致，不暴露存在性（SYS-P-09）")
    void outOfScopeMessageDoesNotLeakExistence() {
        assertThat(DataScopeService.OUT_OF_SCOPE_MESSAGE).contains("不存在").contains("数据范围");
        // 不得出现"存在但无权限"这类可区分的措辞
        assertThat(DataScopeService.OUT_OF_SCOPE_MESSAGE).doesNotContain("无权限").doesNotContain("仅");
    }

    @Test
    @DisplayName("QueryScope 与 DataScope 的转换保持语义")
    void queryScopeMirrorsDataScope() {
        assertThat(QueryScope.of(DataScope.all(1L, 1)).unrestricted).isTrue();
        QueryScope restricted = QueryScope.of(DataScope.of(2L, 2, List.of(2L, 3L), "本省"));
        assertThat(restricted.unrestricted).isFalse();
        assertThat(restricted.orgIds).containsExactly(2L, 3L);
        // 空 orgIds 也必须保持受限（mapper 会因此生成 1=0）
        assertThat(QueryScope.of(DataScope.of(null, null, List.of(), "空")).unrestricted).isFalse();
    }

    @Test
    @DisplayName("越权目标统一抛 404 语义的 BizException")
    void requireInScopeThrowsNotFound() {
        DataScopeService service = new DataScopeService(null);
        DataScope scope = DataScope.of(2L, 2, List.of(2L, 3L), "本省");
        // 范围内不抛
        service.requireInScope(scope, 3L);
        // 范围外抛，且文案无法区分"不存在"
        assertThatThrownBy(() -> service.requireInScope(scope, 8L))
                .isInstanceOf(BizException.class)
                .hasMessage(DataScopeService.OUT_OF_SCOPE_MESSAGE);
    }
}
