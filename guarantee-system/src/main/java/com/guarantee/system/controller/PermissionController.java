package com.guarantee.system.controller;

import com.guarantee.common.api.Result;
import com.guarantee.system.service.PermissionService;
import com.guarantee.system.vo.PermissionVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 权限配置接口。返回扁平列表，前端按 parentId 组树。
 */
@RestController
@RequestMapping("/api/system/permissions")
public class PermissionController {

    private final PermissionService permissionService;

    public PermissionController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @GetMapping
    public Result<List<PermissionVO>> list() {
        return Result.ok(permissionService.listAll());
    }
}
