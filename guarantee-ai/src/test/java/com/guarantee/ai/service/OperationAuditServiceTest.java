package com.guarantee.ai.service;

import com.guarantee.ai.entity.AiOperationAudit;
import com.guarantee.ai.mapper.AiOperationAuditMapper;
import com.guarantee.ai.mapper.OperationAuditQuery;
import com.guarantee.common.exception.BizException;
import com.guarantee.common.security.SensitiveFieldMasker;
import com.guarantee.system.scope.DataScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 操作审计服务单元测试（TEST-15 / TEST-19 / TEST-22 / TEST-23 / SYS-A-*）。
 *
 * <p>本类集中验证审计最容易被做错的三件事：写入前脱敏、查询护栏、范围规则。
 * 都是安全/合规性质，必须在单元层就有快速反馈。</p>
 */
class OperationAuditServiceTest {

    private AiOperationAuditMapper auditMapper;
    private OperationAuditService service;

    @BeforeEach
    void setUp() {
        auditMapper = mock(AiOperationAuditMapper.class);
        service = new OperationAuditService(auditMapper, new ObjectMapper());
    }

    private static final OperationAuditService.OperatorContext OPERATOR =
            new OperationAuditService.OperatorContext(7L, "admin", "超级管理员", 1L);

    // ==================================================================
    // 写入前脱敏（D-4 / SYS-A-02a / SYS-A-02b / TEST-15）
    // ==================================================================

    @Test
    @DisplayName("审计写入前脱敏：before/after 中查不到手机号与邮箱明文（TEST-15）")
    void shouldMaskSensitiveValuesBeforePersisting() {
        OperationAuditService.AuditEntry entry = OperationAuditService.AuditEntry.of(
                "AI", "UPDATE", "USER", 123L, "user0123",
                Map.of("realName", "张力", "phone", "13812345678", "email", "user0123@guarantee.com"),
                Map.of("realName", "张力", "phone", "13900000000", "email", "new@guarantee.com"),
                "SUCCESS");

        service.record(entry, OPERATOR, 9L, 5L, "trace-1");

        ArgumentCaptor<AiOperationAudit> captor = ArgumentCaptor.forClass(AiOperationAudit.class);
        verify(auditMapper).insert(captor.capture());
        AiOperationAudit saved = captor.getValue();

        String before = saved.getBeforeValue();
        String after = saved.getAfterValue();
        assertThat(before).as("手机号与邮箱明文绝不能进入审计表").doesNotContain("13812345678");
        assertThat(before).doesNotContain("user0123@guarantee.com");
        assertThat(after).doesNotContain("13900000000").doesNotContain("new@guarantee.com");
        // 但要能看出字段发生过变更
        assertThat(saved.getChangedFields()).contains("phone").contains("email");
        // 非敏感字段保留原值
        assertThat(before).contains("张力");
    }

    @Test
    @DisplayName("脱敏与操作者角色无关：ADMIN 写入同样脱敏（D-4）")
    void maskingIsRoleIndependent() {
        OperationAuditService.AuditEntry entry = OperationAuditService.AuditEntry.of(
                "WEB", "UPDATE", "USER", 1L, "admin",
                Map.of("phone", "13800001111"), Map.of("phone", "13800002222"), "SUCCESS");

        // 用 ADMIN 的身份写入
        OperationAuditService.OperatorContext admin =
                new OperationAuditService.OperatorContext(1L, "admin", "超级管理员", 1L);
        service.record(entry, admin, null, null, "trace-2");

        ArgumentCaptor<AiOperationAudit> captor = ArgumentCaptor.forClass(AiOperationAudit.class);
        verify(auditMapper).insert(captor.capture());
        assertThat(captor.getValue().getBeforeValue()).doesNotContain("13800001111");
        assertThat(captor.getValue().getAfterValue()).doesNotContain("13800002222");
        assertThat(captor.getValue().getBeforeValue())
                .contains(SensitiveFieldMasker.CHANGED_PLACEHOLDER);
    }

