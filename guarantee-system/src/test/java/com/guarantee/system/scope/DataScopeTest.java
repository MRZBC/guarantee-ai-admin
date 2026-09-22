package com.guarantee.system.scope;

import com.guarantee.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数据范围单元测试（TEST-03 / SYS-P-07 / SYS-P-09）。
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
    @DisplayName("受限范围只包含列出的机构")
    void restrictedContainsOnlyListed() {
        DataScope scope = DataScope.of(2L, 2, List.of(2L, 3L, 4L, 5L, 6L, 7L), "本省范围：浙江省");
        assertThat(scope.contains(3L)).isTrue();
        assertThat(scope.contains(8L)).isFalse();
        assertThat(scope.contains(null)).isFalse();
        assertThat(scope.description()).isEqualTo("本省范围：浙江省");
    }

    @Test
    @DisplayName("市级范围只有一个可见机构（仅本市）")
    void cityScopeIsSingleOrg() {
        DataScope scope = DataScope.singleOrg(3L, 3, "机构缺失（仅可见本机构）");
        assertThat(scope.isSingleOrg()).isTrue();
        assertThat(scope.contains(3L)).isTrue();
        assertThat(scope.contains(2L)).isFalse();
    }

    @Test
    @DisplayName("无机构归属时可见集合为空，不会误放开")
    void missingOrgYieldsEmptyScope() {
        DataScope scope = DataScope.singleOrg(null, null, "无机构归属（仅可见自己）");
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
