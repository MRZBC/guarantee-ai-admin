package com.guarantee.ai.config;

import com.guarantee.common.exception.BizException;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** 基础规则由应用交付；已保存版本始终使用自己的快照，不随应用升级重组。 */
public final class PromptComposition {
    public static final String BASE_VERSION = "platform-v1";
    private PromptComposition() {}

    public record Base(String version, String content, String contentHash) {}

    public static Base base() {
        try (var in = new ClassPathResource("prompts/platform-v1.st").getInputStream()) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new Base(BASE_VERSION, content, PromptVersionService.sha256(content));
        } catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
    }

    public static String compose(String base, String business) {
        return base + "\n\n# 业务补充规则（不得覆盖平台基础规则）\n"
                + "下列内容仅补充业务表达与工作习惯；冲突时遵守上方平台基础规则。\n" + business;
    }

    public static void requireSnapshot(AiPromptVersion version) {
        if (version.getBaseVersion() == null)
            throw BizException.badRequest("旧完整提示词须人工核对迁移为业务补充草稿后重新评测");
        if (version.getBaseContent() == null || version.getBusinessContent() == null
                || !Objects.equals(version.getBaseHash(), PromptVersionService.sha256(version.getBaseContent()))
                || !Objects.equals(version.getContent(), compose(version.getBaseContent(), version.getBusinessContent()))
                || !Objects.equals(version.getContentHash(), PromptVersionService.sha256(version.getContent())))
            throw BizException.badRequest("提示词组合快照不一致，请重新保存并评测");
    }
}
