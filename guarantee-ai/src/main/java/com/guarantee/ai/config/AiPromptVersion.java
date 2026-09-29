package com.guarantee.ai.config;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 提示词版本（{@code ai_prompt_version}，REQ-CFG-02）。
 *
 * <p>状态机 {@code DRAFT → PUBLISHED → ARCHIVED}；同一时刻只有一个 {@code PUBLISHED}。
 * 已发布版本**只读**：回滚是"把生效版本指回历史版本"，不是修改历史版本的内容。</p>
 */
@Data
public class AiPromptVersion {

    /** DRAFT（草稿，可编辑）。 */
    public static final String STATUS_DRAFT = "DRAFT";
    /** PUBLISHED（已发布，运行期使用）。 */
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    /** ARCHIVED（已归档，只读历史）。 */
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    private Long id;
    /** 版本号（递增，唯一）。 */
    private Integer versionNo;
    /** 提示词正文。 */
    private String content;
    /** 内容哈希（SHA-256 十六进制），用于审计与 diff 判定。 */
    private String contentHash;
    /** DRAFT / PUBLISHED / ARCHIVED。 */
    private String status;
    /** 版本说明。 */
    private String note;
    /** 创建人。 */
    private String createdBy;
    private LocalDateTime createdAt;
    /** 发布人。 */
    private String publishedBy;
    private LocalDateTime publishedAt;

    private Integer isDeleted;
    private LocalDateTime deletedAt;
    private String deletedBy;
}
