package com.guarantee.system.service;

import com.guarantee.common.api.PageResult;
import com.guarantee.common.exception.BizException;
import com.guarantee.system.dto.UserDto;
import com.guarantee.system.dto.UserRoleRef;
import com.guarantee.system.entity.SysUser;
import com.guarantee.system.mapper.SysUserMapper;
import com.guarantee.system.vo.UserVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户配置服务。
 *
 * <p>安全约束：所有对外返回的 VO 都不含 password；
 * 只有 {@link #getEntityByUsername(String)}（登录专用）返回带密码散列的实体。</p>
 */
@Service
public class UserService {

    private final SysUserMapper sysUserMapper;

    public UserService(SysUserMapper sysUserMapper) {
        this.sysUserMapper = sysUserMapper;
    }

    @Transactional(readOnly = true)
    public PageResult<UserVO> page(UserDto.Query query) {
        long total = sysUserMapper.countByQuery(query);
        if (total == 0) {
            return PageResult.empty(query.getPageNum(), query.getPageSize());
        }
        List<UserVO> list = sysUserMapper.selectPage(query, query.offset(), query.getPageSize());
        fillRoles(list);
        return PageResult.of(query.getPageNum(), query.getPageSize(), total, list);
    }

    @Transactional(readOnly = true)
    public UserVO getById(Long id) {
        UserVO vo = sysUserMapper.selectVoById(id);
        if (vo == null) {
            throw BizException.notFound("用户不存在: " + id);
        }
        fillRoles(List.of(vo));
        return vo;
    }

    /**
     * 登录专用：按账号读取用户实体（包含 password 散列）。
     * 用户不存在返回 {@code null}，由认证层决定如何响应。
     */
    @Transactional(readOnly = true)
    public SysUser getEntityByUsername(String username) {
        return sysUserMapper.selectByUsername(username);
    }

    /** 当前用户的启用角色编码，供认证与鉴权使用。 */
    @Transactional(readOnly = true)
    public List<String> listRoleCodesByUserId(Long userId) {
        return sysUserMapper.listRoleCodesByUserId(userId);
    }

    /** 当前用户的启用权限编码（多角色去重），供认证与鉴权使用。 */
    @Transactional(readOnly = true)
    public List<String> listPermissionCodesByUserId(Long userId) {
        return sysUserMapper.listPermissionCodesByUserId(userId);
    }

    /** 登录成功后更新最近登录时间。 */
    @Transactional
    public void updateLastLoginAt(Long userId) {
        sysUserMapper.updateLastLoginAt(userId);
    }

    /** 批量回填角色，避免逐条查询。 */
    private void fillRoles(List<UserVO> users) {
        if (users.isEmpty()) {
            return;
        }
        List<Long> userIds = users.stream().map(UserVO::getId).toList();
        Map<Long, List<UserRoleRef>> grouped = sysUserMapper.selectRoleRefsByUserIds(userIds).stream()
                .collect(Collectors.groupingBy(UserRoleRef::getUserId));
        for (UserVO user : users) {
            List<UserRoleRef> refs = grouped.getOrDefault(user.getId(), List.of());
            user.setRoleIds(refs.stream().map(UserRoleRef::getRoleId).toList());
            user.setRoleNames(refs.stream().map(UserRoleRef::getRoleName).toList());
        }
    }
}
