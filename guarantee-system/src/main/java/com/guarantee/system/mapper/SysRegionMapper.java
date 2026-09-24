package com.guarantee.system.mapper;

import com.guarantee.system.entity.SysRegion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 行政区划字典 Mapper。SQL 统一维护在 mapper/system/SysRegionMapper.xml。
 *
 * <p>表已在 {@code LogicalDeleteTables.MANAGED} 清单里：没有显式写 {@code is_deleted} 的语句
 * 会被拦截器自动注入 {@code sys_region.is_deleted = 0}，因此"已删除的地区"默认读不到。</p>
 */
@Mapper
public interface SysRegionMapper {

    /** 按层级/上级取地区；两个条件都是可选的（都不传 = 全量）。 */
    List<SysRegion> selectByLevel(@Param("level") Integer level,
                                  @Param("parentCode") String parentCode);

    /** 按区划码精确取（已逻辑删除的读不到）。 */
    SysRegion selectByCode(@Param("code") String code);

    /** 按全称或简称取：用于把"浙江省"这类输入规范化成 330000。 */
    SysRegion selectByNameOrShortName(@Param("name") String name);

    /**
     * 业务数据里出现过的区划码（去重）。
     *
     * <p>用于"筛选下拉只列能筛出数据的地区"。刻意写成顶层 UNION：拦截器不支持顶层 UNION
     * 的自动注入，因此每个分支都显式写了 {@code is_deleted = 0}（显式出现即整句跳过）。</p>
     */
    List<String> selectUsedRegionCodes();
}
