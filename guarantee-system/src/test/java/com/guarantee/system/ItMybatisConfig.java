package com.guarantee.system;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 集成测试用的最小 Spring 装配。
 *
 * <p>只启用数据源 / JdbcTemplate / SQL 初始化 / MyBatis，不引入 Redis 与 Spring AI，
 * 使 guarantee-system 的数据范围与写操作测试可以独立运行（{@code mvn verify}）。</p>
 *
 * <p>显式扫描 {@code com.guarantee.system}（Service）与 {@code com.guarantee.common}
 * （异常处理与安全常量），不扫描 web / ai 模块，避免无关依赖。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@ComponentScan(basePackages = {"com.guarantee.system", "com.guarantee.common"})
@MapperScan(basePackages = "com.guarantee.system.mapper", annotationClass = Mapper.class)
public class ItMybatisConfig {
}
