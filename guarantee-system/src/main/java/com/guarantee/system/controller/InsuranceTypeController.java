package com.guarantee.system.controller;

import com.guarantee.common.api.PageResult;
import com.guarantee.common.api.Result;
import com.guarantee.system.dto.InsuranceTypeDto;
import com.guarantee.system.service.InsuranceTypeService;
import com.guarantee.system.vo.InsuranceTypeVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 险种配置接口。
 */
@RestController
@RequestMapping("/api/system/insurance-types")
public class InsuranceTypeController {

    private final InsuranceTypeService insuranceTypeService;

    public InsuranceTypeController(InsuranceTypeService insuranceTypeService) {
        this.insuranceTypeService = insuranceTypeService;
    }

    @GetMapping
    public Result<PageResult<InsuranceTypeVO>> list(@Valid InsuranceTypeDto.Query query) {
        return Result.ok(insuranceTypeService.page(query));
    }

    /** 下拉框使用：不分页。 */
    @GetMapping("/options")
    public Result<List<InsuranceTypeVO>> options() {
        return Result.ok(insuranceTypeService.listAllEnabled());
    }

    @GetMapping("/{id}")
    public Result<InsuranceTypeVO> detail(@PathVariable Long id) {
        return Result.ok(insuranceTypeService.getById(id));
    }

    @PostMapping
    public Result<InsuranceTypeVO> create(@Valid @RequestBody InsuranceTypeDto.CreateRequest request) {
        return Result.ok(insuranceTypeService.create(request));
    }

    @PutMapping("/{id}")
    public Result<InsuranceTypeVO> update(@PathVariable Long id,
                                          @Valid @RequestBody InsuranceTypeDto.UpdateRequest request) {
        return Result.ok(insuranceTypeService.update(id, request));
    }
}
