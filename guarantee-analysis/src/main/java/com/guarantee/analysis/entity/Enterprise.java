package com.guarantee.analysis.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 企业（对应 enterprise 表）。
 */
@Data
public class Enterprise {

    private Long id;
    private String entCode;
    private String entName;
    /** 统一社会信用代码 */
    private String creditCode;
    private String regionCode;
    private String regionName;
    /** 行业 */
    private String industry;
    /** AAA/AA/A/BBB */
    private String entLevel;
    private String contactName;
    private String contactPhone;
    /** 1启用 0停用 */
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
