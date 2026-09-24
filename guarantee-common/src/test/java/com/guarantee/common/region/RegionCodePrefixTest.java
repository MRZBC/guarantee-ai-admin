package com.guarantee.common.region;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 行政区划层级前缀的单元测试。
 *
 * <p>这条规则决定"选省能不能筛出下级的数据"，写错的表现是**静默漏数据**
 * （选浙江省只出来一部分、剩下的要按市单独筛才看得到），因此必须有测试钉住。</p>
 */
class RegionCodePrefixTest {

    @Test
    @DisplayName("省 → 前 2 位：选浙江省要能覆盖其下所有市/区县")
    void provinceTruncatesToTwoDigits() {
        assertThat(RegionCodePrefix.of("330000")).isEqualTo("33");
        assertThat(RegionCodePrefix.of("110000")).isEqualTo("11");
        assertThat(RegionCodePrefix.of("820000")).as("港澳台同样是省级码").isEqualTo("82");
    }

    @Test
    @DisplayName("市 → 前 4 位；区县 → 原样（6 位，等价于精确匹配）")
    void cityAndDistrict() {
        assertThat(RegionCodePrefix.of("330100")).isEqualTo("3301");
        assertThat(RegionCodePrefix.of("330102")).isEqualTo("330102");
    }

    @Test
    @DisplayName("省市区的包含关系符合直觉：33 能匹配到市与区县，3301 匹配不到别的市")
    void prefixCoversDescendants() {
        String province = RegionCodePrefix.of("330000");
        String city = RegionCodePrefix.of("330100");

        assertThat("330000").startsWith(province);
        assertThat("330100").startsWith(province);
        assertThat("330102").startsWith(province);
        assertThat("330200").as("杭州的前缀不该匹配到宁波").doesNotStartWith(city);
        assertThat("330100").startsWith(city);
        assertThat("330102").startsWith(city);
    }

    @Test
    @DisplayName("边界：空值原样返回；非 6 位的历史脏数据退化为精确匹配")
    void edgeCases() {
        assertThat(RegionCodePrefix.of(null)).isNull();
        assertThat(RegionCodePrefix.of("  330000  ")).as("两侧空白先 trim").isEqualTo("33");
        assertThat(RegionCodePrefix.of("000000")).as("未指定/占位码只匹配自身").isEqualTo("00");
        assertThat(RegionCodePrefix.of("33000")).as("长度不是 6 位 → 原样").isEqualTo("33000");
        assertThat(RegionCodePrefix.of("ABCDEF")).as("非数字 → 原样").isEqualTo("ABCDEF");
    }
}
