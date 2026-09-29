package com.guarantee.ai.config.mapper;

import com.guarantee.ai.config.AiConfigItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 配置项 Mapper（{@code ai_config_item}）。
 *
 * <p>只做"值的读写"：元数据以 {@code AiConfigCatalog} 为准，写库时把目录里的元数据投影到行上
 * （便于运维直接读表），读回时不依赖这些列。</p>
 */
@Mapper
public interface AiConfigItemMapper {

    /** 全部未删除的配置行。 */
    List<AiConfigItem> selectAll();

    /** 按 key 取一行（未删除）。 */
    AiConfigItem selectByKey(@Param("configKey") String configKey);

    /** 新增一行配置（用于首次写入，含元数据投影）。 */
    int insert(AiConfigItem item);

    /**
     * 更新既有行的值 + 版本号 + 元数据投影（不触碰逻辑删除列）。
     *
     * @return 影响行数；0 表示该 key 尚无对应的未删除行，调用方应改为 {@link #insert}
     */
    int updateValue(AiConfigItem item);

    /** 全表最大版本号；空表返回 null（调用方按 0 处理）。 */
    Long maxVersion();
}
