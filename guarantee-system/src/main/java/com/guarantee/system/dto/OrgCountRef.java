package com.guarantee.system.dto;

import lombok.Data;

/**
 * 机构维度的计数结果（{@code orgId -> deptCount / userCount}）。
 *
 * <p>用于 {@code queryOrg} 一次取回"部门数 / 用户数"，避免逐机构查询造成 N+1。</p>
 */
@Data
public class OrgCountRef {

    private Long orgId;
    private Long deptCount;
    private Long userCount;
}
