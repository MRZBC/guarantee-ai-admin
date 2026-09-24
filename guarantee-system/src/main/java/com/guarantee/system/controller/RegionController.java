package com.guarantee.system.controller;

import com.guarantee.common.api.Result;
import com.guarantee.system.service.RegionService;
import com.guarantee.system.vo.RegionOptionVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 行政区划（地区）字典接口。
 *
 * <p><b>授权：登录即可，刻意不加 {@code @PreAuthorize}</b>（依据
 * {@code docs/REQ-地区基础信息与区域筛选下拉.md} D5）。地区是国标公开数据、最低敏，
 * 且被订单 / 项目 / 企业 / 机构配置四个业务页面共用；而
 * {@code docs/DEC-业务筛选下拉的授权口径.md} 的教训是——
 * **筛选字典的授权必须对齐"能不能看业务数据"，不能因为它挂在 {@code /system} 路径下就要求
 * 系统配置权限**，否则"能看订单但没有系统配置权限"的角色会一进页面就吃 403。</p>
 */
@RestController
@RequestMapping("/api/system/regions")
public class RegionController {

    private final RegionService regionService;

    public RegionController(RegionService regionService) {
        this.regionService = regionService;
    }

    /**
     * 地区下拉选项。
     *
     * @param level        层级：1 省 / 2 市 / 3 区县；不传 = **全层级**（前端级联控件用这个）
     * @param parentCode   上级区划码（做省 → 市 → 区县级联时用）；不传 = 不限
     * @param onlyWithData 默认 **false**：返回全部启用地区（产品口径是"下拉看到整本字典"）。
     *                     传 true 可只返回"当前有业务数据"的地区（对账/分析等场景用）
     */
    @GetMapping("/options")
    public Result<List<RegionOptionVO>> options(
            @RequestParam(required = false) Integer level,
            @RequestParam(required = false) String parentCode,
            @RequestParam(required = false, defaultValue = "false") boolean onlyWithData) {
        return Result.ok(regionService.listOptions(level, parentCode, onlyWithData));
    }
}
