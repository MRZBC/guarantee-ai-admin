package com.guarantee.system.controller;

import com.guarantee.common.api.PageResult;
import com.guarantee.common.api.Result;
import com.guarantee.common.api.ResultCode;
import com.guarantee.common.exception.BizException;
import com.guarantee.common.security.CurrentUser;
import com.guarantee.common.security.LogicalDeletePermissions;
import com.guarantee.common.security.Permissions;
import com.guarantee.system.dto.OrgDto;
import com.guarantee.system.scope.DataScope;
import com.guarantee.system.scope.DataScopeService;
import com.guarantee.system.service.OrgService;
import com.guarantee.system.vo.OrgOptionVO;
import com.guarantee.system.vo.OrgVO;
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
 * 机构配置接口。
 *
 * <p><b>权限</b>：每个方法都有 {@code @PreAuthorize}（SYS-P-01）。URL 层的"是否登录"只是
 * 第一道门槛，方法级鉴权才是安全边界；前端按钮的显隐只是体验优化（SYS-NF-04）。</p>
 *
 * <p><b>数据范围</b>：Controller 负责把 {@code CurrentUser} 解析成 {@link DataScope}
 * 并下传，Service 只认范围对象（SYS-P-10）。</p>
 */
@RestController
@RequestMapping("/api/system/orgs")
public class OrgController {

    private final OrgService orgService;
    private final DataScopeService dataScopeService;

    public OrgController(OrgService orgService, DataScopeService dataScopeService) {
        this.orgService = orgService;
        this.dataScopeService = dataScopeService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.ORG_VIEW + "')")
    public Result<PageResult<OrgVO>> list(@Valid OrgDto.Query query) {
        requireDeletePermissionWhenIncludingDeleted(query.getIncludeDeleted());
        return Result.ok(orgService.page(query, currentScope()));
    }

    /**
     * 树形数据源（SYS-C-21 / SYS-C-24）。
     *
     * <p>与既有 {@code GET /api/system/orgs} 并列新增，分页接口与 {@code PageResult}
     * 结构保持不变，避免影响其他调用方。</p>
     */
    @GetMapping("/tree")
    @PreAuthorize("hasAuthority('" + Permissions.ORG_VIEW + "')")
    public Result<List<OrgVO>> tree(@Valid OrgDto.Query query) {
        // 树与分页列表共用同一查询口径，因此 includeDeleted 的权限校验必须同样生效，
        // 否则可以绕过列表的参数级校验，从树接口读到已删除机构（设计 §7.1）
        requireDeletePermissionWhenIncludingDeleted(query.getIncludeDeleted());
        return Result.ok(orgService.tree(query, currentScope()));
    }

    /** 下拉框使用：仅启用机构，不分页。 */
    @GetMapping("/options")
    @PreAuthorize("hasAuthority('" + Permissions.ORG_VIEW + "')")
    public Result<List<OrgOptionVO>> options() {
        return Result.ok(orgService.listOptions());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.ORG_VIEW + "')")
    public Result<OrgVO> detail(@PathVariable Long id) {
        return Result.ok(orgService.getById(id, currentScope()));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Permissions.ORG_CREATE + "')")
    public Result<OrgVO> create(@Valid @RequestBody OrgDto.CreateRequest request) {
        return Result.ok(orgService.create(request, currentScope()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.ORG_UPDATE + "')")
    public Result<OrgVO> update(@PathVariable Long id,
                                @Valid @RequestBody OrgDto.UpdateRequest request) {
        return Result.ok(orgService.update(id, request, currentScope()));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('" + Permissions.ORG_DISABLE + "')")
    public Result<OrgVO> changeStatus(@PathVariable Long id,
                                      @Valid @RequestBody OrgDto.StatusRequest request) {
        return Result.ok(orgService.changeStatus(id, request.getStatus(), currentScope()));
    }

    /** 当前请求的数据范围；未登录时按最小范围处理（URL 层已拦截未登录请求）。 */
    private DataScope currentScope() {
        var principal = com.guarantee.common.security.CurrentUser.get();
        if (principal == null) {
            return DataScope.of(null, null, List.of(), "未登录（无可见范围）");
        }
        return dataScopeService.resolve(principal.userId(), principal.orgId(), principal.roles());
    }
    /**
     * 逻辑删除（设计 §7.1）。删除不是物理删除：记录仍在库中，可在「显示已删除」中恢复。
     *
     * <p>恢复与删除共用 {@code ORG_DELETE} 权限：能删就能恢复，避免"删了不能恢复"的单向能力（LD-04）。</p>
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.ORG_DELETE + "')")
    public Result<OrgVO> delete(@PathVariable Long id) {
        return Result.ok(orgService.delete(id, currentScope(), currentUserId()));
    }

    /** 恢复（POST /{id}/restore）：语义是"执行一个逆向动作"，非幂等 PATCH（设计 §7.1）。 */
    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('" + Permissions.ORG_DELETE + "')")
    public Result<OrgVO> restore(@PathVariable Long id) {
        return Result.ok(orgService.restore(id, currentUserId()));
    }

    /**
     * 「显示已删除」（includeDeleted=true）需要 ORG_DELETE 权限（设计 §7.1）。
     *
     * <p>参数级权限无法用 {@code @PreAuthorize} 表达，因此在方法体内显式判定：
     * **服务端强制**，前端隐藏开关只是体验优化（SYS-NF-04）。</p>
     */
    private static void requireDeletePermissionWhenIncludingDeleted(Boolean includeDeleted) {
        // 判定逻辑收敛到 guarantee-common（单一实现，可单测）：参数级权限无法用 @PreAuthorize 表达
        LogicalDeletePermissions.requireIncludeDeleted(includeDeleted, Permissions.ORG_DELETE, "机构");
    }

    private static Long currentUserId() {
        return CurrentUser.userId();
    }

}
