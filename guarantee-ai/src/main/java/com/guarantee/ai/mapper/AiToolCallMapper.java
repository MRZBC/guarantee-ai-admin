package com.guarantee.ai.mapper;

import com.guarantee.ai.entity.AiToolCall;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiToolCallMapper {

    int insert(AiToolCall entity);

    List<AiToolCall> selectByConversationId(@Param("conversationId") Long conversationId);

    /** 流式结束、助手消息落库后，把本轮 Tool Call 关联到该消息。 */
    int bindMessageId(@Param("conversationId") Long conversationId,
                      @Param("messageId") Long messageId);

    /**
     * 当前用户自己的工具调用记录（SYS-Q-06a）。
     *
     * <p>范围由 SQL 强制收敛到 {@code userId}，**不接受任何用户维度入参**——
     * 可选参数意味着模型可以传别人的 id（TEST-17）。</p>
     */
    long countMine(@Param("userId") Long userId,
                   @Param("startDate") java.time.LocalDateTime startDate,
                   @Param("endDate") java.time.LocalDateTime endDate,
                   @Param("toolName") String toolName,
                   @Param("status") String status);

    List<AiToolCall> selectMine(@Param("userId") Long userId,
                                @Param("startDate") java.time.LocalDateTime startDate,
                                @Param("endDate") java.time.LocalDateTime endDate,
                                @Param("toolName") String toolName,
                                @Param("status") String status,
                                @Param("limit") int limit);
}
