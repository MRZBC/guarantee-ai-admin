-- 不自动切割或改写历史正文；组合快照只在人工保存新草稿时写入。
CREATE TABLE IF NOT EXISTS ai_prompt_composition (
    version_no INT NOT NULL PRIMARY KEY,
    base_version VARCHAR(64) NOT NULL,
    base_hash VARCHAR(64) NOT NULL,
    base_content LONGTEXT NOT NULL,
    business_content LONGTEXT NOT NULL,
    migration_source_version INT NULL,
    migration_source_hash VARCHAR(64) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='提示词基础规则与业务补充快照';