    @Test
    @DisplayName("结构化快照：不是一句描述，而是字段级 JSON（SYS-A-02）")
    void snapshotShouldBeStructured() {
        OperationAuditService.AuditEntry entry = OperationAuditService.AuditEntry.of(
                "AI", "DISABLE", "ORG", 13L, "广东省第2保函运营机构",
                Map.of("status", 1), Map.of("status", 0), "SUCCESS");

        service.record(entry, OPERATOR, null, null, "trace-3");

        ArgumentCaptor<AiOperationAudit> captor = ArgumentCaptor.forClass(AiOperationAudit.class);
        verify(auditMapper).insert(captor.capture());
        assertThat(captor.getValue().getBeforeValue()).isEqualTo("{\"status\":1}");
        assertThat(captor.getValue().getAfterValue()).isEqualTo("{\"status\":0}");
        assertThat(captor.getValue().getChangedFields()).isEqualTo("status");
    }

    @Test
    @DisplayName("单行快照超 8KB 时被截断并标记 truncated（SYS-A-16 / TEST-22）")
    void oversizedSnapshotShouldBeTruncated() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            huge.append("x".repeat(10));
        }
        OperationAuditService.AuditEntry entry = OperationAuditService.AuditEntry.of(
                "AI", "UPDATE", "USER", 1L, "user0001",
                Map.of("description", huge.toString()), Map.of("description", "短"), "SUCCESS");

        service.record(entry, OPERATOR, null, null, "trace-4");

        ArgumentCaptor<AiOperationAudit> captor = ArgumentCaptor.forClass(AiOperationAudit.class);
        verify(auditMapper).insert(captor.capture());
        assertThat(captor.getValue().getTruncated()).as("超限必须显式标记，不能静默膨胀").isEqualTo(1);
        assertThat(captor.getValue().getBeforeValue()).isNull();
    }

    // ==================================================================
    // 查询护栏（SYS-A-17 / TEST-23）
    // ==================================================================

    @Test
    @DisplayName("缺时间条件时明确拒绝，而不是全量扫描（TEST-23）")
    void shouldRejectQueryWithoutTimeRange() {
        OperationAuditQuery query = new OperationAuditQuery();
        assertThatThrownBy(() -> service.query(query, true, DataScope.all(1L, 1)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("必须提供明确的时间区间");
    }

    @Test
    @DisplayName("时间跨度超过 90 天时拒绝并提示收窄（SYS-A-17）")
    void shouldRejectRangeOverNinetyDays() {
        OperationAuditQuery query = new OperationAuditQuery();
        query.setStartDate(LocalDateTime.now().minusDays(120));
        query.setEndDate(LocalDateTime.now());
        assertThatThrownBy(() -> service.query(query, true, DataScope.all(1L, 1)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("90 天");
    }

    @Test
    @DisplayName("startDate 晚于 endDate 时拒绝")
    void shouldRejectInvertedRange() {
        OperationAuditQuery query = new OperationAuditQuery();
        query.setStartDate(LocalDateTime.now());
        query.setEndDate(LocalDateTime.now().minusDays(1));
        assertThatThrownBy(() -> service.query(query, true, DataScope.all(1L, 1)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能晚于");
    }

    @Test
    @DisplayName("90 天以内的查询正常执行，并命中条数上限收敛")
    void shouldAllowValidRange() {
        OperationAuditQuery query = new OperationAuditQuery();
        query.setStartDate(LocalDateTime.now().minusDays(7));
        query.setEndDate(LocalDateTime.now());
        query.setLimit(500);
        when(auditMapper.countByQuery(any())).thenReturn(3L);
        when(auditMapper.selectPage(any(), anyInt(), anyInt())).thenReturn(List.of());

        var page = service.query(query, true, DataScope.all(1L, 1));
        assertThat(page.total()).isEqualTo(3L);
        // 上限收敛到 200（SYS-Q-06）
        verify(auditMapper).selectPage(any(), anyInt(), org.mockito.ArgumentMatchers.eq(200));
    }

    // ==================================================================
    // 范围规则（SYS-A-10 / TEST-19）
    // ==================================================================

    @Test
    @DisplayName("非 ADMIN 查询 ROLE 类审计被拒绝，而不是返回残缺结果（SYS-A-10）")
    void nonAdminShouldBeRejectedForRoleAudit() {
        OperationAuditQuery query = new OperationAuditQuery();
        query.setStartDate(LocalDateTime.now().minusDays(1));
        query.setEndDate(LocalDateTime.now());
        query.setTargetType("ROLE");

        DataScope scope = DataScope.of(2L, 2, List.of(2L, 3L), "本省范围：浙江省");
        assertThatThrownBy(() -> service.query(query, false, scope))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅超级管理员可见");
    }

    @Test
    @DisplayName("非 ADMIN 查询审计：阶段一 O3 下不再按机构收敛，但仍然限制目标类型（SYS-A-10）")
    void nonAdminUserAuditIsTargetTypeScoped() {
        OperationAuditQuery query = new OperationAuditQuery();
        query.setStartDate(LocalDateTime.now().minusDays(1));
        query.setEndDate(LocalDateTime.now());
        when(auditMapper.countByQuery(any())).thenReturn(0L);

        // 阶段一 O3：resolve() 恒返回全量范围
        DataScope scope = DataScope.all(1L, 1);
        service.query(query, false, scope);

        // 静默失效的回归点：O3 下 scope.unrestricted() 恒为 true → visibleOrgIds 恒为 null。
        // 若此处仍置 restrictByOrg=true，Mapper 的 <when visibleOrgIds 非空> 不成立就会走
        // <otherwise> 注入 `AND 1 = 0`，导致非 ADMIN 查询操作审计**恒为 0 条**——
        // 编译期完全看不出来，只能靠这条断言守住。
        assertThat(scope.unrestricted()).as("阶段一 O3：数据范围恒为全量").isTrue();
        assertThat(query.isRestrictByOrg()).as("全量范围下不得再按 operator_org_id 收敛").isFalse();
        assertThat(query.getVisibleOrgIds()).as("机构收敛关闭后不得残留可见机构列表").isNull();

        // 非 ADMIN 仍然有效的限制：只能看有机构归属的目标类型
        // （显式点名 ROLE/PERMISSION 时的 FORBIDDEN 由 nonAdminShouldBeRejectedForRoleAudit 覆盖）
        assertThat(query.getAllowedTargetTypes()).as("非 ADMIN 只能看有机构归属的目标类型")
                .containsExactlyInAnyOrder("USER", "ORG", "DEPT");
    }

    @Test
    @DisplayName("ADMIN 查询不受机构限制，也不限制目标类型")
    void adminQueryIsUnrestricted() {
        OperationAuditQuery query = new OperationAuditQuery();
        query.setStartDate(LocalDateTime.now().minusDays(1));
        query.setEndDate(LocalDateTime.now());
        when(auditMapper.countByQuery(any())).thenReturn(0L);

        service.query(query, true, DataScope.all(1L, 1));

        assertThat(query.isRestrictByOrg()).isFalse();
        assertThat(query.getVisibleOrgIds()).isNull();
        assertThat(query.getAllowedTargetTypes()).isNull();
    }

    // ==================================================================
    // 容量可观测（SYS-A-18）
    // ==================================================================

    @Test
    @DisplayName("容量巡检指标可读，无数据时返回 0 / null 而不是抛异常")
    void capacityMetricsShouldBeReadable() {
        when(auditMapper.countAll()).thenReturn(null);
        when(auditMapper.selectOldestOperatedAt()).thenReturn(null);
        assertThat(service.onlineRows()).isZero();
        assertThat(service.oldestOperatedAt()).isNull();
    }

    @Test
    @DisplayName("审计表只增不改不删：Mapper 接口不得出现 update / delete 审计记录的方法")
    void auditMapperMustNotExposeMutationOfRecords() {
        List<String> methods = java.util.Arrays.stream(AiOperationAuditMapper.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName).toList();
        // deleteBefore 只用于归档（SYS-A-14），不属于"修改审计记录"
        assertThat(methods).as("不允许存在更新审计记录的方法（SYS-A-04）")
                .noneMatch(name -> name.startsWith("update"));
        assertThat(methods).contains("insert", "deleteBefore");
    }
}
