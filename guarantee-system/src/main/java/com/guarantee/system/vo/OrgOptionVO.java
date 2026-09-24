package com.guarantee.system.vo;

import lombok.Data;

/**
 * 机构下拉选项（订单筛选下拉口径：启用中未删除的 ∪ 被订单引用的）。
 *
 * <p>{@code status} / {@code isDeleted} 回传是为了让前端把选项标注成
 * 「（已停用）」「（已删除）」：这些机构仍能筛历史订单，但不能被误认为可用于新业务。</p>
 */
@Data
public class OrgOptionVO {

    private Long id;
    private String orgName;
    private String regionCode;
    private String regionName;
    /** 1 启用 / 0 停用。 */
    private Integer status;
    /** 逻辑删除标记：1 已删除 / 0 正常。 */
    private Integer isDeleted;
}
