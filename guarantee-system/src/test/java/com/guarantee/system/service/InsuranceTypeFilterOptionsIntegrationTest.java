package com.guarantee.system.service;

import com.guarantee.system.ItMybatisConfig;
import com.guarantee.system.vo.InsuranceTypeVO;
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
 * 订单筛选下拉「可选险种」口径的集成测试。
 *
 * <p><b>这条口径防的是什么</b>：订单页的「险种」筛选下拉与列表用的是同一份数据，
 * 但两者的"可见性"来源不同——列表按订单展示险种名，下拉按<em>险种状态</em>枚举。
 * 只要下拉用了 {@code status = 1} 这一条口径，就会出现"列表里全是这个险种、
 * 筛选里却选不到"：现场「投标保函（标准）」名下 4.4 万条投标订单，因为被停用而缺席下拉。</p>
 *
 * <p>因此可选集合是"**能筛出数据**"而不是"可用于新业务"：启用中未删除的险种，
 * **或**被订单引用的险种（不论已停用、已逻辑删除）。已删除这一支只可能来自
 * 数据库直连删除——应用层对"被订单引用的险种"是禁止删除的（LD-T10）。</p>
 *
 * <p><b>测试数据纪律</b>：夹具一律以 {@code __lft_} 前缀建行，{@code @AfterEach} 物理清理，
 * 与 LD-T 系列一致，避免影响演示数据相关的既有断言。</p>
 */
@SpringBootTest(classes = ItMybatisConfig.class)
@TestPropertySource(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/schema.sql"
})
class InsuranceTypeFilterOptionsIntegrationTest {

    private static final String P = "__lft_";

