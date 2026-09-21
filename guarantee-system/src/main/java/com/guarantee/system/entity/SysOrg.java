package com.guarantee.system.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 机构配置（sys_org）。
 */
@Data
public class SysOrg {

    private Long id;
    /** 机构编码 */
    private String orgCode;
    /** 机构名称 */
    private String orgName;
    /** 行政区划编码 */
    private String regionCode;
    /** 行政区划名称 */
    private String regionName;
    /** 层级 1总部 2省级 3市级 */
    private Integer orgLevel;
    /** 上级机构ID，0为顶级 */
    private Long parentId;
    /** 状态 1启用 0停用 */
    private Integer status;
    private Integer sortNo;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
