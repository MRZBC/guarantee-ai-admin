package com.guarantee.analysis.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 项目列表视图对象：由 MyBatis 关联查询直接映射填充。
 *
 * <p>连接字段使用 {@code AS camelCase} 别名 + 全局 map-underscore-to-camel-case。</p>
 */
@Data
public class ProjectVO {

    private Long id;
    private String projectCode;
    private String projectName;
    /** 业主企业ID */
    private Long enterpriseId;
    /** 业主企业名称（join enterprise） */
    private String enterpriseName;
    private String regionCode;
    private String regionName;
    private BigDecimal projectAmount;
    private String projectType;
    /** BIDDING/AWARDED/BUILDING/FINISHED */
    private String status;
    private LocalDate tenderDate;
}
