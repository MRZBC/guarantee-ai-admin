package com.guarantee.system.mapper;

import com.guarantee.system.dto.DepartmentDto;
import com.guarantee.system.entity.SysDepartment;
import com.guarantee.system.scope.DataScope;
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

    // ---------------- 读 ----------------

    List<DepartmentVO> selectPage(@Param("q") DepartmentDto.Query query,
                                  @Param("offset") int offset,
                                  @Param("limit") int limit);

    long countByQuery(@Param("q") DepartmentDto.Query query);

    /**
     * 树形数据源（SYS-C-21 / SYS-C-24 口径）。
     *
     * <p>与 {@link #selectPage} 共用同一 {@code queryWhere} 片段，只是去掉分页、按
     * {@code sort_no + id} 稳定排序，保证"树上看到的 = 列表能查到的"。
     * 返回**扁平**列表，嵌套结构由前端组装（与 {@code GET /api/system/orgs/tree} 一致）。</p>
     *
     * @param limit 条数上限，触顶时调用方负责告警
     */
    List<DepartmentVO> selectTree(@Param("q") DepartmentDto.Query query, @Param("limit") int limit);

    /** 明细（带数据范围）：SYS-P-14 要求范围覆盖列表与明细两类查询。 */
    DepartmentVO selectVoScoped(@Param("q") DepartmentDto.Query query);

    SysDepartment selectEntityById(@Param("id") Long id);

    // ---------------- 逻辑删除（LD-02 / LD-04） ----------------

    /**
     * 逻辑删除：一条语句写全 is_deleted / deleted_at / deleted_by（设计 §9.4.1）。
     *
     * @param operatorId 应用侧传 sys_user.id 的字符串形式；直连/未知时传 null，落默认 'DB'
     * @return 受影响行数：0 表示该行不存在或已被删除（并发冲突）
     */
    int softDelete(@Param("id") Long id, @Param("operatorId") String operatorId);

    /** 恢复：is_deleted = 0 且 deleted_at = NULL；只允许从"已删除"恢复。 */
    int restore(@Param("id") Long id);

    /** 恢复前的读取：包含已删除行（方法名后缀触发拦截器豁免）。 */
    SysDepartment selectEntityByIdIncludingDeleted(@Param("id") Long id);

    /** 编码唯一性检查（全局唯一键，跨部门判定）。 */
    SysDepartment selectEntityByCode(@Param("deptCode") String deptCode);

    /** 写操作目标解析：按名称/编码模糊匹配（SYS-W-10）；阶段一 O3 起不再按机构过滤。 */
    List<SysDepartment> selectCandidates(@Param("keyword") String keyword,
                                         @Param("scope") DataScope scope,
                                         @Param("limit") int limit);

    /** 供下拉框使用：仅启用部门（阶段一 O3 起无机构维度）。 */
    List<DepartmentOptionVO> selectEnabledOptions();

    // ---------------- 写 ----------------

    int insert(SysDepartment entity);

    int updateById(SysDepartment entity);

    int updateStatus(@Param("id") Long id,
                     @Param("status") Integer status,
                     @Param("expectedStatus") Integer expectedStatus);

    // ---------------- 校验用统计 ----------------

    long countEnabledUserByDept(@Param("deptId") Long deptId);

    /** 删除前置检查：部门下未删除的用户数（含停用用户）。 */
    long countUserByDept(@Param("deptId") Long deptId);

    /** 删除前置检查（实施期补充）：未删除的下级部门数。 */
    long countChildDept(@Param("deptId") Long deptId);

    /**
     * 给定部门，返回**它自身 + 全部下级部门** id（用于"上级不能挂到自己的下级之下"的防环校验）。
     *
     * <p>递归 CTE 显式限制 {@code depth < 10}，避免脏 {@code parent_id} 已形成环时无限递归，
     * 与 {@code SysOrgMapper.selectVisibleOrgIds} 同款保护。</p>
     */
    List<Long> selectSelfAndDescendantIds(@Param("deptId") Long deptId);
}
