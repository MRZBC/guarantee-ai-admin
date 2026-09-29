package com.guarantee.analysis.dto;

import com.guarantee.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 项目分页查询条件。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ProjectQuery extends PageQuery {

    /** 项目名称，模糊匹配。 */
    private String projectName;

    /**
     * 项目主键，精确匹配。
     *
     * <p>与 {@link #projectName} 的用途不同：名称用于"搜索候选"（下拉里按关键字找），
     * 主键用于"已经选中某一条"（把列表收敛到那一行）。
     * 项目管理页的筛选控件是"选中一条项目"，因此走这个字段而不是名称前缀匹配。</p>
     */
    private Long projectId;

    private String regionCode;

    /** 房建/市政/交通/水利/其他 */
    private String projectType;

    /** BIDDING/AWARDED/BUILDING/FINISHED */
    private String status;

    /** 业主企业ID。 */
    private Long enterpriseId;
}
