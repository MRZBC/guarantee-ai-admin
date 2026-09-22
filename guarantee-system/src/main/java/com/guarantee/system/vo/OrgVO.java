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
    /** 上级机构名称（Service 层补齐，SYS-Q-01 出参） */
    private String parentName;
    /** 机构下的部门数（Service 层补齐） */
    private Long deptCount;
    /** 机构下的启用用户数（Service 层补齐） */
    private Long userCount;
    /** 状态 1启用 0停用 */
    private Integer status;
    private Integer sortNo;
    private LocalDateTime createdAt;
    /** 逻辑删除 0正常 1已删除 */
    private Integer isDeleted;
    /** 删除时间（DATETIME(6)，未删除为 null） */
    private LocalDateTime deletedAt;
    /** 删除人标识：应用删除记为 sys_user.id 的字符串，数据库直连删除为 "DB"（设计 §2.1a） */
    private String deletedBy;

}
