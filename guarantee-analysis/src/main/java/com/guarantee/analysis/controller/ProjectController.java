package com.guarantee.analysis.controller;

import com.guarantee.analysis.dto.ProjectQuery;
import com.guarantee.analysis.service.ProjectService;
import com.guarantee.analysis.vo.ProjectDetailVO;
import com.guarantee.analysis.vo.ProjectVO;
import com.guarantee.common.api.PageResult;
import com.guarantee.common.api.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目管理接口。
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    /** 项目分页列表：projectName(LIKE) / regionCode / projectType / status / enterpriseId。 */
    @GetMapping
    public Result<PageResult<ProjectVO>> list(@Valid ProjectQuery query) {
        return Result.ok(projectService.page(query));
    }

    /** 项目详情：基础信息 + 订单指标。 */
    @GetMapping("/{id}")
    public Result<ProjectDetailVO> detail(@PathVariable Long id) {
        return Result.ok(projectService.detail(id));
    }
}
