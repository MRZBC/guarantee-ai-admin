package com.guarantee.system.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 行政区划基础信息（`sys_region`）实体。
 *
 * <p>表按省/市/区县三级设计（{@code level} 1/2/3），本期数据只到市级。
 * 主键是**区划码本身**（国标 GB/T 2260），因为业务数据一直用 {@code region_code}
 * 字符串引用它，不再引入代理 id。</p>
 */
@Data
public class SysRegion {

    /** 行政区划代码（省级 6 位，例如 330000）。 */
    private String code;

    /** 全称（例如 浙江省）——与业务数据的 region_name 逐字一致。 */
    private String name;

    /** 简称，仅用于搜索（例如 浙江）。 */
    private String shortName;

    /** 层级 1省 2市 3区县。 */
    private Integer level;

    /** 上级区划代码；省级为空串。 */
    private String parentCode;

    /** 1启用 0停用。 */
    private Integer status;

    private Integer sortNo;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 逻辑删除 0正常 1已删除。 */
    private Integer isDeleted;

    private LocalDateTime deletedAt;

    private String deletedBy;
}
