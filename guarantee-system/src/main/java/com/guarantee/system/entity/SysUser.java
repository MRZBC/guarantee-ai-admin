package com.guarantee.system.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户配置（sys_user）。
 *
 * <p>实体包含 password 散列，仅供登录校验使用，禁止从 Controller 返回。</p>
 */
@Data
public class SysUser {

    /** 账号类型：人（可登录）。 */
    public static final String ACCOUNT_TYPE_HUMAN = "HUMAN";
    /** 账号类型：服务账号（机器身份，**不参与登录**，只用于 MCP 等机器凭据）。 */
    public static final String ACCOUNT_TYPE_SERVICE = "SERVICE";

    private Long id;
    /** 登录账号 */
    private String username;
    /** BCrypt 密码散列，仅登录链路读取 */
    private String password;
    /** 姓名 */
    private String realName;
    /** 所属部门（必填：用户必须属于一个部门） */
    private Long deptId;
    private String phone;
    private String email;
    /** 状态 1启用 0停用 */
    private Integer status;
    /**
     * 账号类型 HUMAN / SERVICE（V10 新增列 {@code sys_user.account_type}）。
     *
     * <p><b>缺省语义是 HUMAN</b>：列本身是 {@code NOT NULL DEFAULT 'HUMAN'}，但存量库/直连数据
     * 仍可能拿到 NULL 或空串，一律按 HUMAN 处理——把"字段缺失"当成"服务账号"会让普通账号
     * 突然登不进来（放过未知、拒绝已知的服务账号，两边的失败方向才是安全的）。</p>
     */
    private String accountType;
    /**
     * 首次登录强制改密（P-10 / D1=C）。
     *
     * <p>新建账号与管理员重置密码都会置 1；用户改密成功后清 0。为 1 时服务端闸门
     * （{@code PasswordChangeRequiredFilter}）会拒绝除改密/登出/读自己外的一切请求。</p>
     */
    private Integer mustChangePassword;
    /** 最近登录时间 */
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 逻辑删除 0正常 1已删除（LD-01：所有业务查询默认只看 0） */
    private Integer isDeleted;
    /** 删除时间（DATETIME(6)，未删除为 null；同时是唯一键分量） */
    private LocalDateTime deletedAt;
    /** 删除人：应用写 sys_user.id 字符串，数据库直连删除为 'DB' */
    private String deletedBy;

    /**
     * 归一账号类型：NULL / 空 / 未知取值一律 {@code HUMAN}，只有明确等于 {@code SERVICE} 才算服务账号。
     *
     * <p><b>为什么"未知值"也归 HUMAN</b>：这是两条链路各自的安全方向决定的——
     * 登录侧若因为一个拼错的值（例如 {@code SERVCE}）把普通账号判成服务账号，
     * 就会制造一批"密码对但登不进"的账号；而机器凭据侧判的是"是否等于 SERVICE"，
     * 未知值自然不通过（fail-closed）。同一条归一规则在两侧都给出安全结果。</p>
     */
    public static String normalizeAccountType(String raw) {
        if (raw == null) {
            return ACCOUNT_TYPE_HUMAN;
        }
        String value = raw.trim().toUpperCase(java.util.Locale.ROOT);
        return ACCOUNT_TYPE_SERVICE.equals(value) ? ACCOUNT_TYPE_SERVICE : ACCOUNT_TYPE_HUMAN;
    }

    /** 归一后的账号类型（NULL/空/未知 → HUMAN）。 */
    public String effectiveAccountType() {
        return normalizeAccountType(accountType);
    }

    /** 是否服务账号（机器身份）：只有明确 {@code account_type='SERVICE'} 才为 true。 */
    public boolean isServiceAccount() {
        return ACCOUNT_TYPE_SERVICE.equals(effectiveAccountType());
    }

}
