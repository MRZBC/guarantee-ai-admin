package com.guarantee.order.controller;

import com.guarantee.common.api.PageResult;
import com.guarantee.common.api.Result;
import com.guarantee.order.dto.TenderOrderQuery;
import com.guarantee.order.service.TenderOrderService;
import com.guarantee.order.vo.TenderOrderVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 投标订单接口。
 */
@RestController
@RequestMapping("/api/orders/tender")
public class TenderOrderController {

    private final TenderOrderService tenderOrderService;

    public TenderOrderController(TenderOrderService tenderOrderService) {
        this.tenderOrderService = tenderOrderService;
    }

    /** 分页查询。 */
    @GetMapping
    public Result<PageResult<TenderOrderVO>> list(@Valid TenderOrderQuery query) {
        return Result.ok(tenderOrderService.page(query));
    }

    /** 订单详情。 */
    @GetMapping("/{id}")
    public Result<TenderOrderVO> detail(@PathVariable Long id) {
        return Result.ok(tenderOrderService.getById(id));
    }
}
