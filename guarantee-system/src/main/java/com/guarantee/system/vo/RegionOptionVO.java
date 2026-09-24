package com.guarantee.system.vo;

import com.guarantee.system.entity.SysRegion;

/**
 * 地区下拉选项。
 *
 * <p>给下拉/级联控件需要的字段：前端绑定 {@code code} 作为筛选参数（后端按层级前缀匹配），
 * {@code name} 用于显示，{@code shortName} 与 {@code code} 一起供搜索匹配，
 * {@code level} 与 {@code parentCode} 供前端组装省/市/区县树。</p>
 */
public record RegionOptionVO(
        String code,
        String name,
        String shortName,
        Integer level,
        String parentCode) {

    public static RegionOptionVO of(SysRegion region) {
        return new RegionOptionVO(region.getCode(), region.getName(),
                region.getShortName(), region.getLevel(), region.getParentCode());
    }
}
