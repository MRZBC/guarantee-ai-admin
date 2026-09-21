package com.guarantee.system.service;

import com.guarantee.common.api.PageResult;
import com.guarantee.common.exception.BizException;
import com.guarantee.system.dto.RoleDto;
import com.guarantee.system.dto.RolePermissionRef;
import com.guarantee.system.mapper.SysRoleMapper;
import com.guarantee.system.vo.RoleVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 角色配置服务。权限列表由 Service 批量回填，避免 N+1。
 */
@Service
public class RoleService {

    private final SysRoleMapper sysRoleMapper;

    public RoleService(SysRoleMapper sysRoleMapper) {
        this.sysRoleMapper = sysRoleMapper;
    }

    @Transactional(readOnly = true)
    public PageResult<RoleVO> page(RoleDto.Query query) {
        long total = sysRoleMapper.countByQuery(query);
        if (total == 0) {
            return PageResult.empty(query.getPageNum(), query.getPageSize());
        }
        List<RoleVO> list = sysRoleMapper.selectPage(query, query.offset(), query.getPageSize());
        fillPermissions(list);
        return PageResult.of(query.getPageNum(), query.getPageSize(), total, list);
    }

    @Transactional(readOnly = true)
    public RoleVO getById(Long id) {
        RoleVO vo = sysRoleMapper.selectVoById(id);
        if (vo == null) {
            throw BizException.notFound("角色不存在: " + id);
        }
        fillPermissions(List.of(vo));
        return vo;
    }

    /** 批量回填角色已授权权限。 */
    private void fillPermissions(List<RoleVO> roles) {
        if (roles.isEmpty()) {
            return;
        }
        List<Long> roleIds = roles.stream().map(RoleVO::getId).toList();
        Map<Long, List<RolePermissionRef>> grouped =
                sysRoleMapper.selectPermissionRefsByRoleIds(roleIds).stream()
                        .collect(Collectors.groupingBy(RolePermissionRef::getRoleId));
        for (RoleVO role : roles) {
            List<RolePermissionRef> refs = grouped.getOrDefault(role.getId(), List.of());
            role.setPermissionIds(refs.stream().map(RolePermissionRef::getPermissionId).toList());
            role.setPermissionNames(refs.stream().map(RolePermissionRef::getPermissionName).toList());
        }
    }
}
