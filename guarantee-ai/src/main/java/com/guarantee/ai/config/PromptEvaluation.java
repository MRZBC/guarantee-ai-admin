package com.guarantee.ai.config;

import lombok.Data;
import java.time.LocalDateTime;

/** 候选正文不可变快照；状态和结果由后台任务更新。 */
@Data
public class PromptEvaluation {
    private String id;
    private Integer versionNo;
    private String contentHash;
    private String content;
    private String baselineContent;
    private String contextHash;
    private String contextSnapshot;
    private String suiteVersion;
    private String status;
    private String summary;
    private String results;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
}
