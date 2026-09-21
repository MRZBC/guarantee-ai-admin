package com.guarantee.analysis.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 项目（对应 project 表）。
 */
@Data
public class Project {

    private Long id;
    private String projectCode;
    private String projectName;
    /** 业主企业 */
    private Long enterpriseId;
    private String regionCode;
    private String regionName;
    /** 项目金额 */
    private BigDecimal projectAmount;
    /** 房建/市政/交通/水利/其他 */
    private String projectType;
    /** BIDDING/AWARDED/BUILDING/FINISHED */
    private String status;
    /** 招标日期 */
    private LocalDate tenderDate;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
