package com.guarantee.system.dto;

import lombok.Data;

/**
 * 角色维度的计数结果（{@code roleId -> cnt}）。
 *
 * <p>用于 {@code queryRole} 一次取回"权限数 / 用户数"，避免逐角色查询造成 N+1。</p>
 */
@Data
public class RoleCountRef {

    private Long roleId;
    private Long cnt;
}
