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

    private String regionCode;

    /** 房建/市政/交通/水利/其他 */
    private String projectType;

    /** BIDDING/AWARDED/BUILDING/FINISHED */
    private String status;

    /** 业主企业ID。 */
    private Long enterpriseId;
}
