package com.guarantee.system.vo;

import lombok.Data;

/**
 * 机构下拉选项（仅启用机构）。
 */
@Data
public class OrgOptionVO {

    private Long id;
    private String orgName;
    private String regionCode;
    private String regionName;
}
