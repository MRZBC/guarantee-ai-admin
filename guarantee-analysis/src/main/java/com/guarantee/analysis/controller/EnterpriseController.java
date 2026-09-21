package com.guarantee.analysis.controller;

import com.guarantee.analysis.dto.EnterpriseQuery;
import com.guarantee.analysis.service.EnterpriseService;
import com.guarantee.analysis.vo.EnterpriseDetailVO;
import com.guarantee.analysis.vo.EnterpriseVO;
import com.guarantee.common.api.PageResult;
import com.guarantee.common.api.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 企业管理接口。
 */
@RestController
@RequestMapping("/api/enterprises")
public class EnterpriseController {

    private final EnterpriseService enterpriseService;

    public EnterpriseController(EnterpriseService enterpriseService) {
        this.enterpriseService = enterpriseService;
    }

    /** 企业分页列表：entName(LIKE) / regionCode / industry / entLevel / status。 */
    @GetMapping
    public Result<PageResult<EnterpriseVO>> list(@Valid EnterpriseQuery query) {
        return Result.ok(enterpriseService.page(query));
    }

    /** 企业详情：基础信息 + 项目/订单细分指标。 */
    @GetMapping("/{id}")
    public Result<EnterpriseDetailVO> detail(@PathVariable Long id) {
        return Result.ok(enterpriseService.detail(id));
    }
}
