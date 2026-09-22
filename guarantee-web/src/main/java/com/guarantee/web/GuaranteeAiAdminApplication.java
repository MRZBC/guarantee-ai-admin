package com.guarantee.web;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 智能电子保函运营管理平台启动类。
 *
 * <p>模块化单体：单个 Spring 上下文聚合全部业务模块，
 * 模块之间通过 Service 依赖，不引入微服务。</p>
 *
 * <p>{@link EnableScheduling} 用于提案过期清理与操作审计容量巡检
 * （SYS-C-09 / T-11 / SYS-A-18）。</p>
 */
@SpringBootApplication(scanBasePackages = "com.guarantee")
@EnableScheduling
@MapperScan(basePackages = {
        "com.guarantee.system.mapper",
        "com.guarantee.order.mapper",
        "com.guarantee.analysis.mapper",
        "com.guarantee.ai.mapper"
}, annotationClass = Mapper.class)
public class GuaranteeAiAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(GuaranteeAiAdminApplication.class, args);
    }
}
