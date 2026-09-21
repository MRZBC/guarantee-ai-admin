package com.guarantee.ai.mapper;

import com.guarantee.ai.entity.AiAuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AiAuditLogMapper {

    int insert(AiAuditLog entity);
}
