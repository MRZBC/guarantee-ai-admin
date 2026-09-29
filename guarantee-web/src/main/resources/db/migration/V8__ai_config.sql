-- =====================================================================
--  第四阶段：AI 配置底座（T4-00 / REQ-CFG-01、REQ-CFG-02）
--
--  依据：docs/REQ-第四阶段-AI配置与确认审计.md §6.1（表结构）、§6.2（配置项总表）、§6.4（回答级回溯）
--
--  本脚本含**两部分**：
--    第 1 部分（T4-00）：新建 ai_config_item / ai_prompt_version 两张表；
--    第 2 部分（T4-02）：ai_conversation 追加回答级回溯列 prompt_version / config_version。
--    V8 从未在真库执行过，故回溯列直接并入本文件而不另开 V9（V9 留给第五阶段观测、V10 留给 MCP）。
--
--  新增两张表（本阶段表数 20 → 22，与第三阶段 V7 的顺序无关）：
--    1) ai_config_item     配置项的"当前值"；元数据以 AiConfigCatalog 为唯一真源，
--                          行上的 value_type/default_value/... 列是写库时生成的投影，
--                          只为运维直接读表时能看懂一行配置，应用读回时不依赖它们。
--    2) ai_prompt_version  提示词版本（DRAFT → PUBLISHED → ARCHIVED）；
--                          同一时刻只有一个 PUBLISHED 由服务层保证（MySQL 无部分唯一索引）。
--
--  ⚠️ 本仓库**没有 Flyway**：db/migration/V1~V8 全部是**手工执行的幂等脚本**，
--     应用启动不会自动跑本目录。CREATE TABLE IF NOT EXISTS 本身幂等，可重复执行。
--     schema.sql 会在 T4-01 追加与这里**逐字一致**的 DDL（新库自举用），两处内容必须同源。
--
--  执行方式（手工执行）：
--    mysql -h 127.0.0.1 -P 3307 -u guarantee -p guarantee_ai_admin < <本文件>
--
--  回滚（一般不需要；保留供演练）：
--    ALTER TABLE ai_conversation DROP COLUMN prompt_version;
--    ALTER TABLE ai_conversation DROP COLUMN config_version;
--    DROP TABLE IF EXISTS ai_prompt_version;
--    DROP TABLE IF EXISTS ai_config_item;
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1) 配置项当前值
--
--    为什么"版本号"在行上：快照版本 = 全部未删除行 version 的最大值。
--    每次写入把 version 置为 max(version)+1，且恢复默认值（config_value 置 NULL）
--    也**继续递增**——版本号只增不减，否则"版本号变化 → 重载快照"的判据会失效。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_config_item (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    config_key    VARCHAR(64)   NOT NULL COMMENT '配置键（唯一，点号分层，如 budget.max-rounds）',
    config_value  TEXT          NULL     COMMENT '当前值；NULL 表示无显式值（回落 AiConfigCatalog 默认值）',
    value_type    VARCHAR(16)   NOT NULL DEFAULT 'STRING' COMMENT '值类型投影 STRING/INT/DECIMAL/BOOLEAN/ENUM',
    default_value TEXT          NULL     COMMENT '默认值投影（NULL 表示未设置、沿用框架默认）',
    min_value     DECIMAL(20,6) NULL     COMMENT '允许范围下界投影（含）',
    max_value     DECIMAL(20,6) NULL     COMMENT '允许范围上界投影（含）',
    enum_options  VARCHAR(512)  NULL     COMMENT 'ENUM 可选值投影（逗号分隔）',
    category      VARCHAR(16)   NOT NULL DEFAULT 'MODEL' COMMENT '分类投影 MODEL/SWITCH/BUDGET/PROMPT',
    dangerous     TINYINT       NOT NULL DEFAULT 0 COMMENT '是否危险配置 1是 0否（页面二次确认）',
    description   VARCHAR(255)  NULL     COMMENT '影响面说明投影',
    version       BIGINT        NOT NULL DEFAULT 0 COMMENT '配置版本号；快照版本取最大值',
    updated_by    VARCHAR(64)   NULL     COMMENT '最后修改人：应用写 sys_user.id',
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted  TINYINT     NOT NULL DEFAULT 0    COMMENT '逻辑删除 0正常 1已删除',
    deleted_at  DATETIME(6) NULL     DEFAULT NULL COMMENT '删除时间（微秒精度，唯一键分量）',
    deleted_by  VARCHAR(64) NOT NULL DEFAULT 'DB' COMMENT '删除人：应用写 sys_user.id，直连为 DB',
    PRIMARY KEY (id),
    KEY idx_ai_config_item_deleted (is_deleted),
    KEY idx_ai_config_category (category),
    UNIQUE KEY uk_ai_config_item_key (config_key, (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'AI 配置项当前值';

-- ---------------------------------------------------------------------
-- 2) 提示词版本
--
--    已发布版本只读：内容更新语句一律带 status='DRAFT' 条件（见 AiPromptVersionMapper.xml）。
--    回滚 = 重新发布历史版本（改 status/published_by/published_at），内容与哈希不变。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_prompt_version (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    version_no   INT          NOT NULL COMMENT '版本号（递增、唯一）',
    content      LONGTEXT     NOT NULL COMMENT '提示词正文（可能超 8KB，审计侧按既有截断规则处理）',
    content_hash VARCHAR(64)  NOT NULL COMMENT '正文 SHA-256 十六进制，用于审计与 diff 判定',
    status       VARCHAR(16)  NOT NULL COMMENT 'DRAFT/PUBLISHED/ARCHIVED',
    note         VARCHAR(512) NULL     COMMENT '版本说明（谁、为什么改）',
    created_by   VARCHAR(64)  NULL     COMMENT '创建人',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_by VARCHAR(64)  NULL     COMMENT '发布人',
    published_at DATETIME     NULL     COMMENT '发布时间',
    is_deleted  TINYINT     NOT NULL DEFAULT 0    COMMENT '逻辑删除 0正常 1已删除',
    deleted_at  DATETIME(6) NULL     DEFAULT NULL COMMENT '删除时间（微秒精度，唯一键分量）',
    deleted_by  VARCHAR(64) NOT NULL DEFAULT 'DB' COMMENT '删除人：应用写 sys_user.id，直连为 DB',
    PRIMARY KEY (id),
    KEY idx_ai_prompt_version_deleted (is_deleted),
    KEY idx_ai_prompt_version_status (status),
    UNIQUE KEY uk_ai_prompt_version_no (version_no, (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'AI 提示词版本';

-- ---------------------------------------------------------------------
-- 3) 回答级回溯列（REQ-CFG-05 / §6.4）
--
--    每轮对话可查到"用的是哪一版提示词 + 哪一版配置"：
--      prompt_version  当前发布版提示词版本号（ai_prompt_version.version_no，未用真源时为 NULL）
--      config_version  本轮生效的配置快照版本（ai_config_item.version 最大值，未配置时为 0）
--    写入方：config_version 由 T4-01（AiChatService 收尾时写）、prompt_version 由 T4-03 写。
--
--    MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS，沿用 V6 的 information_schema + PREPARE 幂等写法。
-- ---------------------------------------------------------------------
SET @n_ai_conv_prompt_version := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_conversation'
      AND COLUMN_NAME = 'prompt_version');
SET @ddl := IF(@n_ai_conv_prompt_version = 1, 'DO 0',
    'ALTER TABLE ai_conversation ADD COLUMN prompt_version INT NULL COMMENT ''回答所用的提示词版本号（ai_prompt_version.version_no）''');
PREPARE ai_conv_pv_stmt FROM @ddl; EXECUTE ai_conv_pv_stmt; DEALLOCATE PREPARE ai_conv_pv_stmt;

SET @n_ai_conv_config_version := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_conversation'
      AND COLUMN_NAME = 'config_version');
SET @ddl := IF(@n_ai_conv_config_version = 1, 'DO 0',
    'ALTER TABLE ai_conversation ADD COLUMN config_version BIGINT NULL COMMENT ''回答所用的 AI 配置快照版本号''');
PREPARE ai_conv_cv_stmt FROM @ddl; EXECUTE ai_conv_cv_stmt; DEALLOCATE PREPARE ai_conv_cv_stmt;

-- 复核（前两项应为 1，第三项应为 2）
SELECT COUNT(*) AS ai_config_item_present
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_config_item';

SELECT COUNT(*) AS ai_prompt_version_present
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_prompt_version';

SELECT COUNT(*) AS ai_conversation_version_trace_columns
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_conversation'
  AND COLUMN_NAME IN ('prompt_version', 'config_version');
