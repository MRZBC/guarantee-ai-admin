package com.guarantee.order.mapper;

import com.guarantee.order.dto.PerformanceOrderQuery;
import com.guarantee.order.vo.PerformanceOrderVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 履约订单 Mapper。SQL 统一维护在 mapper/order/PerformanceOrderMapper.xml。
 */
@Mapper
public interface PerformanceOrderMapper {

    /** 分页查询，关联项目/企业/险种/机构取名称。 */
    List<PerformanceOrderVO> selectPage(@Param("q") PerformanceOrderQuery query,
                                        @Param("offset") int offset,
                                        @Param("limit") int limit);

    /** 与 selectPage 共用 queryWhere 片段，保证总数与列表同口径。 */
    long countByQuery(@Param("q") PerformanceOrderQuery query);

    /** 订单详情（含关联名称），不存在返回 null。 */
    PerformanceOrderVO selectDetailById(@Param("id") Long id);
}
