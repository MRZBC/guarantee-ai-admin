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
    InsuranceType selectByIdIncludingDeleted(@Param("id") Long id);

    InsuranceType selectByCode(@Param("typeCode") String typeCode);

    /** 供 AI Tool 与写操作使用：全部启用险种（"可用于新业务"口径）。 */
    List<InsuranceType> selectAllEnabled();

    /**
     * 订单筛选下拉的可选险种（"能筛出数据"口径）：启用中且未删除的险种，
     * **或**被订单引用的险种（不论已停用、已逻辑删除）。
     *
     * <p>与 {@link #selectAllEnabled()} 分开是刻意的：停用只表示"不能再承保新业务"，
     * 删除则受"被订单引用即拒绝"守卫保护；但历史订单仍在列表里展示该险种，
     * 下拉若按 {@code status = 1} 过滤，就会出现"列表里全是这个险种、筛选里却选不到"
     * （现场：「投标保函（标准）」名下 4.4 万条投标订单，因 {@code status = 0} 而缺席下拉）。</p>
     */
    List<InsuranceType> selectFilterOptions();

    /** 写操作目标解析：按名称/编码模糊匹配（SYS-W-10）。 */
    List<InsuranceType> selectCandidates(@Param("keyword") String keyword, @Param("limit") int limit);

    int insert(InsuranceType entity);

    int updateById(InsuranceType entity);

    int updateStatus(@Param("id") Long id,
                     @Param("status") Integer status,
                     @Param("expectedStatus") Integer expectedStatus);

    /** 停用前置检查：该险种被多少条订单引用（SYS-W-01）。 */
    long countOrderByType(@Param("id") Long id);
}
