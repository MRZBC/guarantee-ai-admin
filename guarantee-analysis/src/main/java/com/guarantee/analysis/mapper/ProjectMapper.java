package com.guarantee.analysis.mapper;

import com.guarantee.analysis.dto.ProjectQuery;
import com.guarantee.analysis.vo.ProjectDetailVO;
import com.guarantee.analysis.vo.ProjectVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 项目管理 Mapper。
 *
 * <p>分页使用 count + page 两条 SQL，共用 {@code <sql id="queryWhere">} 动态条件片段，
 * 保证总数与列表口径一致。SQL 统一维护在 mapper/analysis/ProjectMapper.xml。</p>
 */
@Mapper
public interface ProjectMapper {

    /** 项目分页列表（join enterprise 取企业名）。 */
    List<ProjectVO> selectPage(@Param("q") ProjectQuery query,
                               @Param("offset") int offset,
                               @Param("limit") int limit);

    /** 与 selectPage 同条件的总数。 */
    long countByQuery(@Param("q") ProjectQuery query);

    /** 项目详情基础信息。 */
    ProjectDetailVO selectDetailById(@Param("id") Long id);

    /** 项目详情订单指标：投标/履约 UNION ALL 后按 project_id 聚合。 */
    ProjectDetailVO selectOrderMetricsByProjectId(@Param("id") Long id);
}
