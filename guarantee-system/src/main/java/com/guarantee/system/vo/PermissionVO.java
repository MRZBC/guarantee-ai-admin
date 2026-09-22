package com.guarantee.system.vo;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 权限配置响应对象（MyBatis 结果类型）。前端按 parentId 自行组树。
 */
@Data
public class PermissionVO {

    private Long id;
    private String permCode;
    private String permName;
    /** MENU/BUTTON/API */
    private String permType;
    private Long parentId;
    /** 前端路由或接口路径 */
    private String path;
    private String component;
    private String icon;
    private Integer sortNo;
    /** 逻辑删除 0正常 1已删除 */
    private Integer isDeleted;
    /** 删除时间（DATETIME(6)，未删除为 null） */
    private LocalDateTime deletedAt;

}
