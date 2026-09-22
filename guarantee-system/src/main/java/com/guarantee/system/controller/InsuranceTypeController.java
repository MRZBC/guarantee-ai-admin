package com.guarantee.system.controller;

import com.guarantee.common.api.PageResult;
import com.guarantee.common.api.Result;
import com.guarantee.common.api.ResultCode;
import com.guarantee.common.exception.BizException;
import com.guarantee.common.security.CurrentUser;
import com.guarantee.common.security.LogicalDeletePermissions;
import com.guarantee.common.security.Permissions;
import com.guarantee.system.dto.InsuranceTypeDto;
import com.guarantee.system.service.InsuranceTypeService;
import com.guarantee.system.vo.InsuranceTypeVO;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 险种配置接口。
 *
 * <p>这是本需求之前**唯一**有写操作的系统管理域。补齐 {@code @PreAuthorize} 后，
 * 页面渠道的险种写操作正式进入权限边界（SYS-P-01）；其二期审计接入见 SYS-A-07。</p>
 */
@RestController
@RequestMapping("/api/system/insurance-types")
public class InsuranceTypeController {

    private final InsuranceTypeService insuranceTypeService;

    public InsuranceTypeController(InsuranceTypeService insuranceTypeService) {
        this.insuranceTypeService = insuranceTypeService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.INSURANCE_VIEW + "')")
    public Result<PageResult<InsuranceTypeVO>> list(@Valid InsuranceTypeDto.Query query) {
        requireDeletePermissionWhenIncludingDeleted(query.getIncludeDeleted());
        return Result.ok(insuranceTypeService.page(query));
    }

    /** 下拉框使用：不分页。 */
    @GetMapping("/options")
    @PreAuthorize("hasAuthority('" + Permissions.INSURANCE_VIEW + "')")
    public Result<List<InsuranceTypeVO>> options() {
        return Result.ok(insuranceTypeService.listAllEnabled());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.INSURANCE_VIEW + "')")
    public Result<InsuranceTypeVO> detail(@PathVariable Long id) {
        return Result.ok(insuranceTypeService.getById(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Permissions.INSURANCE_CREATE + "')")
    public Result<InsuranceTypeVO> create(@Valid @RequestBody InsuranceTypeDto.CreateRequest request) {
        return Result.ok(insuranceTypeService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.INSURANCE_UPDATE + "')")
    public Result<InsuranceTypeVO> update(@PathVariable Long id,
                                          @Valid @RequestBody InsuranceTypeDto.UpdateRequest request) {
        return Result.ok(insuranceTypeService.update(id, request));
    }

    /** 险种启停（新增权限码 {@code system:insurance:disable}）。 */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('" + Permissions.INSURANCE_DISABLE + "')")
    public Result<InsuranceTypeVO> changeStatus(@PathVariable Long id,
                                                @Valid @RequestBody InsuranceTypeDto.StatusRequest request) {
        return Result.ok(insuranceTypeService.changeStatus(id, request.getStatus()));
    }
    /**
     * 逻辑删除（设计 §7.1）。删除不是物理删除：记录仍在库中，可在「显示已删除」中恢复。
     *
     * <p>恢复与删除共用 {@code INSURANCE_DELETE} 权限：能删就能恢复，避免"删了不能恢复"的单向能力（LD-04）。</p>
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.INSURANCE_DELETE + "')")
    public Result<InsuranceTypeVO> delete(@PathVariable Long id) {
        return Result.ok(insuranceTypeService.delete(id, currentUserId()));
    }

    /** 恢复（POST /{id}/restore）：语义是"执行一个逆向动作"，非幂等 PATCH（设计 §7.1）。 */
    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('" + Permissions.INSURANCE_DELETE + "')")
    public Result<InsuranceTypeVO> restore(@PathVariable Long id) {
        return Result.ok(insuranceTypeService.restore(id, currentUserId()));
    }

    /**
     * 「显示已删除」（includeDeleted=true）需要 INSURANCE_DELETE 权限（设计 §7.1）。
     *
     * <p>参数级权限无法用 {@code @PreAuthorize} 表达，因此在方法体内显式判定：
     * **服务端强制**，前端隐藏开关只是体验优化（SYS-NF-04）。</p>
     */
    private static void requireDeletePermissionWhenIncludingDeleted(Boolean includeDeleted) {
        // 判定逻辑收敛到 guarantee-common（单一实现，可单测）：参数级权限无法用 @PreAuthorize 表达
        LogicalDeletePermissions.requireIncludeDeleted(includeDeleted, Permissions.INSURANCE_DELETE, "险种");
    }

    private static Long currentUserId() {
        return CurrentUser.userId();
    }

}
