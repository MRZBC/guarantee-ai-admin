package com.guarantee.system.mapper;

import com.guarantee.system.dto.OrgDto;
import com.guarantee.system.entity.SysOrg;
import com.guarantee.system.vo.OrgOptionVO;
import com.guarantee.system.vo.OrgVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 机构配置 Mapper。SQL 统一维护在 mapper/system/SysOrgMapper.xml。
 */
@Mapper
public interface SysOrgMapper {

    List<OrgVO> selectPage(@Param("q") OrgDto.Query query,
                           @Param("offset") int offset,
                           @Param("limit") int limit);

    long countByQuery(@Param("q") OrgDto.Query query);

    OrgVO selectVoById(@Param("id") Long id);

    /** 供下拉框使用：仅启用机构。 */
    List<OrgOptionVO> selectEnabledOptions();

    /** 供其它模块使用：全部启用机构实体。 */
    List<SysOrg> selectAllEnabled();

    /** 供其它模块按主键读取机构实体。 */
    SysOrg selectEntityById(@Param("id") Long id);
}
