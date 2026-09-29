-- =====================================================================
--  业务知识底座迁移脚本 V7：ai_knowledge_item + ai_knowledge_import_log
--
--  依据：docs/REQ-第三阶段-RAG业务知识.md §5.1.1 / §5.1.2 / §6.1（REQ-RAG-01/02）
--
--  背景：
--    第三阶段把"业务知识硬编码在系统提示词里"改成"可检索、可溯源、可更新"。
--    真源是仓库内的 Markdown（YAML front-matter）：
--      guarantee-ai/src/main/resources/knowledge/<domain>/<knowledge_no>-<slug>.md
--    本脚本建的两张表是它在运行期的投影：
--      ai_knowledge_item      —— 检索面（生效期、版本、权限码、状态、逻辑删除）；
--      ai_knowledge_import_log—— 导入留痕（条目号/旧版本/新版本/内容哈希/来源文件），只追加。
--    启动时由 KnowledgeImportRunner → KnowledgeImporter 幂等导入（可用
--    guarantee.ai.knowledge.import-on-startup=false 关闭）。
--
--  语义要点（与 schema.sql 中的同一份定义保持一致）：
--    · version：内容变化即 +1，knowledge_no 不变；**导入器不得自动生成编号**
--      （编号人工固定在 front-matter，否则多次导入会漂移）。
--    · status：只有 PUBLISHED 参与检索；真源文件消失 → 置 RETIRED，**不物理删除**。
--    · permission_code：NULL/空 = 登录即可见；裁剪发生在 Service 层（不依赖提示词自约束）。
--    · 逻辑删除房规（LD-T18 逐列断言，别踩）：
--        is_deleted TINYINT NOT NULL DEFAULT 0；
--        deleted_at DATETIME(6) NULL **禁止任何默认值**（有默认值会让有效行的 deleted_at 非 NULL，
--          直接绕过下面的函数唯一键）；
--        deleted_by VARCHAR(64) NOT NULL DEFAULT 'DB'；
--        索引名必须是 idx_ai_knowledge_item_deleted（测试按 `idx\_%\_deleted` 匹配）。
--    · ai_knowledge_import_log **刻意不带**逻辑删除三列、也不进 LogicalDeleteTables.MANAGED：
--      它是"只追加、不修改"的留痕表，没有"删除一条留痕"这个语义。
--    · 唯一键沿用逻辑删除的函数索引写法（同 V3）：
--        (knowledge_no, IFNULL(deleted_at, '1970-01-01 00:00:00.000000'))
--      未删除行 deleted_at 为 NULL → IFNULL 后是同一哨兵值，因此同一编号只能有一条有效行。
--
--  ⚠️ 本仓库**没有 Flyway**：db/migration/V1~V7 全部是**手工执行的幂等脚本**，
--     应用启动不会自动跑本目录（应用启动跑的是 schema.sql，它已包含同一份 DDL）。
--     本文件用于**存量库**补齐与变更留档；上线前请手工执行。
--
--  幂等实现说明：
--    CREATE TABLE IF NOT EXISTS 本身即幂等（不存在则建，存在则跳过），
--    因此本脚本无需 information_schema + PREPARE 那套（V1~V6 用于 ADD COLUMN/索引，
--    MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS 才不得不动态执行）。
--    重复执行无副作用；已存在的表不会被改动（列有变化时须另起 V{n} 迁移）。
--
--  执行方式（手工执行）：
--    mysql -h 127.0.0.1 -P 3307 -u guarantee -p guarantee_ai_admin < V7__ai_knowledge.sql
--
--  回滚（一般不需要；保留供演练）：
--    DROP TABLE ai_knowledge_import_log;
--    DROP TABLE ai_knowledge_item;
-- =====================================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS ai_knowledge_item (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    knowledge_no    VARCHAR(32)  NOT NULL COMMENT '稳定编号 KB-<DOMAIN>-NNNN（人工固定，全生命周期不变）',
    domain          VARCHAR(16)  NOT NULL COMMENT 'ORDER/SYSTEM/CONCEPT/POLICY',
    title           VARCHAR(120) NOT NULL COMMENT '条目标题（中文，≤60 字；溯源行逐字使用）',
    content         TEXT         NOT NULL COMMENT '条目正文（Markdown 纯文本，≤2KB）',
    keywords        VARCHAR(512) NOT NULL DEFAULT '' COMMENT '检索标签，逗号分隔（5~15 个，主力命中面）',
    effective_from  DATE         NULL COMMENT '生效起始日（NULL=不限）',
    effective_to    DATE         NULL COMMENT '生效截止日（NULL=长期有效）',
    version         INT          NOT NULL DEFAULT 1 COMMENT '内容版本号：内容变化即 +1，编号不变',
    status          VARCHAR(16)  NOT NULL DEFAULT 'PUBLISHED' COMMENT 'DRAFT/PUBLISHED/RETIRED',
    permission_code VARCHAR(64)  NULL COMMENT '可见所需权限码；NULL/空=登录即可见',
    source_ref      VARCHAR(255) NULL COMMENT '来源说明（原文出处/对应代码类/文档章节）',
    is_deleted      TINYINT      NOT NULL DEFAULT 0    COMMENT '逻辑删除 0正常 1已删除',
    deleted_at      DATETIME(6)  NULL     COMMENT '删除时间（微秒精度，唯一键分量）；禁止默认值',
    deleted_by      VARCHAR(64)  NOT NULL DEFAULT 'DB' COMMENT '删除人：应用写 sys_user.id，直连为 DB',
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_ai_knowledge_item_deleted (is_deleted),
    UNIQUE KEY uk_ai_knowledge_no (knowledge_no, (IFNULL(deleted_at, '1970-01-01 00:00:00.000000'))),
    KEY idx_ai_knowledge_lookup (domain, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '业务知识条目（Markdown 真源的运行期投影）';

CREATE TABLE IF NOT EXISTS ai_knowledge_import_log (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    knowledge_no VARCHAR(32)  NOT NULL COMMENT '条目编号',
    old_version  INT          NULL COMMENT '变更前版本（首次导入为 NULL）',
    new_version  INT          NOT NULL COMMENT '变更后版本',
    content_hash CHAR(64)     NOT NULL COMMENT '变更后内容的 SHA-256（规范化后）',
    action       VARCHAR(16)  NOT NULL COMMENT 'CREATED/UPDATED/RESTORED/RETIRED',
    source_file  VARCHAR(255) NULL COMMENT '真源文件（classpath 相对路径）；RETIRED 时记录最后已知路径',
    imported_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '导入时间',
    PRIMARY KEY (id),
    KEY idx_ai_knowledge_log_no (knowledge_no, imported_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '知识真源导入留痕（只追加，不修改）';

-- ---------------------------------------------------------------------
--  自检：两张表都应存在（幂等执行后各输出一行）
-- ---------------------------------------------------------------------
SELECT TABLE_NAME, TABLE_COMMENT
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME IN ('ai_knowledge_item', 'ai_knowledge_import_log')
 ORDER BY TABLE_NAME;

-- 自检：唯一键必须是**函数索引**形态（含表达式分量），普通列形态会导致
-- "两条同名有效行"在库层面被放行（V3 的真实事故）。
SELECT INDEX_NAME, COUNT(*) AS expr_parts
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_knowledge_item'
   AND INDEX_NAME = 'uk_ai_knowledge_no'
   AND COLUMN_NAME IS NULL AND EXPRESSION IS NOT NULL
 GROUP BY INDEX_NAME;
