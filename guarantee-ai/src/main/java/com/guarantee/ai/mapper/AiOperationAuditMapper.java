package com.guarantee.ai.mapper;

import com.guarantee.ai.dto.OperationAuditQuery;
import com.guarantee.ai.entity.AiOperationAudit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 操作审计 Mapper（{@code ai_operation_audit}）。
 *
 * <p><b>只增不改不删</b>（SYS-A-04）：本接口只有 {@code insert} 与只读查询，
 * 不提供任何修改/删除方法——审计表的完整性不能依赖代码评审的自律。</p>
 *
 * <p><b>时间条件是强制的</b>（SYS-A-17）：查询 SQL 一律带 {@code operated_at} 区间，
 * 使其能命中少量分区；不传时间条件的全量扫描在 Service 层就被拒绝。</p>
 */
@Mapper
public interface AiOperationAuditMapper {

    int insert(AiOperationAudit entity);

    long countByQuery(@Param("q") OperationAuditQuery query);

    List<AiOperationAudit> selectPage(@Param("q") OperationAuditQuery query,
                                      @Param("offset") int offset,
                                      @Param("limit") int limit);

    /**
     * 容量可观测（SYS-A-18）：在线行数与最老记录时间。
     *
     * <p>把"在线窗口实际跨度"纳入巡检，否则滚动归档会静默退化为永久在线（RK-14）。</p>
     */
    Long countAll();

    LocalDateTime selectOldestOperatedAt();

    /** 归档：删除早于指定时间的记录（按月分区表应优先用 DROP PARTITION，此处为兜底）。 */
    int deleteBefore(@Param("before") LocalDateTime before);
}
