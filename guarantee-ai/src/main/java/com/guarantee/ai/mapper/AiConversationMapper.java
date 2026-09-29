package com.guarantee.ai.mapper;

import com.guarantee.ai.entity.AiConversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiConversationMapper {

    int insert(AiConversation entity);

    AiConversation selectById(@Param("id") Long id);

    List<AiConversation> selectByUserId(@Param("userId") Long userId, @Param("limit") int limit);

    int updateTitle(@Param("id") Long id, @Param("title") String title);

    int incrementMessageCount(@Param("id") Long id, @Param("delta") int delta);

    /**
     * 回答级回溯（REQ-CFG-05 / AC-CFG-09）：记录本会话最近一轮回答所用的
     * 提示词版本号与配置快照版本号；两者都允许为 null（未使用 DB 版本时）。
     *
     * <p>写入方：{@code configVersion} 由 T4-01 在 {@code AiChatService} 收尾时写，
     * {@code promptVersion} 由 T4-03 写。</p>
     */
    int updateVersionTrace(@Param("id") Long id,
                           @Param("promptVersion") Integer promptVersion,
                           @Param("configVersion") Long configVersion);
}
