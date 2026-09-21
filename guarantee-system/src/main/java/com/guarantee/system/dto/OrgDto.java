package com.guarantee.system.dto;

import com.guarantee.common.api.PageQuery;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 机构配置的请求 DTO。
 */
public final class OrgDto {

    private OrgDto() {
    }

    /** 机构列表查询条件。 */
    @Getter
    @Setter
    public static class Query extends PageQuery {

        @Size(max = 64, message = "机构名称长度不能超过 64")
        private String orgName;

        @Size(max = 12, message = "行政区划编码长度不能超过 12")
        private String regionCode;

        private Integer status;
    }
}