    @Autowired
    private InsuranceTypeService insuranceTypeService;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM tender_order WHERE order_no LIKE ?", P + "%");
        jdbc.update("DELETE FROM performance_order WHERE order_no LIKE ?", P + "%");
        jdbc.update("DELETE FROM insurance_type WHERE type_code LIKE ?", P + "%");
    }

    // ==================================================================
    // 核心：停用但仍有历史订单引用 → 必须还能筛
    // ==================================================================

    @Test
    @DisplayName("停用但仍被投标订单引用 → 仍在下拉里，且带回 status=0 供前端标注「已停用」")
    void disabledButReferencedByTenderOrderStaysSelectable() {
        Long disabled = insertType(P + "off_used", "TENDER", 0);
        insertTenderOrder(P + "t1", disabled);

        List<InsuranceTypeVO> options = insuranceTypeService.listFilterOptions();

        InsuranceTypeVO option = options.stream().filter(o -> disabled.equals(o.id())).findFirst()
                .orElseThrow(() -> new AssertionError(
                        "停用但仍被订单引用的险种必须出现在筛选下拉里，否则列表有数据却筛不了"));
        assertThat(option.status())
                .as("停用标记必须回传：前端据此把选项标注为「已停用」，避免误解为还能承保")
                .isZero();
    }

    @Test
    @DisplayName("停用但仍被履约订单引用 → 同样在下拉里（投标/履约两个方向都要覆盖）")
    void disabledButReferencedByPerformanceOrderStaysSelectable() {
        Long disabled = insertType(P + "off_perf", "PERFORMANCE", 0);
        insertPerformanceOrder(P + "f1", disabled);

        assertThat(optionIds()).contains(disabled);
    }

    // ==================================================================
    // 护栏：不该出现在候选里的行
    // ==================================================================

    @Test
    @DisplayName("停用且没有任何订单引用 → 不出现（停用就该退出候选，避免选了必得空列表）")
    void disabledWithoutOrdersIsNotSelectable() {
        Long unused = insertType(P + "off_unused", "TENDER", 0);

        assertThat(optionIds()).doesNotContain(unused);
    }

    @Test
    @DisplayName("已逻辑删除但仍被订单引用 → 仍在下拉里（直连删除的兜底：历史数据的名字还在列表上）")
    void softDeletedButReferencedStaysSelectable() {
        Long deleted = insertType(P + "deleted_used", "TENDER", 1);
        insertTenderOrder(P + "t2", deleted);
        softDeleteType(deleted);

        assertThat(optionIds())
                .as("被订单引用的险种若被直连删除，筛选里仍必须能选到它，否则那些订单只能靠肉眼找")
                .contains(deleted);
    }

    @Test
    @DisplayName("已逻辑删除且无订单引用 → 不出现（候选里不该留一条没数据又已删除的项）")
    void softDeletedWithoutOrdersIsNotSelectable() {
        Long deleted = insertType(P + "deleted_unused", "TENDER", 1);
        softDeleteType(deleted);

        assertThat(optionIds()).doesNotContain(deleted);
    }

    @Test
    @DisplayName("启用中的险种一律可选，与有没有订单无关")
    void enabledIsAlwaysSelectable() {
        Long enabled = insertType(P + "on", "TENDER", 1);

        assertThat(optionIds()).contains(enabled);
    }

    @Test
    @DisplayName("结果按 id 升序：下拉顺序稳定，不随 SQL 执行计划漂移")
    void optionsAreOrderedById() {
        insertType(P + "order_a", "TENDER", 1);
        insertType(P + "order_b", "TENDER", 1);

        assertThat(optionIds()).isSorted();
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private List<Long> optionIds() {
        return insuranceTypeService.listFilterOptions().stream().map(InsuranceTypeVO::id).toList();
    }

    private Long insertType(String code, String category, int status) {
        jdbc.update("INSERT INTO insurance_type (type_code, type_name, category, base_rate, status)"
                + " VALUES (?, ?, ?, 0.01, ?)", code, "夹具-" + code, category, status);
        return jdbc.queryForObject("SELECT id FROM insurance_type WHERE type_code = ?", Long.class, code);
    }

    /** 模拟"数据库直连删除"（应用层对已被订单引用的险种会拒绝删除，见 LD-T10）。 */
    private void softDeleteType(Long id) {
        jdbc.update("UPDATE insurance_type SET is_deleted = 1, deleted_at = NOW(6), deleted_by = 'DB'"
                + " WHERE id = ?", id);
    }

    private void insertTenderOrder(String orderNo, Long insuranceTypeId) {
        jdbc.update("""
                INSERT INTO tender_order (order_no, project_id, enterprise_id, insurance_type_id, org_id,
                                          region_code, region_name, guarantee_amount, premium_amount, premium_rate,
                                          status, apply_date)
                VALUES (?, ?, ?, ?, ?, '000000', '未指定', 100, 1, 0.01, 'DRAFT', CURDATE())
                """, orderNo, projectId(), enterpriseId(), insuranceTypeId, orgId());
    }

    private void insertPerformanceOrder(String orderNo, Long insuranceTypeId) {
        jdbc.update("""
                INSERT INTO performance_order (order_no, contract_no, project_id, enterprise_id,
                                               insurance_type_id, org_id, region_code, region_name,
                                               guarantee_amount, premium_amount, premium_rate,
                                               status, apply_date)
                VALUES (?, ?, ?, ?, ?, ?, '000000', '未指定', 100, 1, 0.01, 'DRAFT', CURDATE())
                """, orderNo, "C-" + orderNo, projectId(), enterpriseId(), insuranceTypeId, orgId());
    }

    /** 订单夹具必须挂在真实存在的项目 / 企业 / 机构上，否则外键或语义会失真。 */
    private Long projectId() {
        return jdbc.queryForObject("SELECT id FROM project LIMIT 1", Long.class);
    }

    private Long enterpriseId() {
        return jdbc.queryForObject("SELECT id FROM enterprise LIMIT 1", Long.class);
    }

    private Long orgId() {
        return jdbc.queryForObject("SELECT id FROM sys_org LIMIT 1", Long.class);
    }
}
