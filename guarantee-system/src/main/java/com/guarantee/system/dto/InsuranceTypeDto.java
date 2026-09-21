package com.guarantee.system.dto;

import com.guarantee.common.api.PageQuery;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 险种配置的请求 DTO（新增 / 修改 / 查询）。
 *
 * <p>与实体分离，避免外部直接绑定数据库字段。</p>
 */
public final class InsuranceTypeDto {

    private InsuranceTypeDto() {
    }

    /** 新增险种。 */
    @Getter
    @Setter
    public static class CreateRequest {

        @NotBlank(message = "险种编码不能为空")
        @Size(max = 32, message = "险种编码长度不能超过 32")
        private String typeCode;

        @NotBlank(message = "险种名称不能为空")
        @Size(max = 64, message = "险种名称长度不能超过 64")
        private String typeName;

        @NotBlank(message = "险种分类不能为空")
        private String category;

        @NotNull(message = "基准费率不能为空")
        @PositiveOrZero(message = "基准费率不能为负数")
        private BigDecimal baseRate;

        @PositiveOrZero(message = "最小保额不能为负数")
        private BigDecimal minAmount;

        @PositiveOrZero(message = "最大保额不能为负数")
        private BigDecimal maxAmount;

        @Size(max = 255, message = "描述长度不能超过 255")
        private String description;
    }

    /** 修改险种。 */
    @Getter
    @Setter
    public static class UpdateRequest {

        @NotBlank(message = "险种名称不能为空")
        @Size(max = 64, message = "险种名称长度不能超过 64")
        private String typeName;

        @NotBlank(message = "险种分类不能为空")
        private String category;

        @NotNull(message = "基准费率不能为空")
        @PositiveOrZero(message = "基准费率不能为负数")
        private BigDecimal baseRate;

        @PositiveOrZero(message = "最小保额不能为负数")
        private BigDecimal minAmount;

        @PositiveOrZero(message = "最大保额不能为负数")
        private BigDecimal maxAmount;

        private Integer status;

        @Size(max = 255, message = "描述长度不能超过 255")
        private String description;
    }

    /** 险种列表查询条件。 */
    @Getter
    @Setter
    public static class Query extends PageQuery {

        private String typeName;

        private String category;

        private Integer status;
    }
}
