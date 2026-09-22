package com.guarantee.system.controller;

import com.guarantee.common.api.PageResult;
import com.guarantee.common.api.Result;
import com.guarantee.common.api.ResultCode;
import com.guarantee.common.exception.BizException;
import com.guarantee.common.security.CurrentUser;
import com.guarantee.common.security.LogicalDeletePermissions;
import com.guarantee.common.security.Permissions;
import com.guarantee.system.dto.UserDto;
import com.guarantee.system.scope.DataScope;
import com.guarantee.system.scope.DataScopeService;
import com.guarantee.system.service.UserService;
import com.guarantee.system.vo.UserVO;
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
 * 用户配置接口。响应 VO 不含密码字段。
 *
 * <p><b>D-2 的接口级落点</b>：本类**没有** {@code POST /api/system/users} 与
 * {@code POST /api/system/users/{id}/reset-password}。新建账号与密码重置确定单独立项，
 * 不在本需求交付（见需求 5.2.2 D-2 / D-2a）。</p>
 *
 * <p><b>Q-15 暂缓口径</b>：本期页面接口的返回行为保持不变（仍是 {@code UserVO} 直出），
 * 字段脱敏只作用于助手工具返回值、审计、提案与 {@code ai_tool_call} 四条路径。</p>
 */
@RestController
@RequestMapping("/api/system/users")
public class UserController {

    private final UserService userService;
    private final DataScopeService dataScopeService;

    public UserController(UserService userService, DataScopeService dataScopeService) {
        this.userService = userService;
        this.dataScopeService = dataScopeService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.USER_VIEW + "')")
    public Result<PageResult<UserVO>> list(@Valid UserDto.Query query) {
        requireDeletePermissionWhenIncludingDeleted(query.getIncludeDeleted());
        return Result.ok(userService.page(query, currentScope()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.USER_VIEW + "')")
    public Result<UserVO> detail(@PathVariable Long id) {
        return Result.ok(userService.getById(id, currentScope()));
    }

    /** 修改用户资料（SYS-W-04 UPDATE）。 */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.USER_UPDATE + "')")
    public Result<UserVO> update(@PathVariable Long id,
                                 @Valid @RequestBody UserDto.UpdateRequest request) {
        return Result.ok(userService.updateProfile(id, request, currentScope(), currentUserId()));
    }

    /** 启用/停用（SYS-W-04 ENABLE/DISABLE）。停用后该用户全部令牌被撤销。 */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('" + Permissions.USER_DISABLE + "')")
    public Result<UserVO> changeStatus(@PathVariable Long id,
                                       @Valid @RequestBody UserDto.StatusRequest request) {
        return Result.ok(userService.changeStatus(id, request.getStatus(), currentScope(), currentUserId()));
    }

    /** 角色分配（SYS-W-04 ASSIGN_ROLES）。变更后该用户全部令牌被撤销。 */
    @PutMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('" + Permissions.USER_ASSIGN_ROLE + "')")
    public Result<UserVO> assignRoles(@PathVariable Long id,
                                      @Valid @RequestBody UserDto.AssignRolesRequest request) {
        return Result.ok(userService.assignRoles(id, request.getRoleCodes(), currentScope(), currentUserId()));
    }

    private DataScope currentScope() {
        var principal = CurrentUser.get();
        if (principal == null) {
            return DataScope.of(null, null, List.of(), "未登录（无可见范围）");
        }
        return dataScopeService.resolve(principal.userId(), principal.roles());
    }

    private static Long currentUserId() {
        return CurrentUser.userId();
    }
    /**
     * 逻辑删除（设计 §7.1）。删除不是物理删除：记录仍在库中，可在「显示已删除」中恢复。
     *
     * <p>恢复与删除共用 {@code USER_DELETE} 权限：能删就能恢复，避免"删了不能恢复"的单向能力（LD-04）。</p>
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.USER_DELETE + "')")
    public Result<UserVO> delete(@PathVariable Long id) {
        return Result.ok(userService.delete(id, currentScope(), currentUserId()));
    }

    /** 恢复（POST /{id}/restore）：语义是"执行一个逆向动作"，非幂等 PATCH（设计 §7.1）。 */
    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('" + Permissions.USER_DELETE + "')")
    public Result<UserVO> restore(@PathVariable Long id) {
        return Result.ok(userService.restore(id, currentUserId()));
    }

    /**
     * 「显示已删除」（includeDeleted=true）需要 USER_DELETE 权限（设计 §7.1）。
     *
     * <p>参数级权限无法用 {@code @PreAuthorize} 表达，因此在方法体内显式判定：
     * **服务端强制**，前端隐藏开关只是体验优化（SYS-NF-04）。</p>
     */
    private static void requireDeletePermissionWhenIncludingDeleted(Boolean includeDeleted) {
        // 判定逻辑收敛到 guarantee-common（单一实现，可单测）：参数级权限无法用 @PreAuthorize 表达
        LogicalDeletePermissions.requireIncludeDeleted(includeDeleted, Permissions.USER_DELETE, "用户");
    }

}
