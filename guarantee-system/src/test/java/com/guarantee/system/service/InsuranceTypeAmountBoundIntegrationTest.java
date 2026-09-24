package com.guarantee.system.service;

import com.guarantee.common.exception.BizException;
import com.guarantee.system.ItMybatisConfig;
import com.guarantee.system.dto.InsuranceTypeDto;
import com.guarantee.system.vo.InsuranceTypeVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 险种「保额区间」口径的集成测试：**不填 = 不限**。
 *
 * <p><b>为什么专门测这条</b>：{@code insurance_type.min_amount / max_amount} 是
 * {@code NOT NULL DEFAULT 0}，而接口允许不传、AI 工具描述也写着"选填"。
 * 原实现把 {@code null} 原样写进 INSERT，于是"新增险种不填保额区间"直接撞约束，
 * 被全局兜底报成 {@code code=500「系统内部错误」}——一个正常操作看到系统故障提示。</p>
 *
 * <p>现在的口径：<b>空 / 0 都表示不限</b>（0 是库里既有的默认值，因此不动 DDL）。
 * 写入时 null 归一成 0；只有"上下限都给了具体值"时才校验大小。</p>
 *
 * <p><b>测试数据纪律</b>：夹具一律以 {@code __iab_} 前缀建行，{@code @AfterEach} 物理清理。</p>
 */
@SpringBootTest(classes = ItMybatisConfig.class)
@TestPropertySource(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/schema.sql"
})
class InsuranceTypeAmountBoundIntegrationTest {

    private static final String P = "__iab_";

    @Autowired
    private InsuranceTypeService insuranceTypeService;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM insurance_type WHERE type_code LIKE ?", P + "%");
    }

    // ==================================================================
    // 回归：不填保额区间不再 500
    // ==================================================================

    @Test
    @DisplayName("新增险种不填保额区间 → 成功落库，区间为不限（0），不再是 500「系统内部错误」")
    void createWithoutAmountBoundsSucceeds() {
        InsuranceTypeVO created = insuranceTypeService.create(createRequest(P + "none", null, null));

        assertThat(created.minAmount()).as("不填 = 不限，落库为 0").isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(created.maxAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(stored(created.id(), "min_amount")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(stored(created.id(), "max_amount")).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("只填下限（例如 100 万起、上不封顶）→ 上限为不限")
    void createWithMinOnlyLeavesMaxUnlimited() {
        InsuranceTypeVO created = insuranceTypeService.create(
                createRequest(P + "minonly", new BigDecimal("1000000"), null));

        assertThat(created.minAmount()).isEqualByComparingTo(new BigDecimal("1000000"));
        assertThat(created.maxAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("上限显式传 0（不限）+ 下限 100 万 → 允许：不设上限时下限随便填")
    void explicitZeroMaxMeansUnlimitedSoNoComparison() {
        InsuranceTypeVO created = insuranceTypeService.create(
                createRequest(P + "zeromax", new BigDecimal("1000000"), BigDecimal.ZERO));

        assertThat(created.maxAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ==================================================================
    // 仍然要拦的情况：两边都给了具体值
    // ==================================================================

    @Test
    @DisplayName("上下限都给了具体值且下限 ≥ 上限 → 400（可读提示，不是 500）")
    void minNotLessThanMaxIsRejected() {
        assertThatThrownBy(() -> insuranceTypeService.create(
                createRequest(P + "bad", new BigDecimal("2000000"), new BigDecimal("1000000"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("最小保额必须小于最大保额");
    }

    // ==================================================================
    // 修改：0 = 清空为不限；不传 = 保持原值
    // ==================================================================

    @Test
    @DisplayName("修改时传 0 → 把上限清成不限；不传下限 → 下限保持原值")
    void updateZeroClearsToUnlimitedAndNullKeepsExisting() {
        InsuranceTypeVO created = insuranceTypeService.create(createRequest(
                P + "upd", new BigDecimal("500000"), new BigDecimal("5000000")));

        InsuranceTypeDto.UpdateRequest request = new InsuranceTypeDto.UpdateRequest();
        request.setMaxAmount(BigDecimal.ZERO);   // 清空上限 = 不限
        insuranceTypeService.update(created.id(), request);

        assertThat(stored(created.id(), "max_amount")).as("0 = 不限").isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(stored(created.id(), "min_amount"))
                .as("不传的字段保持原值（部分更新语义，AI 工具只改一个字段时依赖它）")
                .isEqualByComparingTo(new BigDecimal("500000"));
    }

    @Test
    @DisplayName("修改时只改名称、区间都不传 → 区间完全不变")
    void updateWithoutAmountFieldsKeepsBounds() {
        InsuranceTypeVO created = insuranceTypeService.create(createRequest(
                P + "keep", new BigDecimal("500000"), new BigDecimal("5000000")));

        InsuranceTypeDto.UpdateRequest request = new InsuranceTypeDto.UpdateRequest();
        request.setTypeName("夹具-改名后");
        insuranceTypeService.update(created.id(), request);

        assertThat(stored(created.id(), "min_amount")).isEqualByComparingTo(new BigDecimal("500000"));
        assertThat(stored(created.id(), "max_amount")).isEqualByComparingTo(new BigDecimal("5000000"));
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private static InsuranceTypeDto.CreateRequest createRequest(String code, BigDecimal min, BigDecimal max) {
        InsuranceTypeDto.CreateRequest request = new InsuranceTypeDto.CreateRequest();
        request.setTypeCode(code);
        request.setTypeName("夹具-" + code);
        request.setCategory("TENDER");
        request.setBaseRate(new BigDecimal("0.010000"));
        request.setMinAmount(min);
        request.setMaxAmount(max);
        return request;
    }

    private BigDecimal stored(Long id, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM insurance_type WHERE id = ?",
                BigDecimal.class, id);
    }
}
