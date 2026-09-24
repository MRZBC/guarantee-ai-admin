package com.guarantee.system.mapper;

import com.guarantee.system.dto.OrgDto;
import com.guarantee.system.entity.SysOrg;
import com.guarantee.system.scope.DataScope;
import com.guarantee.system.vo.OrgOptionVO;
import com.guarantee.system.vo.OrgVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 机构配置 Mapper。SQL 统一维护在 mapper/system/SysOrgMapper.xml。
 *
 * <p>所有列表/明细查询都通过 {@code q.scope} 强制注入机构范围过滤（SYS-P-08）；
 * 写操作的目标解析走 {@link #selectCandidates}，同样带范围过滤（SYS-P-14）。</p>
 */
@Mapper
public interface SysOrgMapper {

    // ---------------- 读 ----------------

    List<OrgVO> selectPage(@Param("q") OrgDto.Query query,
                           @Param("offset") int offset,
                           @Param("limit") int limit);

    long countByQuery(@Param("q") OrgDto.Query query);

    /** 树形数据源：数据范围内的全量机构（SYS-C-21）。 */
    List<OrgVO> selectTree(@Param("q") OrgDto.Query query, @Param("limit") int limit);

    OrgVO selectVoById(@Param("id") Long id);

    /**
     * 订单筛选下拉的可选机构（"能筛出数据"口径）：启用中且未删除的机构，
     * **或**被订单引用的机构（不论已停用、已逻辑删除）。
     *
     * <p>机构停用不拦"名下有订单"，因此"停用机构 + 历史订单"是常规可达状态，
     * 只按 {@code status = 1} 过滤会让它在筛选里消失；"已删除 + 被引用"则由
     * 删除守卫拦在门外，只可能来自数据库直连删除（{@code deleted_by = 'DB'}）。</p>
     *
     * <p>原先的"仅启用"下拉语句已随本口径删除：同一张表留两份近似的选项查询，
     * 下一次改口径必然只改一处。</p>
     */
    List<OrgOptionVO> selectFilterOptions();

    /** 供其它模块使用：全部启用机构实体。 */
    List<SysOrg> selectAllEnabled();

    /** 供其它模块按主键读取机构实体。 */
    SysOrg selectEntityById(@Param("id") Long id);

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
    SysOrg selectEntityByIdIncludingDeleted(@Param("id") Long id);

    SysOrg selectEntityByCode(@Param("orgCode") String orgCode);

    /**
     * 数据范围解析：自身 + 全部下级机构 id（递归 CTE）。
     */
    List<Long> selectVisibleOrgIds(@Param("orgId") Long orgId);

    /** 写操作目标解析：按名称/编码模糊匹配，带范围过滤（SYS-W-10）。 */
    List<SysOrg> selectCandidates(@Param("keyword") String keyword,
                                  @Param("scope") DataScope scope,
                                  @Param("limit") int limit);

    // ---------------- 写 ----------------

    int insert(SysOrg entity);

    int updateById(SysOrg entity);

    /** 条件更新：只有当前状态与预期一致时才改（T-08）。 */
    int updateStatus(@Param("id") Long id,
                     @Param("status") Integer status,
                     @Param("expectedStatus") Integer expectedStatus);

    // ---------------- 校验用统计 ----------------

    long countChildren(@Param("id") Long id);

    /** 启用中的下级机构数（递归，含多级）。 */
    long countEnabledDescendants(@Param("id") Long id);

    /** 机构下的订单数（订单仍带机构维度）。 */
    long countOrderByOrg(@Param("orgId") Long orgId);
}
