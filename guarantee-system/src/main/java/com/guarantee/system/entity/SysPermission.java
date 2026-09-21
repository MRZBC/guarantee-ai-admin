package com.guarantee.system.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 权限配置（sys_permission）。
 */
@Data
public class SysPermission {

    private Long id;
    /** 权限编码 */
    private String permCode;
    /** 权限名称 */
    private String permName;
    /** MENU/BUTTON/API */
    private String permType;
    /** 上级权限ID，0为顶级 */
    private Long parentId;
    /** 前端路由或接口路径 */
    private String path;
    /** 前端组件 */
    private String component;
    private String icon;
    private Integer sortNo;
    private LocalDateTime createdAt;
}
