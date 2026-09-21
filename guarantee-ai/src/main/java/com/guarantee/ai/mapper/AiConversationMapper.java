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
}
