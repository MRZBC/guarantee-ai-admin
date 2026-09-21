package com.guarantee.system.vo;

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
}
