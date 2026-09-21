package com.guarantee.ai.service;

import com.guarantee.ai.time.TimeRange;
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
 * 业务助手 System Prompt 提供者。
 *
 * <p>Prompt 正文维护在 {@code classpath:prompts/business-assistant.st}，
 * 这里只负责加载并追加每轮动态上下文（当前日期、已解析的时间范围）。</p>
 */
@Component
public class BusinessAssistantPrompt {

    private final Resource promptResource;

    public BusinessAssistantPrompt(
            @Value("classpath:prompts/business-assistant.st") Resource promptResource) {
        this.promptResource = promptResource;
    }

    /** 读取 Prompt 正文。 */
    public String loadTemplate() {
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
