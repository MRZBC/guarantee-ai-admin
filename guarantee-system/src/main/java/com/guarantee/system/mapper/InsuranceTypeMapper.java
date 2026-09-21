package com.guarantee.system.mapper;

import com.guarantee.system.dto.InsuranceTypeDto;
import com.guarantee.system.entity.InsuranceType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 险种配置 Mapper。SQL 统一维护在 mapper/system/InsuranceTypeMapper.xml。
 */
@Mapper
public interface InsuranceTypeMapper {

    List<InsuranceType> selectPage(@Param("q") InsuranceTypeDto.Query query,
                                   @Param("offset") int offset,
                                   @Param("limit") int limit);

    long countByQuery(@Param("q") InsuranceTypeDto.Query query);

    InsuranceType selectById(@Param("id") Long id);

    InsuranceType selectByCode(@Param("typeCode") String typeCode);

    /** 供 AI Tool 与下拉框使用：全部启用险种。 */
    List<InsuranceType> selectAllEnabled();

    int insert(InsuranceType entity);

    int updateById(InsuranceType entity);
}
