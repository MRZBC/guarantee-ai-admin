package com.guarantee.system.service;

import com.guarantee.system.ItMybatisConfig;
import com.guarantee.system.vo.OrgOptionVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订单筛选下拉「可选机构」口径的集成测试。
 *
 * <p><b>这条口径防的是什么</b>：订单页的「机构」筛选下拉原先按 {@code status = 1} 枚举，
 * 而机构**停用不拦"名下有订单"**（停用只拦启用中的下级机构，见
 * {@code OrgService.deleteBlockers} 的注释），于是"停用机构 + 历史订单"是常规可达状态：
 * 列表里还有这个机构的订单，筛选里却选不到它。</p>
 *
 * <p>可选集合因此是"**能筛出数据**"而不是"可用于新业务"：启用中未删除的机构，
 * **或**被订单引用的机构（不论已停用、已逻辑删除）。已删除这一支只可能来自
 * 数据库直连删除——应用层"被订单引用即拒绝删除"（{@code deleteBlockers}）。</p>
 *
 * <p><b>测试数据纪律</b>：夹具一律以 {@code __oft_} 前缀建行，{@code @AfterEach} 物理清理。</p>
 */
@SpringBootTest(classes = ItMybatisConfig.class)
@TestPropertySource(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/schema.sql"
})
class OrgFilterOptionsIntegrationTest {

    private static final String P = "__oft_";

    @Autowired
    private OrgService orgService;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM tender_order WHERE order_no LIKE ?", P + "%");
        jdbc.update("DELETE FROM performance_order WHERE order_no LIKE ?", P + "%");
        jdbc.update("DELETE FROM sys_org WHERE org_code LIKE ?", P + "%");
    }

    // ==================================================================
    // 核心：停用 / 已删除但有订单 → 必须还能筛
    // ==================================================================

    @Test
    @DisplayName("停用但名下有投标订单 → 仍在下拉里，且带回 status=0 供前端标注「已停用」")
    void disabledButReferencedByTenderOrderStaysSelectable() {
        Long disabled = insertOrg(P + "off_used", 0);
        insertTenderOrder(P + "t1", disabled);

        OrgOptionVO option = optionById(disabled);

        assertThat(option.getStatus())
                .as("停用标记必须回传：前端据此把选项标注为「已停用」")
                .isZero();
    }

    @Test
    @DisplayName("停用但名下有履约订单 → 同样在下拉里（投标/履约两个方向都要覆盖）")
    void disabledButReferencedByPerformanceOrderStaysSelectable() {
        Long disabled = insertOrg(P + "off_perf", 0);
        insertPerformanceOrder(P + "f1", disabled);

        assertThat(optionIds()).contains(disabled);
    }

    @Test
    @DisplayName("已逻辑删除但名下有订单 → 仍在下拉里（直连删除的兜底），并带回 isDeleted=1")
    void softDeletedButReferencedStaysSelectable() {
        Long deleted = insertOrg(P + "deleted_used", 1);
        insertTenderOrder(P + "t2", deleted);
        softDeleteOrg(deleted);

        OrgOptionVO option = optionById(deleted);

        assertThat(option.getIsDeleted())
                .as("删除标记必须回传：前端据此把选项标注为「已删除」")
                .isEqualTo(1);
    }

    // ==================================================================
    // 护栏：不该出现在候选里的行
    // ==================================================================

    @Test
    @DisplayName("停用且没有订单引用 → 不出现（避免候选里塞满选了必得空列表的项）")
    void disabledWithoutOrdersIsNotSelectable() {
        Long unused = insertOrg(P + "off_unused", 0);

        assertThat(optionIds()).doesNotContain(unused);
    }

    @Test
    @DisplayName("已逻辑删除且没有订单引用 → 不出现")
    void softDeletedWithoutOrdersIsNotSelectable() {
        Long deleted = insertOrg(P + "deleted_unused", 1);
        softDeleteOrg(deleted);

        assertThat(optionIds()).doesNotContain(deleted);
    }

    @Test
    @DisplayName("启用中且未删除的机构一律可选，与有没有订单无关")
    void enabledIsAlwaysSelectable() {
        Long enabled = insertOrg(P + "on", 1);

        assertThat(optionIds()).contains(enabled);
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private List<Long> optionIds() {
        return orgService.listFilterOptions().stream().map(OrgOptionVO::getId).toList();
    }

    private OrgOptionVO optionById(Long id) {
        return orgService.listFilterOptions().stream()
                .filter(o -> id.equals(o.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("机构 " + id
                        + " 必须出现在筛选下拉里：名下有历史订单就不能筛不到"));
    }

    private Long insertOrg(String code, int status) {
        jdbc.update("INSERT INTO sys_org (org_code, org_name, region_code, region_name, org_level,"
                + " parent_id, status, sort_no) VALUES (?, ?, '000000', '未指定', 3, 0, ?, 0)",
                code, "夹具-" + code, status);
        return jdbc.queryForObject("SELECT id FROM sys_org WHERE org_code = ?", Long.class, code);
    }

    /** 模拟"数据库直连删除"（应用层对被订单引用的机构会拒绝删除，见 deleteBlockers）。 */
    private void softDeleteOrg(Long id) {
        jdbc.update("UPDATE sys_org SET is_deleted = 1, deleted_at = NOW(6), deleted_by = 'DB'"
                + " WHERE id = ?", id);
    }

    private void insertTenderOrder(String orderNo, Long orgId) {
        jdbc.update("""
                INSERT INTO tender_order (order_no, project_id, enterprise_id, insurance_type_id, org_id,
                                          region_code, region_name, guarantee_amount, premium_amount, premium_rate,
                                          status, apply_date)
                VALUES (?, ?, ?, ?, ?, '000000', '未指定', 100, 1, 0.01, 'DRAFT', CURDATE())
                """, orderNo, projectId(), enterpriseId(), insuranceTypeId(), orgId);
    }

    private void insertPerformanceOrder(String orderNo, Long orgId) {
        jdbc.update("""
                INSERT INTO performance_order (order_no, contract_no, project_id, enterprise_id,
                                               insurance_type_id, org_id, region_code, region_name,
                                               guarantee_amount, premium_amount, premium_rate,
                                               status, apply_date)
                VALUES (?, ?, ?, ?, ?, ?, '000000', '未指定', 100, 1, 0.01, 'DRAFT', CURDATE())
                """, orderNo, "C-" + orderNo, projectId(), enterpriseId(), insuranceTypeId(), orgId);
    }

    /** 订单夹具必须挂在真实存在的项目 / 企业 / 险种上，否则外键或语义会失真。 */
    private Long projectId() {
        return jdbc.queryForObject("SELECT id FROM project LIMIT 1", Long.class);
    }

    private Long enterpriseId() {
        return jdbc.queryForObject("SELECT id FROM enterprise LIMIT 1", Long.class);
    }

    private Long insuranceTypeId() {
        return jdbc.queryForObject("SELECT id FROM insurance_type LIMIT 1", Long.class);
    }
}
