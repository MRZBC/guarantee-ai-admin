package com.guarantee.system.controller;

import com.guarantee.common.api.PageResult;
import com.guarantee.common.api.Result;
import com.guarantee.system.dto.OrgDto;
import com.guarantee.system.service.OrgService;
import com.guarantee.system.vo.OrgOptionVO;
import com.guarantee.system.vo.OrgVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 机构配置接口。
 */
@RestController
@RequestMapping("/api/system/orgs")
public class OrgController {

    private final OrgService orgService;

    public OrgController(OrgService orgService) {
        this.orgService = orgService;
    }

    @GetMapping
    public Result<PageResult<OrgVO>> list(@Valid OrgDto.Query query) {
        return Result.ok(orgService.page(query));
    }

    /** 下拉框使用：仅启用机构，不分页。 */
    @GetMapping("/options")
    public Result<List<OrgOptionVO>> options() {
        return Result.ok(orgService.listOptions());
    }

    @GetMapping("/{id}")
    public Result<OrgVO> detail(@PathVariable Long id) {
        return Result.ok(orgService.getById(id));
    }
}
