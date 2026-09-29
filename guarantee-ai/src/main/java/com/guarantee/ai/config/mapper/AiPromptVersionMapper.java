package com.guarantee.ai.config.mapper;

import com.guarantee.ai.config.AiPromptVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 提示词版本 Mapper（{@code ai_prompt_version}，REQ-CFG-02）。
 *
 * <p><b>已发布版本只读</b>：所有内容更新语句都带 {@code status = 'DRAFT'} 条件，
 * 由 SQL 而不是调用方的自觉来保证"发布后不可改"。</p>
 */
@Mapper
public interface AiPromptVersionMapper {

    /** 版本历史（按 version_no 倒序）。 */
    List<AiPromptVersion> selectAll();

    /** 单个版本（未删除）。 */
    AiPromptVersion selectByVersionNo(@Param("versionNo") int versionNo);

    /** 当前 PUBLISHED 版本；无发布版时返回 null（运行期回落到 classpath 提示词）。 */
    AiPromptVersion selectPublished();

    /** 最新 DRAFT 版本（页面"继续编辑草稿"用）；无草稿返回 null。 */
    AiPromptVersion selectLatestDraft();

    /** 最大版本号；空表返回 null。 */
    Integer maxVersionNo();

    int insert(AiPromptVersion version);

    /** 仅允许更新 DRAFT 的内容/哈希/说明。 */
    int updateDraft(@Param("versionNo") int versionNo,
                    @Param("content") String content,
                    @Param("contentHash") String contentHash,
                    @Param("note") String note);

    /** 把当前 PUBLISHED（除 {@code keepVersionNo} 外）全部置为 ARCHIVED。 */
    int archivePublishedExcept(@Param("keepVersionNo") int keepVersionNo);

    /** 发布一个 DRAFT 或历史 ARCHIVED 版本（回滚即发布历史版本）。 */
    int publish(@Param("versionNo") int versionNo, @Param("publishedBy") String publishedBy);
}
