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

    /**
     * 企业主键，精确匹配。
     *
     * <p>与 {@link #entName} 的用途不同：名称用于"搜索候选"（下拉里按关键字找），
     * 主键用于"已经选中某一条"（把列表收敛到那一行）。
     * 企业管理页的筛选控件是"选中一条企业"，因此走这个字段而不是名称模糊匹配。</p>
     */
    private Long entId;

    private String regionCode;

    /** 行业。 */
    private String industry;

    /** AAA/AA/A/BBB */
    private String entLevel;

    /** 1启用 0停用 */
    private Integer status;
}
