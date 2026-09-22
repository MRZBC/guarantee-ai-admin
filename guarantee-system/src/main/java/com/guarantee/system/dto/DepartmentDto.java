package com.guarantee.system.dto;

import com.guarantee.common.api.PageQuery;
import com.guarantee.system.scope.QueryScope;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 部门配置的请求 DTO。
 */
public final class DepartmentDto {

    /**
     * 树形接口的条数上限（SYS-C-21 同款保护）。
     *
     * <p>树必须基于全量数据组装，分页会导致树静默缺节点，因此树接口不走分页、只设上限；
     * 触顶时服务端必须告警而非静默截断（SYS-C-19 / SYS-C-24）。</p>
     */
    public static final int TREE_LIMIT = 2000;

    private DepartmentDto() {
    }

    /** 部门列表查询条件。 */
    @Getter
    @Setter
    public static class Query extends PageQuery {

        @Size(max = 64, message = "部门名称长度不能超过 64")
        private String deptName;

        @Size(max = 32, message = "部门编码长度不能超过 32")
        private String deptCode;

        /** 统一模糊词（名称或编码）。 */
        @Size(max = 64, message = "关键字长度不能超过 64")
        private String keyword;

        private Long parentId;

        private Integer status;

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

    /** 部门新增。 */
    @Getter
    @Setter
    public static class CreateRequest {

        @NotBlank(message = "部门编码不能为空")
        @Size(max = 32, message = "部门编码长度不能超过 32")
        private String deptCode;

        @NotBlank(message = "部门名称不能为空")
        @Size(max = 64, message = "部门名称长度不能超过 64")
        private String deptName;

        /** 上级部门，0 表示顶级。 */
        private Long parentId;

        private Integer sortNo;
    }

    /**
     * 部门修改。
     *
     * <p>{@code deptCode} 不可改（改编码请停用后新建），因此本 DTO 刻意不提供该字段。</p>
     */
    @Getter
    @Setter
    public static class UpdateRequest {

        @Size(max = 64, message = "部门名称长度不能超过 64")
        private String deptName;

        private Long parentId;

        private Integer sortNo;
    }

    /** 部门启停。 */
    @Getter
    @Setter
    public static class StatusRequest {

        @jakarta.validation.constraints.NotNull(message = "目标状态不能为空")
        @Min(value = 0, message = "状态只能是 0/1")
        @Max(value = 1, message = "状态只能是 0/1")
        private Integer status;
    }
}
