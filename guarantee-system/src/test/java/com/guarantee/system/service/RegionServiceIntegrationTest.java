package com.guarantee.system.service;

import com.guarantee.common.exception.BizException;
import com.guarantee.system.ItMybatisConfig;
import com.guarantee.system.entity.SysRegion;
import com.guarantee.system.vo.RegionOptionVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 行政区划字典（地区基础信息）的集成测试。
 *
 * <p>对应 {@code docs/REQ-地区基础信息与区域筛选下拉.md}：地区下拉取代手填"区域编码"、
 * 机构写入按字典校验。这里钉住四件事：</p>
 * <ol>
 *   <li><b>种子数据真的导进来了</b>（省级含港澳台、市级与区县能按上级取到，
 *       前端要靠 {@code parentCode} 组装级联树），否则页面会"下拉是空的"，
 *       而失败原因看起来与断言无关；</li>
 *   <li><b>字典与业务数据对得上</b>：订单里用到的每个 region_code 都能在字典里查到，
 *       且 region_name 与字典全称逐字一致（业务数据是快照，名称漂移会让人以为筛选坏了）；</li>
 *   <li><b>两种口径</b>：默认返回整本字典（产品口径：下拉看到全部数据）；
 *       {@code onlyWithData=true} 时只给有业务数据的地区；</li>
 *   <li><b>写入校验</b>：省/市/区县三级都接受（可传名称解析成编码），
 *       未知码、停用、已删除的都会被拒。</li>
 * </ol>
 *
 * <p><b>测试数据纪律</b>：夹具区划码用 {@code 9900xx}、订单号用 {@code __rgn_} 前缀，
 * {@code @AfterEach} 物理清理。</p>
 */
@SpringBootTest(classes = ItMybatisConfig.class)
@TestPropertySource(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/schema.sql",
        "spring.sql.init.data-locations=classpath:db/seed/region.sql"
})
class RegionServiceIntegrationTest {

    private static final String ORDER_PREFIX = "__rgn_";
    private static final String FIXTURE_CODE_USED = "990001";
    private static final String FIXTURE_CODE_UNUSED = "990002";
    private static final String FIXTURE_CODE_DISABLED = "990003";
    private static final String FIXTURE_CODE_DELETED = "990004";

