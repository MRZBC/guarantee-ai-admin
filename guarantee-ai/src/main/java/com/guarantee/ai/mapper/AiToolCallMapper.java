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
}
