package com.guarantee.order.mapper;

import com.guarantee.order.dto.TenderOrderQuery;
import com.guarantee.order.vo.TenderOrderVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 投标订单 Mapper。SQL 统一维护在 mapper/order/TenderOrderMapper.xml。
 */
@Mapper
public interface TenderOrderMapper {

    /** 分页查询，关联项目/企业/险种/机构取名称。 */
    List<TenderOrderVO> selectPage(@Param("q") TenderOrderQuery query,
                                   @Param("offset") int offset,
                                   @Param("limit") int limit);

    /** 与 selectPage 共用 queryWhere 片段，保证总数与列表同口径。 */
    long countByQuery(@Param("q") TenderOrderQuery query);

    /** 订单详情（含关联名称），不存在返回 null。 */
    TenderOrderVO selectDetailById(@Param("id") Long id);
}
