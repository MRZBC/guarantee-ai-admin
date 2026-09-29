package com.guarantee.ai.mcp;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 模块的 Mapper 扫描配置。
 *
 * <p>启动类的 {@code @MapperScan} 不覆盖 {@code com.guarantee.ai.mcp.mapper}，
 * 而本切片不修改启动类（多阶段共用热点文件），因此在本模块内自包含声明
 * （与 {@code KnowledgeMapperConfig} / {@code MetricsMapperConfig} 同款做法），
 * 同时让"MCP 模块整体可摘除"。</p>
 */
@Configuration
@MapperScan(basePackages = "com.guarantee.ai.mcp.mapper", annotationClass = Mapper.class)
public class McpMapperConfig {
}
