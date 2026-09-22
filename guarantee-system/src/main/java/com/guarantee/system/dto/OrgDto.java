package com.guarantee.system.dto;

import com.guarantee.common.api.PageQuery;
import com.guarantee.system.scope.QueryScope;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 机构配置的请求 DTO。
 *
 * <p>写入类动作（Create/Update/Status）的校验规则集中在内部类，便于写工具复用
 * {@code guarantee-system} 的既有校验（SYS-W-09），避免模型自行构造绕过字段级校验。</p>
 */
public final class OrgDto {

    /** 树形接口的返回条数上限（SYS-C-21 建议 2000）。 */
    public static final int TREE_LIMIT = 2000;

    /** 写操作在数据范围内解析目标的候选上限。 */
    public static final int CANDIDATE_LIMIT = 20;

    private OrgDto() {
    }

    /** 机构列表查询条件。 */
    @Getter
    @Setter
    public static class Query extends PageQuery {

        @Size(max = 64, message = "机构名称长度不能超过 64")
        private String orgName;

        @Size(max = 32, message = "机构编码长度不能超过 32")
        private String orgCode;

        @Size(max = 12, message = "行政区划编码长度不能超过 12")
        private String regionCode;

        /** 层级 1总部 2省级 3市级。 */
        private Integer orgLevel;

        private Integer status;

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

    /** 机构新增。 */
    @Getter
    @Setter
    public static class CreateRequest {

        @jakarta.validation.constraints.NotBlank(message = "机构编码不能为空")
        @Size(max = 32, message = "机构编码长度不能超过 32")
        private String orgCode;

        @jakarta.validation.constraints.NotBlank(message = "机构名称不能为空")
        @Size(max = 64, message = "机构名称长度不能超过 64")
        private String orgName;

        @jakarta.validation.constraints.NotBlank(message = "行政区划编码不能为空")
        @Size(max = 12, message = "行政区划编码长度不能超过 12")
        private String regionCode;

        @jakarta.validation.constraints.NotNull(message = "机构层级不能为空")
        @Min(value = 1, message = "机构层级只能是 1/2/3")
        @Max(value = 3, message = "机构层级只能是 1/2/3")
        private Integer orgLevel;

        /** 上级机构；0 表示顶级（仅总部）。 */
        @jakarta.validation.constraints.NotNull(message = "上级机构不能为空，0 表示顶级")
        private Long parentId;

        private Integer sortNo;

        private String regionName;
    }

    /** 机构修改。 */
    @Getter
    @Setter
    public static class UpdateRequest {

        @Size(max = 64, message = "机构名称长度不能超过 64")
        private String orgName;

        @Size(max = 12, message = "行政区划编码长度不能超过 12")
        private String regionCode;

        private String regionName;

        /** 变更上级机构；null 表示不改。 */
        private Long parentId;

        @Min(value = 1, message = "机构层级只能是 1/2/3")
        @Max(value = 3, message = "机构层级只能是 1/2/3")
        private Integer orgLevel;

        private Integer sortNo;
    }

    /** 机构启停。 */
    @Getter
    @Setter
    public static class StatusRequest {

        @jakarta.validation.constraints.NotNull(message = "目标状态不能为空")
        @Min(value = 0, message = "状态只能是 0/1")
        @Max(value = 1, message = "状态只能是 0/1")
        private Integer status;
    }
}
