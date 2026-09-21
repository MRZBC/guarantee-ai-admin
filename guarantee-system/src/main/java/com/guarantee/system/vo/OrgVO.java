package com.guarantee.system.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 机构配置响应对象（MyBatis 结果类型）。实体不直接对外暴露。
 */
@Data
public class OrgVO {

    private Long id;
    private String orgCode;
    private String orgName;
    private String regionCode;
    private String regionName;
    /** 层级 1总部 2省级 3市级 */
    private Integer orgLevel;
    private Long parentId;
    /** 状态 1启用 0停用 */
    private Integer status;
    private Integer sortNo;
    private LocalDateTime createdAt;
}
