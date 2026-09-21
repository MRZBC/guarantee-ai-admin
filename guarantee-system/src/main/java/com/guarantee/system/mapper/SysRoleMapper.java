package com.guarantee.system.mapper;

import com.guarantee.system.dto.RoleDto;
import com.guarantee.system.dto.RolePermissionRef;
import com.guarantee.system.vo.RoleVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 角色配置 Mapper。SQL 统一维护在 mapper/system/SysRoleMapper.xml。
 */
@Mapper
public interface SysRoleMapper {

    List<RoleVO> selectPage(@Param("q") RoleDto.Query query,
                            @Param("offset") int offset,
                            @Param("limit") int limit);

    long countByQuery(@Param("q") RoleDto.Query query);

    RoleVO selectVoById(@Param("id") Long id);

    /** 批量回填角色权限，避免 N+1。 */
    List<RolePermissionRef> selectPermissionRefsByRoleIds(@Param("roleIds") List<Long> roleIds);
}
