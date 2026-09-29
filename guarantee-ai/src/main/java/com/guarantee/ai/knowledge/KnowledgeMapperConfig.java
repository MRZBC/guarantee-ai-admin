package com.guarantee.ai.knowledge;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 知识模块的 Mapper 扫描配置。
 *
 * <p><b>为什么需要它</b>：启动类 {@code GuaranteeAiAdminApplication} 的
 * {@code @MapperScan} 只覆盖 {@code com.guarantee.system/order/analysis/ai.mapper}
 * 四个包，本阶段的 Mapper 位于 {@code com.guarantee.ai.knowledge.mapper}，
 * 不在其中。启动类是多个阶段共用的热点文件，本阶段不修改它，改为在本模块内
 * 自包含地声明扫描范围——这也让"知识模块整体可摘除"。</p>
 *
 * <p>{@code annotationClass = Mapper.class} 与既有写法一致：只扫显式标注
 * {@code @Mapper} 的接口，避免把同包下的普通接口误注册成 Mapper。</p>
 */
@Configuration
@MapperScan(basePackages = "com.guarantee.ai.knowledge.mapper", annotationClass = Mapper.class)
public class KnowledgeMapperConfig {
}
