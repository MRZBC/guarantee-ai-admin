-- 幂等增量脚本；同源 DDL 同时收录于 db/schema.sql。
CREATE TABLE IF NOT EXISTS ai_prompt_control (
    id INT NOT NULL PRIMARY KEY
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO ai_prompt_control(id) VALUES(1);

CREATE TABLE IF NOT EXISTS ai_prompt_evaluation (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    version_no INT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    content LONGTEXT NOT NULL,
    baseline_content LONGTEXT NOT NULL,
    context_hash VARCHAR(64) NOT NULL,
    context_snapshot LONGTEXT NOT NULL,
    suite_version VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    summary VARCHAR(512) NOT NULL,
    results LONGTEXT NULL,
    created_by VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at DATETIME(6) NULL,
    KEY idx_prompt_eval_version(version_no,created_at),
    KEY idx_prompt_eval_status(status,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='候选提示词评测快照与结果';
