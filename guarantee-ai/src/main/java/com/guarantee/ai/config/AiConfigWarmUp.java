package com.guarantee.ai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * AI 配置的**启动预热**（T4-01 接线，REQ-CFG-07 / AC-CFG-07）。
 *
 * <p>为什么要在启动期显式跑一次 {@link AiConfigService#warmUp()}：</p>
 * <ol>
 *   <li><b>把"库中非法值"的告警提前到启动期</b>——配置写坏（例如手工 UPDATE 成一个超范围的值）
 *       如果不预热，就要等第一次问答才发现；预热会把每条非法键打成一条 ERROR，运维在启动日志里
 *       就能看到，而不是等业务反馈"助手答得不对"。</li>
 *   <li><b>冷启动第一次请求不必等一次 DB 往返</b>（虽然只有 &lt;1ms，但预热让"首问延迟"更稳）。</li>
 * </ol>
 *
 * <p><b>失败绝不影响启动</b>：{@code warmUp()} 内部已经降级（DB 不可用 → 沿用上一份快照/内置默认值
 * + ERROR 日志）；这里再包一层 try/catch，保证任何意外（例如表还没建）都只是日志里的一条 ERROR，
 * 而不是应用起不来（AC-CFG-07 的"助手仍可用"）。</p>
 *
 * <p><b>为什么单独一个类而不是给 {@code AiConfigService} 挂 {@code @EventListener}</b>：
 * 配置服务是"读/写原语"，启动时序属于装配问题；分开之后，服务在单测里可以直接 new 出来，
 * 不必为了测试一个监听器而搭 Spring 上下文。T4-00 交付底座时也刻意没挂（当时表还没进
 * schema.sql，挂上会让每个 IT 启动都打一条"读配置失败"的假 ERROR），接线留给 T4-01。</p>
 */
@Component
public class AiConfigWarmUp {

    private static final Logger log = LoggerFactory.getLogger(AiConfigWarmUp.class);

    private final AiConfigService configService;

    public AiConfigWarmUp(AiConfigService configService) {
        this.configService = configService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUpOnStartup() {
        try {
            configService.warmUp();
        } catch (RuntimeException ex) {
            log.error("AI 配置预热失败：助手将使用内置默认值/上一份快照继续运行（不因此停止启动）。"
                    + "若为升级后的首次部署，请确认已执行 db/migration/V8__ai_config.sql"
                    + "（或由 schema.sql 自举建表）。", ex);
        }
    }
}
