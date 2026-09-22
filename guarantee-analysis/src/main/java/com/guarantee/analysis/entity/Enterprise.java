package com.guarantee.analysis.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 企业（对应 enterprise 表）。
 */
@Data
public class Enterprise {

    private Long id;
    private String entCode;
    private String entName;
    /** 统一社会信用代码 */
    private String creditCode;
    private String regionCode;
    private String regionName;
    /** 行业 */
    private String industry;
    /** AAA/AA/A/BBB */
    private String entLevel;
    private String contactName;
    private String contactPhone;
    /** 1启用 0停用 */
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 逻辑删除 0正常 1已删除（LD-01：所有业务查询默认只看 0） */
    private Integer isDeleted;
    /** 删除时间（DATETIME(6)，未删除为 null；同时是唯一键分量） */
    private LocalDateTime deletedAt;
    /** 删除人：应用写 sys_user.id 字符串，数据库直连删除为 'DB' */
    private String deletedBy;

}
