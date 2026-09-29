package com.guarantee.ai.service;

import com.guarantee.ai.config.PromptVersionService;
import com.guarantee.ai.time.TimeRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 业务助手 System Prompt 提供者（REQ-CFG-02）。
 *
 * <p><b>读取优先级</b>：</p>
 * <ol>
 *   <li>{@code ai_prompt_version} 里当前的 PUBLISHED 版本（页面发布的版本，改完下一个请求生效）；</li>
 *   <li><b>回落</b> classpath 内置的 {@code prompts/business-assistant.st}：DB 里没有发布版、
 *       表不存在、或读库失败（DB 抖动）时都必须可用——这是"冷启动必须能对话"的保证。</li>
 * </ol>
 *
 * <p>本类只负责"取正文 + 追加每轮动态上下文（当前日期、已解析的时间范围）"；
 * 版本机（草稿 / 发布 / 回滚 / 门禁）在 {@link PromptVersionService}。</p>
 */
@Component
public class BusinessAssistantPrompt {

    private static final Logger log = LoggerFactory.getLogger(BusinessAssistantPrompt.class);

    private final Resource promptResource;
    private final PromptVersionService promptVersionService;

    public BusinessAssistantPrompt(
            @Value("classpath:prompts/business-assistant.st") Resource promptResource,
            PromptVersionService promptVersionService) {
        this.promptResource = promptResource;
        this.promptVersionService = promptVersionService;
    }

    /**
     * 读取 Prompt 正文：优先 DB 发布版，缺失时回落 classpath。
     *
     * <p>回落不是"异常路径的补丁"，而是**正常语义**：首次部署、DB 迁移前的存量环境、
     * 以及"旧版本已归档、新版本尚未发布"的窗口期，都必须能正常对话。</p>
     */
    public String loadTemplate() {
        String published = promptVersionService.currentContent();
        if (published != null && !published.isBlank()) {
            return published;
        }
        log.debug("没有可用的 DB 发布版提示词，回落到 classpath 内置提示词");
        return readClasspathTemplate();
    }

    /**
     * 本轮实际生效的提示词版本号（REQ-CFG-05 回答级回溯）。
     *
     * <p>返回 {@code null} 表示本轮用的是 classpath 内置提示词（DB 无发布版 / 读库失败）——
     * 那同样是一个可回溯的事实，不能拿上一轮的值来填。</p>
     */
    public Integer activeVersionNo() {
        try {
            int version = promptVersionService.activeVersionNo();
            return version <= 0 ? null : version;
        } catch (RuntimeException ex) {
            log.warn("读取提示词生效版本失败，本轮按『无版本（classpath 内置）』记录：{}", ex.getMessage());
            return null;
        }
    }

    private String readClasspathTemplate() {
        try (var in = promptResource.getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("读取 system prompt 失败", ex);
        }
    }

    /**
     * 组装最终 System Prompt。
     *
     * @param parsedTime 已由 {@code TimeSemanticParser} 解析出的时间范围（可能为空）
     */
    public String build(Optional<TimeRange> parsedTime) {
        StringBuilder sb = new StringBuilder(loadTemplate());
        sb.append("\n\n# 本轮运行上下文\n");
        sb.append("- 当前系统日期：").append(LocalDate.now()).append("\n");
        parsedTime.ifPresent(range -> sb.append("- 系统已预解析本轮时间范围：")
                .append(range.startDate()).append(" ~ ").append(range.endDate())
                .append("（").append(range.description()).append("）\n")
                .append("  调用工具时请直接使用上述明确日期。\n"));
        if (parsedTime.isEmpty()) {
            sb.append("- 本轮未识别到明确时间表达；若问题涉及时间，请先调用 getCurrentDate 确认基准日期。\n");
        }
        return sb.toString();
    }
}
