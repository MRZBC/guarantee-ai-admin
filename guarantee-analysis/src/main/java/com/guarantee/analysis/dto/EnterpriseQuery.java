package com.guarantee.analysis.dto;

import com.guarantee.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 企业分页查询条件。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class EnterpriseQuery extends PageQuery {

    /** 企业名称，模糊匹配。 */
    private String entName;

    private String regionCode;

    /** 行业。 */
    private String industry;

    /** AAA/AA/A/BBB */
    private String entLevel;

    /** 1启用 0停用 */
    private Integer status;
}
