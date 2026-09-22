package com.guarantee.system.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 部门配置响应对象（MyBatis 结果类型）。
 *
 * <p>部门是内部组织单元，不挂机构 —— 机构是外部出函机构，服务于订单。</p>
 */
@Data
public class DepartmentVO {

    private Long id;
    private String deptCode;
    private String deptName;
    private Long parentId;
    /** 状态 1启用 0停用 */
    private Integer status;
    private Integer sortNo;
    private LocalDateTime createdAt;
    /** 逻辑删除 0正常 1已删除 */
    private Integer isDeleted;
    /** 删除时间（DATETIME(6)，未删除为 null） */
    private LocalDateTime deletedAt;
    /** 删除人标识：应用删除记为 sys_user.id 的字符串，数据库直连删除为 "DB"（设计 §2.1a） */
    private String deletedBy;

}
