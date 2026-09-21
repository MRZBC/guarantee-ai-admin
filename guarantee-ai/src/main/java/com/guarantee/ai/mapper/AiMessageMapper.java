package com.guarantee.ai.mapper;

import com.guarantee.ai.entity.AiMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiMessageMapper {

    int insert(AiMessage entity);

    List<AiMessage> selectByConversationId(@Param("conversationId") Long conversationId);

    /**
     * 取最近 N 条消息（按 id 倒序取，再由 Service 反转成正序）用于构造多轮上下文。
     */
    List<AiMessage> selectRecentByConversationId(@Param("conversationId") Long conversationId,
                                                 @Param("limit") int limit);
}
