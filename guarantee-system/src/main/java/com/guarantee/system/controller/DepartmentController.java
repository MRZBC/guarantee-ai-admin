package com.guarantee.system.controller;

import com.guarantee.common.api.PageResult;
import com.guarantee.common.api.Result;
import com.guarantee.common.api.ResultCode;
import com.guarantee.common.exception.BizException;
import com.guarantee.common.security.CurrentUser;
import com.guarantee.common.security.LogicalDeletePermissions;
import com.guarantee.common.security.Permissions;
import com.guarantee.system.dto.DepartmentDto;
import com.guarantee.system.scope.DataScope;
import com.guarantee.system.scope.DataScopeService;
import com.guarantee.system.service.DepartmentService;
import com.guarantee.system.vo.DepartmentOptionVO;
import com.guarantee.system.vo.DepartmentVO;
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
 * 部门配置接口。写操作与显式权限码（SYS-P-01 / SYS-W-03）。
 */
@RestController
@RequestMapping("/api/system/departments")
public class DepartmentController {

    private final DepartmentService departmentService;
    private final DataScopeService dataScopeService;

    public DepartmentController(DepartmentService departmentService, DataScopeService dataScopeService) {
        this.departmentService = departmentService;
        this.dataScopeService = dataScopeService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.DEPT_VIEW + "')")
    public Result<PageResult<DepartmentVO>> list(@Valid DepartmentDto.Query query) {
        requireDeletePermissionWhenIncludingDeleted(query.getIncludeDeleted());
        return Result.ok(departmentService.page(query, currentScope()));
    }

    /**
     * 树形数据源（SYS-C-22 的"同构方案"：按机构树 SYS-C-21 的口径新增扁平全量接口）。
     *
     * <p>与既有 {@code GET /api/system/departments} 并列新增，分页接口与 {@code PageResult}
     * 结构保持不变，避免影响助手侧 {@code queryDepartment} 与其他调用方。</p>
     *
     * <p>树与分页列表共用同一查询口径，因此 {@code includeDeleted} 的参数级校验必须同样生效，
     * 否则可以绕过列表的参数级校验，从树接口读到已删除部门。</p>
     */
    @GetMapping("/tree")
    @PreAuthorize("hasAuthority('" + Permissions.DEPT_VIEW + "')")
    public Result<List<DepartmentVO>> tree(@Valid DepartmentDto.Query query) {
        requireDeletePermissionWhenIncludingDeleted(query.getIncludeDeleted());
        return Result.ok(departmentService.tree(query, currentScope()));
    }

    /** 下拉框使用：仅启用部门，不分页（阶段一 O3 起部门已无机构维度，故无 orgId 过滤参数）。 */
    @GetMapping("/options")
    @PreAuthorize("hasAuthority('" + Permissions.DEPT_VIEW + "')")
    public Result<List<DepartmentOptionVO>> options() {
        return Result.ok(departmentService.listOptions());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.DEPT_VIEW + "')")
    public Result<DepartmentVO> detail(@PathVariable Long id) {
        return Result.ok(departmentService.getById(id, currentScope()));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Permissions.DEPT_CREATE + "')")
    public Result<DepartmentVO> create(@Valid @RequestBody DepartmentDto.CreateRequest request) {
        return Result.ok(departmentService.create(request, currentScope()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.DEPT_UPDATE + "')")
    public Result<DepartmentVO> update(@PathVariable Long id,
                                       @Valid @RequestBody DepartmentDto.UpdateRequest request) {
        return Result.ok(departmentService.update(id, request, currentScope()));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('" + Permissions.DEPT_DISABLE + "')")
    public Result<DepartmentVO> changeStatus(@PathVariable Long id,
                                             @Valid @RequestBody DepartmentDto.StatusRequest request) {
        return Result.ok(departmentService.changeStatus(id, request.getStatus(), currentScope()));
    }

    private DataScope currentScope() {
        var principal = CurrentUser.get();
        if (principal == null) {
            return DataScope.of(null, null, List.of(), "未登录（无可见范围）");
        }
        return dataScopeService.resolve(principal.userId(), principal.roles());
    }
    /**
     * 逻辑删除（设计 §7.1）。删除不是物理删除：记录仍在库中，可在「显示已删除」中恢复。
     *
     * <p>恢复与删除共用 {@code DEPT_DELETE} 权限：能删就能恢复，避免"删了不能恢复"的单向能力（LD-04）。</p>
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.DEPT_DELETE + "')")
    public Result<DepartmentVO> delete(@PathVariable Long id) {
        return Result.ok(departmentService.delete(id, currentScope(), currentUserId()));
    }

    /** 恢复（POST /{id}/restore）：语义是"执行一个逆向动作"，非幂等 PATCH（设计 §7.1）。 */
    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('" + Permissions.DEPT_DELETE + "')")
    public Result<DepartmentVO> restore(@PathVariable Long id) {
        return Result.ok(departmentService.restore(id, currentUserId()));
    }

    /**
     * 「显示已删除」（includeDeleted=true）需要 DEPT_DELETE 权限（设计 §7.1）。
     *
     * <p>参数级权限无法用 {@code @PreAuthorize} 表达，因此在方法体内显式判定：
     * **服务端强制**，前端隐藏开关只是体验优化（SYS-NF-04）。</p>
     */
    private static void requireDeletePermissionWhenIncludingDeleted(Boolean includeDeleted) {
        // 判定逻辑收敛到 guarantee-common（单一实现，可单测）：参数级权限无法用 @PreAuthorize 表达
        LogicalDeletePermissions.requireIncludeDeleted(includeDeleted, Permissions.DEPT_DELETE, "部门");
    }

    private static Long currentUserId() {
        return CurrentUser.userId();
    }

}
