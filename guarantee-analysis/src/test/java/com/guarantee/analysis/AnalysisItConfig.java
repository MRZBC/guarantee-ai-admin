package com.guarantee.analysis;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 集成测试用的最小 Spring 装配。
 *
 * <p>只启用数据源 / MyBatis，不引入 Redis 与 Spring AI。扫描范围刻意收窄：</p>
 * <ul>
 *   <li>{@code com.guarantee.analysis}：被测的 Service / Mapper；</li>
 *   <li>{@code com.guarantee.system.mybatis}：只要逻辑删除查询拦截器
 *       （{@code LogicalDeleteInnerInterceptor}），让 IT 跑到的是生产那条 SQL 改写路径。
 *       <b>不整包扫描 {@code com.guarantee.system}</b>：那会把 system 的 Controller/Service
 *       一起拉进来，而它们的 Mapper 不在本模块的扫描范围，上下文直接起不来；</li>
 *   <li>{@code com.guarantee.common}：全局异常处理与安全常量。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@ComponentScan(basePackages = {"com.guarantee.analysis", "com.guarantee.system.mybatis", "com.guarantee.common"})
@MapperScan(basePackages = "com.guarantee.analysis.mapper", annotationClass = Mapper.class)
public class AnalysisItConfig {
}
