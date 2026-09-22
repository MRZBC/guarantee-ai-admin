package com.guarantee.system.controller;

import com.guarantee.common.api.Result;
import com.guarantee.common.security.Permissions;
import com.guarantee.system.service.PermissionService;
import com.guarantee.system.vo.PermissionVO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 权限配置接口。返回扁平列表，前端按 parentId 组树。
 *
 * <p>权限主数据**只读**：R-04 明确禁止修改/删除权限主数据，因此本类没有任何写方法。</p>
 */
@RestController
@RequestMapping("/api/system/permissions")
public class PermissionController {

    private final PermissionService permissionService;

    public PermissionController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.PERMISSION_VIEW + "')")
    public Result<List<PermissionVO>> list() {
        return Result.ok(permissionService.listAll());
    }
}
