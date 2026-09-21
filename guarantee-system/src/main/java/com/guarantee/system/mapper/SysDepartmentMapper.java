package com.guarantee.system.mapper;

import com.guarantee.system.dto.DepartmentDto;
import com.guarantee.system.vo.DepartmentOptionVO;
import com.guarantee.system.vo.DepartmentVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 部门配置 Mapper。SQL 统一维护在 mapper/system/SysDepartmentMapper.xml。
 */
@Mapper
public interface SysDepartmentMapper {

    List<DepartmentVO> selectPage(@Param("q") DepartmentDto.Query query,
                                  @Param("offset") int offset,
                                  @Param("limit") int limit);

    long countByQuery(@Param("q") DepartmentDto.Query query);

    /** 供下拉框使用：仅启用部门，orgId 为空时返回全部。 */
    List<DepartmentOptionVO> selectEnabledOptions(@Param("orgId") Long orgId);
}
