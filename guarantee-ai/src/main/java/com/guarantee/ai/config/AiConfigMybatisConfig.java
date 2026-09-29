package com.guarantee.ai.config;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 配置域 Mapper 扫描。
 *
 * <p>启动类（{@code GuaranteeAiAdminApplication}）的 {@code @MapperScan} 只覆盖
 * {@code com.guarantee.ai.mapper} 等既有包，不含本包下的 {@code com.guarantee.ai.config.mapper}。
 * 为不改动共享的启动类，这里就地声明扫描；这也是 T4-00 把 mapper 放在 config 子包的原因。</p>
 */
@Configuration
@MapperScan(value = "com.guarantee.ai.config.mapper", annotationClass = Mapper.class)
public class AiConfigMybatisConfig {
}
