package com.guarantee.order.dto;

import com.guarantee.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * 履约订单列表查询条件。
 *
 * <p>使用 POJO 而非 record：MyBatis 需要通过标准 getter 解析 OGNL 表达式。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PerformanceOrderQuery extends PageQuery {

    /** 订单号，模糊匹配 */
    private String orderNo;

    private String regionCode;

    /** 承保机构 */
    private Long orgId;

    private Long insuranceTypeId;

    /** DRAFT/UNDER_REVIEW/EFFECTIVE/EXPIRED/RELEASED */
    private String status;

    /** 申请日期起（含） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startDate;

    /** 申请日期止（含） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;

    private Long projectId;

    private Long enterpriseId;
}