    @Autowired
    private RegionService regionService;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM tender_order WHERE order_no LIKE ?", ORDER_PREFIX + "%");
        jdbc.update("DELETE FROM sys_region WHERE code LIKE '9900%'");
        regionService.invalidateUsedCodesCache();
    }

    // ==================================================================
    // 种子数据：省 + 市
    // ==================================================================

    @Test
    @DisplayName("省级字典：含业务用到的 8 个省与港澳台，且名称是全称（与订单里的 region_name 一致）")
    void provinceDictionaryIsSeeded() {
        List<String> codes = regionService.listOptions(1, null, false).stream()
                .map(RegionOptionVO::code).toList();

        assertThat(codes).as("国标省级共 34 个（含港澳台）").hasSizeGreaterThanOrEqualTo(34);
        assertThat(codes).contains(
                "110000", "310000", "320000", "330000", "370000", "420000", "440000", "510000",
                "710000", "810000", "820000");

        SysRegion zhejiang = regionService.requireEnabledRegion("330000");
        assertThat(zhejiang.getName()).as("必须是全称，和订单里的 region_name 逐字一致").isEqualTo("浙江省");
    }

    @Test
    @DisplayName("市级字典：按上级取浙江的 11 个市，排序稳定（国标 sort_no）")
    void cityDictionaryIsSeeded() {
        List<RegionOptionVO> cities = regionService.listOptions(2, "330000", false);

        assertThat(cities).hasSize(11);
        assertThat(cities.get(0).code()).isEqualTo("330100");
        assertThat(cities.get(0).name()).isEqualTo("杭州市");
        assertThat(cities.get(0).parentCode()).as("前端要靠 parentCode 组装级联树").isEqualTo("330000");
    }

    @Test
    @DisplayName("区县字典：浙江下辖区县可查（机构允许填到区县）")
    void districtDictionaryIsSeeded() {
        List<RegionOptionVO> districts = regionService.listOptions(3, "330100", false);

        assertThat(districts).as("杭州市下辖区县").isNotEmpty();
        assertThat(districts.stream().map(RegionOptionVO::code)).contains("330102");
        assertThat(regionService.requireEnabledRegion("330102").getName()).isEqualTo("上城区");
    }

    @Test
    @DisplayName("对账：业务数据里的每个 region_code 都能在字典查到，且 region_name 与字典全称一致")
    void businessDataMatchesDictionary() {
        List<String> usedCodes = List.copyOf(regionService.usedRegionCodes());
        assertThat(usedCodes).as("演示库应该有业务数据").isNotEmpty();

        for (String code : usedCodes) {
            // 复用写入路径的校验：能查不到、不是省级、已停用都会在这里抛错
            SysRegion region = regionService.requireEnabledRegion(code);

            List<String> namesInOrders = jdbc.queryForList(
                    "SELECT DISTINCT region_name FROM tender_order WHERE region_code = ?", String.class, code);
            assertThat(namesInOrders)
                    .as("区划 %s 的名称必须与字典全称一致，否则列表与下拉的显示名会对不上", code)
                    .allMatch(name -> name.equals(region.getName()));
        }
    }

    // ==================================================================
    // 筛选口径：onlyWithData
    // ==================================================================

    @Test
    @DisplayName("onlyWithData=true 只给有业务数据的地区；无数据的不出现（避免选了必然空结果）")
    void onlyWithDataFiltersOutRegionsWithoutBusinessData() {
        insertRegion(FIXTURE_CODE_USED, "夹具-有数据省", 1, 1);
        insertRegion(FIXTURE_CODE_UNUSED, "夹具-无数据省", 1, 1);
        insertTenderOrder(ORDER_PREFIX + "t1", FIXTURE_CODE_USED);
        regionService.invalidateUsedCodesCache();

        List<String> withData = regionService.listOptions(1, null, true).stream()
                .map(RegionOptionVO::code).toList();
        assertThat(withData).contains(FIXTURE_CODE_USED);
        assertThat(withData).doesNotContain(FIXTURE_CODE_UNUSED);

        List<String> all = regionService.listOptions(1, null, false).stream()
                .map(RegionOptionVO::code).toList();
        assertThat(all).as("写入候选口径（onlyWithData=false）应包含全部启用地区")
                .contains(FIXTURE_CODE_USED, FIXTURE_CODE_UNUSED);
    }

    @Test
    @DisplayName("「有业务数据」集合带 TTL 缓存：业务数据变了不会立刻反映，invalidate 后才刷新")
    void usedCodesAreCachedUntilInvalidated() {
        insertRegion(FIXTURE_CODE_USED, "夹具-有数据省", 1, 1);
        insertTenderOrder(ORDER_PREFIX + "t2", FIXTURE_CODE_USED);
        regionService.invalidateUsedCodesCache();
        assertThat(regionService.listOptions(1, null, true).stream().map(RegionOptionVO::code))
                .contains(FIXTURE_CODE_USED);

        // 直接删掉业务数据（绕过服务层）：缓存还在，仍认为"有数据"
        jdbc.update("DELETE FROM tender_order WHERE order_no = ?", ORDER_PREFIX + "t2");
        assertThat(regionService.listOptions(1, null, true).stream().map(RegionOptionVO::code))
                .as("TTL 内命中缓存，行为可预期（订单只读、地区极少变，5 分钟内不刷新是可接受代价）")
                .contains(FIXTURE_CODE_USED);

        regionService.invalidateUsedCodesCache();
        assertThat(regionService.listOptions(1, null, true).stream().map(RegionOptionVO::code))
                .as("失效缓存后如实反映数据库现状")
                .doesNotContain(FIXTURE_CODE_USED);
    }

    // ==================================================================
    // 写入校验：只接受字典里的启用省级
    // ==================================================================

    @Test
    @DisplayName("机构区划省/市/区县三级都能填：传码或传名称都能规范化成字典里的值")
    void acceptsAllLevels() {
        assertThat(regionService.requireEnabledRegion("330000").getCode()).as("省级码").isEqualTo("330000");
        assertThat(regionService.requireEnabledRegion(" 浙江省 ").getCode()).as("省级全称").isEqualTo("330000");
        assertThat(regionService.requireEnabledRegion("浙江").getCode()).as("省级简称").isEqualTo("330000");
        assertThat(regionService.requireEnabledRegion("330100").getCode()).as("市级码").isEqualTo("330100");
        assertThat(regionService.requireEnabledRegion("杭州市").getCode()).as("市级名称").isEqualTo("330100");
        assertThat(regionService.requireEnabledRegion("330102").getCode()).as("区县码").isEqualTo("330102");
    }

    @Test
    @DisplayName("拒绝字典里不存在的区划码，提示里给出正确用法")
    void rejectsUnknownCode() {
        assertThatThrownBy(() -> regionService.requireEnabledRegion("999999"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("行政区划不存在")
                .hasMessageContaining("/api/system/regions/options");
        assertThatThrownBy(() -> regionService.requireEnabledRegion("  "))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能为空");
    }

    @Test
    @DisplayName("拒绝已停用与已逻辑删除的地区")
    void rejectsDisabledAndDeletedRegions() {
        insertRegion(FIXTURE_CODE_DISABLED, "夹具-停用省", 1, 0);
        assertThatThrownBy(() -> regionService.requireEnabledRegion(FIXTURE_CODE_DISABLED))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已停用");

        insertRegion(FIXTURE_CODE_DELETED, "夹具-已删省", 1, 1);
        jdbc.update("UPDATE sys_region SET is_deleted = 1, deleted_at = NOW(6), deleted_by = 'DB'"
                + " WHERE code = ?", FIXTURE_CODE_DELETED);
        assertThatThrownBy(() -> regionService.requireEnabledRegion(FIXTURE_CODE_DELETED))
                .as("逻辑删除的地区不出现在下拉里，也不允许被写入引用")
                .isInstanceOf(BizException.class)
                .hasMessageContaining("行政区划不存在");
        assertThat(regionService.listOptions(1, null, false).stream().map(RegionOptionVO::code))
                .doesNotContain(FIXTURE_CODE_DELETED);
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private void insertRegion(String code, String name, int level, int status) {
        jdbc.update("INSERT INTO sys_region (code, name, short_name, level, parent_code, status, sort_no)"
                + " VALUES (?, ?, ?, ?, '', ?, 0)", code, name, name.replace("省", ""), level, status);
    }

    private void insertTenderOrder(String orderNo, String regionCode) {
        jdbc.update("""
                INSERT INTO tender_order (order_no, project_id, enterprise_id, insurance_type_id, org_id,
                                          region_code, region_name, guarantee_amount, premium_amount, premium_rate,
                                          status, apply_date)
                VALUES (?, (SELECT id FROM project LIMIT 1), (SELECT id FROM enterprise LIMIT 1),
                        (SELECT id FROM insurance_type LIMIT 1), (SELECT id FROM sys_org LIMIT 1),
                        ?, '夹具地区', 100, 1, 0.01, 'DRAFT', CURDATE())
                """, orderNo, regionCode);
    }
}
