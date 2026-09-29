package com.guarantee.ai.metrics;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 观测模块的 Mapper 扫描配置。
 *
 * <p><b>为什么需要它</b>：启动类的 {@code @MapperScan} 只覆盖
 * {@code com.guarantee.system/order/analysis/ai.mapper} 四个包，本模块的 Mapper 位于
 * {@code com.guarantee.ai.metrics.mapper}，不在其中。启动类是多个阶段共用的热点文件，
 * 本切片不修改它，改为在模块内自包含地声明扫描范围（与 {@code KnowledgeMapperConfig} 同款做法），
 * 这也让"观测模块整体可摘除"。</p>
 *
 * <p>{@code annotationClass = Mapper.class} 与既有写法一致：只扫显式标注 {@code @Mapper}
 * 的接口，避免把同包下的普通接口误注册成 Mapper。</p>
 */
@Configuration
@MapperScan(basePackages = "com.guarantee.ai.metrics.mapper", annotationClass = Mapper.class)
public class MetricsMapperConfig {
}
