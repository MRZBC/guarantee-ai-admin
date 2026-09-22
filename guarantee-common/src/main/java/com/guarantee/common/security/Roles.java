package com.guarantee.common.security;

/**
 * 角色编码常量（与 {@code sys_role.role_code} 一一对应）。
 */
public final class Roles {

    /** 超级管理员：全部权限（含角色/权限管理、操作审计）。 */
    public static final String ADMIN = "ADMIN";

    /** 运营人员：除角色/权限管理与操作审计外的全部权限。 */
    public static final String OPERATOR = "OPERATOR";

    /** 数据分析师：业务分析 + 系统管理只读（D-1），不含操作审计（D-1a）。 */
    public static final String ANALYST = "ANALYST";

    /** 只读用户：所有 :view + ai:chat，不含 system:audit:view。 */
    public static final String VIEWER = "VIEWER";

    private Roles() {
    }
}
