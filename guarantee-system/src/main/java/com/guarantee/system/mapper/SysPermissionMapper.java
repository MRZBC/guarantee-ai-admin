package com.guarantee.system.mapper;

import com.guarantee.system.vo.PermissionVO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 权限配置 Mapper。SQL 统一维护在 mapper/system/SysPermissionMapper.xml。
 */
@Mapper
public interface SysPermissionMapper {

    /** 全量权限，按 sort_no 升序；前端按 parentId 自行组树。 */
    List<PermissionVO> selectAllOrdered();
}
