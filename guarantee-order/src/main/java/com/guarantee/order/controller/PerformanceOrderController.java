package com.guarantee.order.controller;

import com.guarantee.common.api.PageResult;
import com.guarantee.common.api.Result;
import com.guarantee.order.dto.PerformanceOrderQuery;
import com.guarantee.order.service.PerformanceOrderService;
import com.guarantee.order.vo.PerformanceOrderVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 履约订单接口。
 */
@RestController
@RequestMapping("/api/orders/performance")
public class PerformanceOrderController {

    private final PerformanceOrderService performanceOrderService;

    public PerformanceOrderController(PerformanceOrderService performanceOrderService) {
        this.performanceOrderService = performanceOrderService;
    }

    /** 分页查询。 */
    @GetMapping
    public Result<PageResult<PerformanceOrderVO>> list(@Valid PerformanceOrderQuery query) {
        return Result.ok(performanceOrderService.page(query));
    }

    /** 订单详情。 */
    @GetMapping("/{id}")
    public Result<PerformanceOrderVO> detail(@PathVariable Long id) {
        return Result.ok(performanceOrderService.getById(id));
    }
}
