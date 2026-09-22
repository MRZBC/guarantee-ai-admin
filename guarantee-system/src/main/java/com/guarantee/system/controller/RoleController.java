package com.guarantee.system.controller;

import com.guarantee.common.api.PageResult;
import com.guarantee.common.api.Result;
import com.guarantee.common.api.ResultCode;
import com.guarantee.common.exception.BizException;
import com.guarantee.common.security.CurrentUser;
import com.guarantee.common.security.LogicalDeletePermissions;
import com.guarantee.common.security.Permissions;
import com.guarantee.system.dto.RoleDto;
import com.guarantee.system.scope.DataScope;
import com.guarantee.system.scope.DataScopeService;
import com.guarantee.system.service.RoleService;
import com.guarantee.system.vo.RoleVO;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色配置接口。写操作与显式权限码（SYS-P-01 / SYS-W-05）。
 *
 * <p>{@code ADMIN} 角色的修改与授权由 Service 层拒绝；权限只能在既有权限码中选择。</p>
 */
@RestController
@RequestMapping("/api/system/roles")
public class RoleController {

    private final RoleService roleService;
    private final DataScopeService dataScopeService;

    public RoleController(RoleService roleService, DataScopeService dataScopeService) {
        this.roleService = roleService;
        this.dataScopeService = dataScopeService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.ROLE_VIEW + "')")
    public Result<PageResult<RoleVO>> list(@Valid RoleDto.Query query) {
        requireDeletePermissionWhenIncludingDeleted(query.getIncludeDeleted());
        return Result.ok(roleService.page(query, currentScope()));
    }

    /** 详情包含 permissionIds / permissionNames / permissionCount / userCount。 */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.ROLE_VIEW + "')")
    public Result<RoleVO> detail(@PathVariable Long id) {
        return Result.ok(roleService.getById(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Permissions.ROLE_CREATE + "')")
    public Result<RoleVO> create(@Valid @RequestBody RoleDto.CreateRequest request) {
        return Result.ok(roleService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.ROLE_UPDATE + "')")
    public Result<RoleVO> update(@PathVariable Long id,
                                 @Valid @RequestBody RoleDto.UpdateRequest request) {
        return Result.ok(roleService.update(id, request));
    }

    /** 角色授权（ASSIGN_PERMISSIONS）。变更后撤销持有该角色用户的令牌。 */
    @PutMapping("/{roleCode}/permissions")
    @PreAuthorize("hasAuthority('" + Permissions.ROLE_ASSIGN_PERMISSION + "')")
    public Result<RoleVO> assignPermissions(@PathVariable String roleCode,
                                            @Valid @RequestBody RoleDto.AssignPermissionsRequest request) {
        return Result.ok(roleService.assignPermissions(roleCode, request.getPermCodes()));
    }

    private DataScope currentScope() {
        var principal = CurrentUser.get();
        if (principal == null) {
            return DataScope.of(null, null, List.of(), "未登录（无可见范围）");
        }
        return dataScopeService.resolve(principal.userId(), principal.orgId(), principal.roles());
    }
    /**
     * 逻辑删除（设计 §7.1）。删除不是物理删除：记录仍在库中，可在「显示已删除」中恢复。
     *
     * <p>恢复与删除共用 {@code ROLE_DELETE} 权限：能删就能恢复，避免"删了不能恢复"的单向能力（LD-04）。</p>
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.ROLE_DELETE + "')")
    public Result<RoleVO> delete(@PathVariable Long id) {
        return Result.ok(roleService.delete(id, currentUserId()));
    }

    /** 恢复（POST /{id}/restore）：语义是"执行一个逆向动作"，非幂等 PATCH（设计 §7.1）。 */
    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('" + Permissions.ROLE_DELETE + "')")
    public Result<RoleVO> restore(@PathVariable Long id) {
        return Result.ok(roleService.restore(id, currentUserId()));
    }

    /**
     * 「显示已删除」（includeDeleted=true）需要 ROLE_DELETE 权限（设计 §7.1）。
     *
     * <p>参数级权限无法用 {@code @PreAuthorize} 表达，因此在方法体内显式判定：
     * **服务端强制**，前端隐藏开关只是体验优化（SYS-NF-04）。</p>
     */
    private static void requireDeletePermissionWhenIncludingDeleted(Boolean includeDeleted) {
        // 判定逻辑收敛到 guarantee-common（单一实现，可单测）：参数级权限无法用 @PreAuthorize 表达
        LogicalDeletePermissions.requireIncludeDeleted(includeDeleted, Permissions.ROLE_DELETE, "角色");
    }

    private static Long currentUserId() {
        return CurrentUser.userId();
    }

}
