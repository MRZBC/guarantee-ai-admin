package com.guarantee.system.controller;

import com.guarantee.common.api.PageResult;
import com.guarantee.common.api.Result;
import com.guarantee.system.dto.DepartmentDto;
import com.guarantee.system.service.DepartmentService;
import com.guarantee.system.vo.DepartmentOptionVO;
import com.guarantee.system.vo.DepartmentVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 部门配置接口。
 */
@RestController
@RequestMapping("/api/system/departments")
public class DepartmentController {

    private final DepartmentService departmentService;

    public DepartmentController(DepartmentService departmentService) {
        this.departmentService = departmentService;
    }

    @GetMapping
    public Result<PageResult<DepartmentVO>> list(@Valid DepartmentDto.Query query) {
        return Result.ok(departmentService.page(query));
    }

    /** 下拉框使用：仅启用部门，orgId 可选，不分页。 */
    @GetMapping("/options")
    public Result<List<DepartmentOptionVO>> options(@RequestParam(required = false) Long orgId) {
        return Result.ok(departmentService.listOptions(orgId));
    }
}
