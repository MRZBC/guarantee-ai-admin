package com.guarantee.system.controller;

import com.guarantee.common.api.PageResult;
import com.guarantee.common.api.Result;
import com.guarantee.system.dto.RoleDto;
import com.guarantee.system.service.RoleService;
import com.guarantee.system.vo.RoleVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 角色配置接口。
 */
@RestController
@RequestMapping("/api/system/roles")
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping
    public Result<PageResult<RoleVO>> list(@Valid RoleDto.Query query) {
        return Result.ok(roleService.page(query));
    }

    /** 详情包含 permissionIds / permissionNames。 */
    @GetMapping("/{id}")
    public Result<RoleVO> detail(@PathVariable Long id) {
        return Result.ok(roleService.getById(id));
    }
}
