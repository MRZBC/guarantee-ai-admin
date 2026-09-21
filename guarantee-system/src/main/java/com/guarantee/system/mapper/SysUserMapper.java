package com.guarantee.system.mapper;

import com.guarantee.system.dto.UserDto;
import com.guarantee.system.dto.UserRoleRef;
import com.guarantee.system.entity.SysUser;
import com.guarantee.system.vo.UserVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户配置 Mapper。SQL 统一维护在 mapper/system/SysUserMapper.xml。
 *
 * <p>除登录专用的 {@link #selectByUsername(String)} 外，任何查询都不读取 password 列。</p>
 */
@Mapper
public interface SysUserMapper {

    List<UserVO> selectPage(@Param("q") UserDto.Query query,
                            @Param("offset") int offset,
                            @Param("limit") int limit);

    long countByQuery(@Param("q") UserDto.Query query);

    UserVO selectVoById(@Param("id") Long id);

    /** 仅登录链路使用：包含 password 散列。 */
    SysUser selectByUsername(@Param("username") String username);

    /** 批量回填用户角色，避免 N+1。 */
    List<UserRoleRef> selectRoleRefsByUserIds(@Param("userIds") List<Long> userIds);

    /** 当前用户的启用角色编码。 */
    List<String> listRoleCodesByUserId(@Param("userId") Long userId);

    /** 当前用户的启用权限编码（经角色去重）。 */
    List<String> listPermissionCodesByUserId(@Param("userId") Long userId);

    /** 登录成功后记录最近登录时间。 */
    int updateLastLoginAt(@Param("id") Long id);
}
