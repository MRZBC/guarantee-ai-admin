package com.guarantee.system.dto;

import com.guarantee.common.api.PageQuery;
import com.guarantee.system.scope.QueryScope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 角色配置的请求 DTO。
 */
public final class RoleDto {

    private RoleDto() {
    }

    /** 角色列表查询条件（同时被 queryRole 工具复用）。 */
    @Getter
    @Setter
    public static class Query extends PageQuery {

        @Size(max = 32, message = "角色编码长度不能超过 32")
        private String roleCode;

        @Size(max = 64, message = "角色名称长度不能超过 64")
        private String roleName;

        /** 统一模糊词（编码或名称）。 */
        @Size(max = 64, message = "关键字长度不能超过 64")
        private String keyword;

        private Integer status;

        /** 单条明细查询用。 */
        private Long id;

        /**
         * 机构过滤：限定"该机构下有用户持有的角色"。
         *
         * <p>角色本身不是机构强相关实体，但 SYS-A-10 要求 ROLE 类数据在审计与画像查询中
         * 能按机构收敛，因此保留该维度。</p>
         */
        private Long orgId;

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

    /** 角色新增。 */
    @Getter
    @Setter
    public static class CreateRequest {

        @NotBlank(message = "角色编码不能为空")
        @Size(max = 32, message = "角色编码长度不能超过 32")
        private String roleCode;

        @NotBlank(message = "角色名称不能为空")
        @Size(max = 64, message = "角色名称长度不能超过 64")
        private String roleName;

        @Size(max = 255, message = "描述长度不能超过 255")
        private String description;
    }

    /**
     * 角色修改。
     *
     * <p>{@code roleCode} 不可改，因此不提供该字段。{@code ADMIN} 角色不可改由 Service 层拦截。</p>
     */
    @Getter
    @Setter
    public static class UpdateRequest {

        @Size(max = 64, message = "角色名称长度不能超过 64")
        private String roleName;

        @Size(max = 255, message = "描述长度不能超过 255")
        private String description;

        private Integer status;
    }

    /**
     * 角色授权。
     *
     * <p>只接受已经在 {@code sys_permission} 中存在的权限码，不接受模型自由构造（5.2.3）。</p>
     */
    @Getter
    @Setter
    public static class AssignPermissionsRequest {

        @jakarta.validation.constraints.NotNull(message = "权限列表不能为空")
        private List<@NotBlank(message = "权限编码不能为空") String> permCodes;
    }
}
