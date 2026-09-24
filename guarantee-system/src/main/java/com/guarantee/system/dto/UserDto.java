package com.guarantee.system.dto;

import com.guarantee.common.api.PageQuery;
import com.guarantee.system.scope.QueryScope;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户配置的请求 DTO。
 *
 * <p><b>P-10 起解除 D-2 的收敛</b>：新增了 {@link CreateRequest}（用户新建）。
 * 但仍然**没有** ResetPasswordRequest——管理侧重置密码**不接受任何入参**
 * （密码固定为系统默认密码），所以它不是一个"请求体 DTO"，见
 * {@code UserController#resetPassword}。详见 docs/REQ-用户管理新增与修改.md。</p>
 */
public final class UserDto {

    private UserDto() {
    }

    /**
     * 用户新建（P-10 / CREATE，权限 {@code system:user:create}）。
     *
     * <p><b>刻意没有密码字段</b>：密码由服务端写入固定默认密码（{@code DefaultCredentials}）
     * 并要求首次登录改密，因此明文密码既不进请求体、也不进响应体。</p>
     */
    @Getter
    @Setter
    public static class CreateRequest {

        @NotBlank(message = "登录账号不能为空")
        @Size(max = 64, message = "登录账号长度不能超过 64")
        private String username;

        @NotBlank(message = "姓名不能为空")
        @Size(max = 64, message = "姓名长度不能超过 64")
        private String realName;

        /** 所属部门（必填：{@code sys_user.dept_id NOT NULL}）。 */
        @NotNull(message = "所属部门不能为空")
        private Long deptId;

        @Size(max = 20, message = "手机号长度不能超过 20")
        private String phone;

        @Size(max = 128, message = "邮箱长度不能超过 128")
        private String email;

        /** 角色（D2=A：至少 1 个；服务层校验存在且启用）。 */
        @NotEmpty(message = "请至少分配一个角色")
        private List<@NotBlank(message = "角色编码不能为空") String> roleCodes;
    }

    /** 用户列表查询条件（同时被 queryUser 工具复用）。 */
    @Getter
    @Setter
    public static class Query extends PageQuery {

        @Size(max = 64, message = "登录账号长度不能超过 64")
        private String username;

        @Size(max = 64, message = "姓名长度不能超过 64")
        private String realName;

        /** 账号或姓名的统一模糊词（工具入参），与 username/realName 是"或"的关系。 */
        @Size(max = 64, message = "关键字长度不能超过 64")
        private String keyword;

        private Long deptId;

        /** 角色编码过滤。 */
        private String roleCode;

        private Integer status;

        /** 最近登录早于该时刻（用于"三个月没登录"类问题）。 */
        private LocalDateTime lastLoginBefore;

        /** 从未登录过。 */
        private Boolean neverLoggedIn;

        /** 单条明细查询用。 */
        private Long id;

        /** 数据范围（服务端强制注入，SYS-P-08）。 */
        private QueryScope scope = QueryScope.unrestricted();

        /**
         * 是否包含已删除记录（LD-04b「显示已删除」开关）。
         *
         * <p>默认 false。仅持有 {@code system:*:delete} 权限的调用方可传 true；
         * 权限判定在 Controller 的 {@code @PreAuthorize} 完成，不依赖本字段。</p>
         */
        private Boolean includeDeleted = false;
    }

    /** 用户资料修改（SYS-W-04 UPDATE）。 */
    @Getter
    @Setter
    public static class UpdateRequest {

        @Size(max = 64, message = "姓名长度不能超过 64")
        private String realName;

        @Size(max = 20, message = "手机号长度不能超过 20")
        private String phone;

        @Size(max = 128, message = "邮箱长度不能超过 128")
        private String email;

        /**
         * 变更部门；{@code null} 表示不改。
         *
         * <p>阶段一 O3：用户必须属于一个部门（{@code sys_user.dept_id NOT NULL}），
         * "清空部门"能力已整体移除，因此不再有 {@code clearDept} 字段。</p>
         */
        private Long deptId;

        /** 至少一个字段可改：由 Service 层显式校验，避免"空请求"被当成成功。 */
        public boolean isEmpty() {
            return realName == null && phone == null && email == null && deptId == null;
        }
    }

    /** 用户启停（SYS-W-04 ENABLE/DISABLE）。 */
    @Getter
    @Setter
    public static class StatusRequest {

        @jakarta.validation.constraints.NotNull(message = "目标状态不能为空")
        @Min(value = 0, message = "状态只能是 0/1")
        @jakarta.validation.constraints.Max(value = 1, message = "状态只能是 0/1")
        private Integer status;
    }

    /** 用户角色分配（SYS-W-04 ASSIGN_ROLES）。 */
    @Getter
    @Setter
    public static class AssignRolesRequest {

        @jakarta.validation.constraints.NotNull(message = "角色列表不能为空")
        private List<@NotBlank(message = "角色编码不能为空") String> roleCodes;
    }
}
