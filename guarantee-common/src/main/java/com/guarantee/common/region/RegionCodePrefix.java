package com.guarantee.common.region;

/**
 * 行政区划码的**层级前缀**：把 6 位国标码截成"覆盖它全部下级"的前缀。
 *
 * <table>
 *   <caption>规则</caption>
 *   <tr><td>省</td><td>{@code 330000} → {@code 33}</td></tr>
 *   <tr><td>市</td><td>{@code 330100} → {@code 3301}</td></tr>
 *   <tr><td>区县</td><td>{@code 330102} → {@code 330102}（6 位，等于精确匹配）</td></tr>
 * </table>
 *
 * <p><b>用途</b>：筛选条件用 {@code region_code LIKE CONCAT(前缀, '%')}，
 * 于是"选浙江省"能把该省下**所有**市/区县编码的订单/项目/企业/机构一并筛出来，
 * 而"选杭州市"只筛杭州及其区县。否则机构/订单一旦按市级码记录，
 * 选省就会漏掉它们（精确匹配 {@code = '330000'} 匹配不到 {@code '330100'}）。</p>
 *
 * <p><b>为什么可以纯靠码串判断层级</b>：GB/T 2260 的 6 位码结构固定——
 * 省 = 前 2 位 + {@code 0000}，市 = 前 4 位 + {@code 00}，区县 = 6 位。
 * 因此不需要查地区表（{@code guarantee-order} / {@code guarantee-analysis} 也不该依赖
 * {@code guarantee-system} 的地区表），这里保持成一个无依赖的纯函数。</p>
 *
 * <p><b>与前端同源</b>：{@code frontend/src/utils/region.ts} 的 {@code regionPrefix()} 是同一条规则，
 * 用于机构树本地过滤；改这里就要同步改那里（本类有单测，前端那条没有）。</p>
 */
public final class RegionCodePrefix {

    private RegionCodePrefix() {
    }

    /**
     * @param code 行政区划码（省/市/区县）；null 或空串返回原值
     * @return 用于 LIKE 的前缀；非 6 位数字的历史脏数据按原样返回（退化为精确匹配）
     */
    public static String of(String code) {
        if (code == null) {
            return null;
        }
        String trimmed = code.trim();
        if (trimmed.length() != 6 || !trimmed.chars().allMatch(Character::isDigit)) {
            return trimmed;
        }
        if (trimmed.endsWith("0000")) {
            return trimmed.substring(0, 2);
        }
        if (trimmed.endsWith("00")) {
            return trimmed.substring(0, 4);
        }
        return trimmed;
    }
}
