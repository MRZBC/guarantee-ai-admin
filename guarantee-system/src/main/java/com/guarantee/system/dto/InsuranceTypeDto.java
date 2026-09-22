package com.guarantee.system.dto;

import com.guarantee.common.api.PageQuery;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 险种配置的请求 DTO（新增 / 修改 / 查询 / 启停）。
 *
 * <p>与实体分离，避免外部直接绑定数据库字段。</p>
 */
public final class InsuranceTypeDto {

    /** 基准费率上限（SYS-W-01 校验：baseRate ∈ (0, 0.1]）。 */
    public static final BigDecimal MAX_BASE_RATE = new BigDecimal("0.1");

    private InsuranceTypeDto() {
    }

    /** 新增险种。 */
    @Getter
    @Setter
    public static class CreateRequest {

        @jakarta.validation.constraints.NotBlank(message = "险种编码不能为空")
        @Size(max = 32, message = "险种编码长度不能超过 32")
        private String typeCode;

        @jakarta.validation.constraints.NotBlank(message = "险种名称不能为空")
        @Size(max = 64, message = "险种名称长度不能超过 64")
        private String typeName;

        @jakarta.validation.constraints.NotBlank(message = "险种分类不能为空")
        private String category;

        @jakarta.validation.constraints.NotNull(message = "基准费率不能为空")
        @jakarta.validation.constraints.Positive(message = "基准费率必须大于 0")
        private BigDecimal baseRate;

        @jakarta.validation.constraints.PositiveOrZero(message = "最小保额不能为负数")
        private BigDecimal minAmount;

        @jakarta.validation.constraints.PositiveOrZero(message = "最大保额不能为负数")
        private BigDecimal maxAmount;

        @Size(max = 255, message = "描述长度不能超过 255")
        private String description;
    }

    /** 修改险种。 */
    @Getter
    @Setter
    public static class UpdateRequest {

        @Size(max = 64, message = "险种名称长度不能超过 64")
        private String typeName;

        private String category;

        @jakarta.validation.constraints.Positive(message = "基准费率必须大于 0")
        private BigDecimal baseRate;

        @jakarta.validation.constraints.PositiveOrZero(message = "最小保额不能为负数")
        private BigDecimal minAmount;

        @jakarta.validation.constraints.PositiveOrZero(message = "最大保额不能为负数")
        private BigDecimal maxAmount;

        private Integer status;

        @Size(max = 255, message = "描述长度不能超过 255")
        private String description;
    }

    /** 险种启停。 */
    @Getter
    @Setter
    public static class StatusRequest {

        @jakarta.validation.constraints.NotNull(message = "目标状态不能为空")
        @jakarta.validation.constraints.Min(value = 0, message = "状态只能是 0/1")
        @jakarta.validation.constraints.Max(value = 1, message = "状态只能是 0/1")
        private Integer status;
    }

    /** 险种列表查询条件（同时被 queryInsuranceType 工具复用）。 */
    @Getter
    @Setter
    public static class Query extends PageQuery {

        @Size(max = 64, message = "险种名称长度不能超过 64")
        private String typeName;

        @Size(max = 32, message = "险种编码长度不能超过 32")
        private String typeCode;

        /** 统一模糊词（名称或编码）。 */
        @Size(max = 64, message = "关键字长度不能超过 64")
        private String keyword;

        /** TENDER / PERFORMANCE / OTHER。 */
        private String category;

        private Integer status;

        /**
         * 是否包含已删除记录（LD-04b「显示已删除」开关）。
         *
         * <p>默认 false。仅持有 {@code system:*:delete} 权限的调用方可传 true；
         * 权限判定在 Controller 的 {@code @PreAuthorize} 完成，不依赖本字段。</p>
         */
        private Boolean includeDeleted = false;
    }
}
