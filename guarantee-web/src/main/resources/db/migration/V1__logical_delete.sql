-- =====================================================================
--  逻辑删除（is_deleted）迁移脚本
--
--  依据：docs/DEC-逻辑删除设计方案.md v2.1（唯一设计依据）
--  生成：node scripts/gen-logical-delete-sql.mjs（请勿手工修改本文件）
--
--  幂等实现说明（重要）：
--    MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS，因此每个 DDL 都用
--    information_schema 判断 + PREPARE/EXECUTE 动态执行。这**不是**存储过程：
--    PREPARE 是会话级语句，不会在库里留下任何数据库对象（LD-EX-02 禁止的是
--    触发器 / 存储过程 / 函数，本脚本不创建其中任何一个，LD-T22 可验证）。
--
--  执行方式（手工执行，应用启动不会自动跑本目录）：
--    mysql -h 127.0.0.1 -P 3307 -u guarantee -p guarantee_ai_admin < <本文件>
-- =====================================================================

-- ---------------------------------------------------------------------
--  批次 1：18 张表新增 is_deleted / deleted_at / deleted_by + idx_*_deleted
--
--  三个硬约束（真机实测，见设计文档 §2.1b / 任务书 §2）：
--    1) deleted_at 必须 DATETIME(6) 且 DEFAULT NULL ——
--       秒精度会在"同一秒内删除→重建→再删除"时撞唯一键；
--       加 DEFAULT CURRENT_TIMESTAMP(6) 会让有效行的 deleted_at 非 NULL，
--       从而绕过 (业务键, deleted_at) 唯一键、使多个同名有效账号共存。
--    2) deleted_by 必须 VARCHAR(64) NOT NULL DEFAULT 'DB'，不能是 BIGINT ——
--       BIGINT 的 DEFAULT 0 会把"直连删除"伪装成"存在 id=0 的用户"。
--    3) 删除时三列必须在同一条语句里写全（应用走 softDelete()，直连见设计文档 §9.4.1）。
--
--  未包含：ai_operation_secret（LD-EX-01，保持物理删除，刻意不加字段）。
-- ---------------------------------------------------------------------


-- sys_org
SET @n_sys_org := (SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_org'
                       AND COLUMN_NAME IN ('is_deleted', 'deleted_at', 'deleted_by'));
SET @ddl := IF(@n_sys_org = 3, 'DO 0',
  'ALTER TABLE sys_org ADD COLUMN is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除 0正常 1已删除'', ADD COLUMN deleted_at DATETIME(6) NULL DEFAULT NULL COMMENT ''删除时间（微秒精度，唯一键分量）'', ADD COLUMN deleted_by VARCHAR(64) NOT NULL DEFAULT ''DB'' COMMENT ''删除人：应用写 sys_user.id，直连为 DB''');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_org'
                        AND INDEX_NAME = 'idx_sys_org_deleted');
SET @ddl := IF(@has_idx = 0, 'ALTER TABLE sys_org ADD INDEX idx_sys_org_deleted (is_deleted)', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- sys_department
SET @n_sys_department := (SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_department'
                       AND COLUMN_NAME IN ('is_deleted', 'deleted_at', 'deleted_by'));
SET @ddl := IF(@n_sys_department = 3, 'DO 0',
  'ALTER TABLE sys_department ADD COLUMN is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除 0正常 1已删除'', ADD COLUMN deleted_at DATETIME(6) NULL DEFAULT NULL COMMENT ''删除时间（微秒精度，唯一键分量）'', ADD COLUMN deleted_by VARCHAR(64) NOT NULL DEFAULT ''DB'' COMMENT ''删除人：应用写 sys_user.id，直连为 DB''');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_department'
                        AND INDEX_NAME = 'idx_sys_department_deleted');
SET @ddl := IF(@has_idx = 0, 'ALTER TABLE sys_department ADD INDEX idx_sys_department_deleted (is_deleted)', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- sys_user
SET @n_sys_user := (SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user'
                       AND COLUMN_NAME IN ('is_deleted', 'deleted_at', 'deleted_by'));
SET @ddl := IF(@n_sys_user = 3, 'DO 0',
  'ALTER TABLE sys_user ADD COLUMN is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除 0正常 1已删除'', ADD COLUMN deleted_at DATETIME(6) NULL DEFAULT NULL COMMENT ''删除时间（微秒精度，唯一键分量）'', ADD COLUMN deleted_by VARCHAR(64) NOT NULL DEFAULT ''DB'' COMMENT ''删除人：应用写 sys_user.id，直连为 DB''');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user'
                        AND INDEX_NAME = 'idx_sys_user_deleted');
SET @ddl := IF(@has_idx = 0, 'ALTER TABLE sys_user ADD INDEX idx_sys_user_deleted (is_deleted)', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- sys_role
SET @n_sys_role := (SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_role'
                       AND COLUMN_NAME IN ('is_deleted', 'deleted_at', 'deleted_by'));
SET @ddl := IF(@n_sys_role = 3, 'DO 0',
  'ALTER TABLE sys_role ADD COLUMN is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除 0正常 1已删除'', ADD COLUMN deleted_at DATETIME(6) NULL DEFAULT NULL COMMENT ''删除时间（微秒精度，唯一键分量）'', ADD COLUMN deleted_by VARCHAR(64) NOT NULL DEFAULT ''DB'' COMMENT ''删除人：应用写 sys_user.id，直连为 DB''');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_role'
                        AND INDEX_NAME = 'idx_sys_role_deleted');
SET @ddl := IF(@has_idx = 0, 'ALTER TABLE sys_role ADD INDEX idx_sys_role_deleted (is_deleted)', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- sys_permission
SET @n_sys_permission := (SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_permission'
                       AND COLUMN_NAME IN ('is_deleted', 'deleted_at', 'deleted_by'));
SET @ddl := IF(@n_sys_permission = 3, 'DO 0',
  'ALTER TABLE sys_permission ADD COLUMN is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除 0正常 1已删除'', ADD COLUMN deleted_at DATETIME(6) NULL DEFAULT NULL COMMENT ''删除时间（微秒精度，唯一键分量）'', ADD COLUMN deleted_by VARCHAR(64) NOT NULL DEFAULT ''DB'' COMMENT ''删除人：应用写 sys_user.id，直连为 DB''');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_permission'
                        AND INDEX_NAME = 'idx_sys_permission_deleted');
SET @ddl := IF(@has_idx = 0, 'ALTER TABLE sys_permission ADD INDEX idx_sys_permission_deleted (is_deleted)', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- sys_user_role
SET @n_sys_user_role := (SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user_role'
                       AND COLUMN_NAME IN ('is_deleted', 'deleted_at', 'deleted_by'));
SET @ddl := IF(@n_sys_user_role = 3, 'DO 0',
  'ALTER TABLE sys_user_role ADD COLUMN is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除 0正常 1已删除'', ADD COLUMN deleted_at DATETIME(6) NULL DEFAULT NULL COMMENT ''删除时间（微秒精度，唯一键分量）'', ADD COLUMN deleted_by VARCHAR(64) NOT NULL DEFAULT ''DB'' COMMENT ''删除人：应用写 sys_user.id，直连为 DB''');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user_role'
                        AND INDEX_NAME = 'idx_sys_user_role_deleted');
SET @ddl := IF(@has_idx = 0, 'ALTER TABLE sys_user_role ADD INDEX idx_sys_user_role_deleted (is_deleted)', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- sys_role_permission
SET @n_sys_role_permission := (SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_role_permission'
                       AND COLUMN_NAME IN ('is_deleted', 'deleted_at', 'deleted_by'));
SET @ddl := IF(@n_sys_role_permission = 3, 'DO 0',
  'ALTER TABLE sys_role_permission ADD COLUMN is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除 0正常 1已删除'', ADD COLUMN deleted_at DATETIME(6) NULL DEFAULT NULL COMMENT ''删除时间（微秒精度，唯一键分量）'', ADD COLUMN deleted_by VARCHAR(64) NOT NULL DEFAULT ''DB'' COMMENT ''删除人：应用写 sys_user.id，直连为 DB''');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_role_permission'
                        AND INDEX_NAME = 'idx_sys_role_permission_deleted');
SET @ddl := IF(@has_idx = 0, 'ALTER TABLE sys_role_permission ADD INDEX idx_sys_role_permission_deleted (is_deleted)', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- insurance_type
SET @n_insurance_type := (SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'insurance_type'
                       AND COLUMN_NAME IN ('is_deleted', 'deleted_at', 'deleted_by'));
SET @ddl := IF(@n_insurance_type = 3, 'DO 0',
  'ALTER TABLE insurance_type ADD COLUMN is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除 0正常 1已删除'', ADD COLUMN deleted_at DATETIME(6) NULL DEFAULT NULL COMMENT ''删除时间（微秒精度，唯一键分量）'', ADD COLUMN deleted_by VARCHAR(64) NOT NULL DEFAULT ''DB'' COMMENT ''删除人：应用写 sys_user.id，直连为 DB''');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'insurance_type'
                        AND INDEX_NAME = 'idx_insurance_type_deleted');
SET @ddl := IF(@has_idx = 0, 'ALTER TABLE insurance_type ADD INDEX idx_insurance_type_deleted (is_deleted)', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- enterprise
SET @n_enterprise := (SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'enterprise'
                       AND COLUMN_NAME IN ('is_deleted', 'deleted_at', 'deleted_by'));
SET @ddl := IF(@n_enterprise = 3, 'DO 0',
  'ALTER TABLE enterprise ADD COLUMN is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除 0正常 1已删除'', ADD COLUMN deleted_at DATETIME(6) NULL DEFAULT NULL COMMENT ''删除时间（微秒精度，唯一键分量）'', ADD COLUMN deleted_by VARCHAR(64) NOT NULL DEFAULT ''DB'' COMMENT ''删除人：应用写 sys_user.id，直连为 DB''');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'enterprise'
                        AND INDEX_NAME = 'idx_enterprise_deleted');
SET @ddl := IF(@has_idx = 0, 'ALTER TABLE enterprise ADD INDEX idx_enterprise_deleted (is_deleted)', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- project
SET @n_project := (SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project'
                       AND COLUMN_NAME IN ('is_deleted', 'deleted_at', 'deleted_by'));
SET @ddl := IF(@n_project = 3, 'DO 0',
  'ALTER TABLE project ADD COLUMN is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除 0正常 1已删除'', ADD COLUMN deleted_at DATETIME(6) NULL DEFAULT NULL COMMENT ''删除时间（微秒精度，唯一键分量）'', ADD COLUMN deleted_by VARCHAR(64) NOT NULL DEFAULT ''DB'' COMMENT ''删除人：应用写 sys_user.id，直连为 DB''');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project'
                        AND INDEX_NAME = 'idx_project_deleted');
SET @ddl := IF(@has_idx = 0, 'ALTER TABLE project ADD INDEX idx_project_deleted (is_deleted)', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- tender_order
SET @n_tender_order := (SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tender_order'
                       AND COLUMN_NAME IN ('is_deleted', 'deleted_at', 'deleted_by'));
SET @ddl := IF(@n_tender_order = 3, 'DO 0',
  'ALTER TABLE tender_order ADD COLUMN is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除 0正常 1已删除'', ADD COLUMN deleted_at DATETIME(6) NULL DEFAULT NULL COMMENT ''删除时间（微秒精度，唯一键分量）'', ADD COLUMN deleted_by VARCHAR(64) NOT NULL DEFAULT ''DB'' COMMENT ''删除人：应用写 sys_user.id，直连为 DB''');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tender_order'
                        AND INDEX_NAME = 'idx_tender_order_deleted');
SET @ddl := IF(@has_idx = 0, 'ALTER TABLE tender_order ADD INDEX idx_tender_order_deleted (is_deleted)', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- performance_order
SET @n_performance_order := (SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'performance_order'
                       AND COLUMN_NAME IN ('is_deleted', 'deleted_at', 'deleted_by'));
SET @ddl := IF(@n_performance_order = 3, 'DO 0',
  'ALTER TABLE performance_order ADD COLUMN is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除 0正常 1已删除'', ADD COLUMN deleted_at DATETIME(6) NULL DEFAULT NULL COMMENT ''删除时间（微秒精度，唯一键分量）'', ADD COLUMN deleted_by VARCHAR(64) NOT NULL DEFAULT ''DB'' COMMENT ''删除人：应用写 sys_user.id，直连为 DB''');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'performance_order'
                        AND INDEX_NAME = 'idx_performance_order_deleted');
SET @ddl := IF(@has_idx = 0, 'ALTER TABLE performance_order ADD INDEX idx_performance_order_deleted (is_deleted)', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- ai_conversation
SET @n_ai_conversation := (SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_conversation'
                       AND COLUMN_NAME IN ('is_deleted', 'deleted_at', 'deleted_by'));
SET @ddl := IF(@n_ai_conversation = 3, 'DO 0',
  'ALTER TABLE ai_conversation ADD COLUMN is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除 0正常 1已删除'', ADD COLUMN deleted_at DATETIME(6) NULL DEFAULT NULL COMMENT ''删除时间（微秒精度，唯一键分量）'', ADD COLUMN deleted_by VARCHAR(64) NOT NULL DEFAULT ''DB'' COMMENT ''删除人：应用写 sys_user.id，直连为 DB''');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_conversation'
                        AND INDEX_NAME = 'idx_ai_conversation_deleted');
SET @ddl := IF(@has_idx = 0, 'ALTER TABLE ai_conversation ADD INDEX idx_ai_conversation_deleted (is_deleted)', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- ai_message
SET @n_ai_message := (SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_message'
                       AND COLUMN_NAME IN ('is_deleted', 'deleted_at', 'deleted_by'));
SET @ddl := IF(@n_ai_message = 3, 'DO 0',
  'ALTER TABLE ai_message ADD COLUMN is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除 0正常 1已删除'', ADD COLUMN deleted_at DATETIME(6) NULL DEFAULT NULL COMMENT ''删除时间（微秒精度，唯一键分量）'', ADD COLUMN deleted_by VARCHAR(64) NOT NULL DEFAULT ''DB'' COMMENT ''删除人：应用写 sys_user.id，直连为 DB''');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_message'
                        AND INDEX_NAME = 'idx_ai_message_deleted');
SET @ddl := IF(@has_idx = 0, 'ALTER TABLE ai_message ADD INDEX idx_ai_message_deleted (is_deleted)', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- ai_tool_call
SET @n_ai_tool_call := (SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_tool_call'
                       AND COLUMN_NAME IN ('is_deleted', 'deleted_at', 'deleted_by'));
SET @ddl := IF(@n_ai_tool_call = 3, 'DO 0',
  'ALTER TABLE ai_tool_call ADD COLUMN is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除 0正常 1已删除'', ADD COLUMN deleted_at DATETIME(6) NULL DEFAULT NULL COMMENT ''删除时间（微秒精度，唯一键分量）'', ADD COLUMN deleted_by VARCHAR(64) NOT NULL DEFAULT ''DB'' COMMENT ''删除人：应用写 sys_user.id，直连为 DB''');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_tool_call'
                        AND INDEX_NAME = 'idx_ai_tool_call_deleted');
SET @ddl := IF(@has_idx = 0, 'ALTER TABLE ai_tool_call ADD INDEX idx_ai_tool_call_deleted (is_deleted)', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- ai_audit_log
SET @n_ai_audit_log := (SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_audit_log'
                       AND COLUMN_NAME IN ('is_deleted', 'deleted_at', 'deleted_by'));
SET @ddl := IF(@n_ai_audit_log = 3, 'DO 0',
  'ALTER TABLE ai_audit_log ADD COLUMN is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除 0正常 1已删除'', ADD COLUMN deleted_at DATETIME(6) NULL DEFAULT NULL COMMENT ''删除时间（微秒精度，唯一键分量）'', ADD COLUMN deleted_by VARCHAR(64) NOT NULL DEFAULT ''DB'' COMMENT ''删除人：应用写 sys_user.id，直连为 DB''');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_audit_log'
                        AND INDEX_NAME = 'idx_ai_audit_log_deleted');
SET @ddl := IF(@has_idx = 0, 'ALTER TABLE ai_audit_log ADD INDEX idx_ai_audit_log_deleted (is_deleted)', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- ai_operation_proposal
SET @n_ai_operation_proposal := (SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_operation_proposal'
                       AND COLUMN_NAME IN ('is_deleted', 'deleted_at', 'deleted_by'));
SET @ddl := IF(@n_ai_operation_proposal = 3, 'DO 0',
  'ALTER TABLE ai_operation_proposal ADD COLUMN is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除 0正常 1已删除'', ADD COLUMN deleted_at DATETIME(6) NULL DEFAULT NULL COMMENT ''删除时间（微秒精度，唯一键分量）'', ADD COLUMN deleted_by VARCHAR(64) NOT NULL DEFAULT ''DB'' COMMENT ''删除人：应用写 sys_user.id，直连为 DB''');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_operation_proposal'
                        AND INDEX_NAME = 'idx_ai_operation_proposal_deleted');
SET @ddl := IF(@has_idx = 0, 'ALTER TABLE ai_operation_proposal ADD INDEX idx_ai_operation_proposal_deleted (is_deleted)', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- ai_operation_audit
SET @n_ai_operation_audit := (SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_operation_audit'
                       AND COLUMN_NAME IN ('is_deleted', 'deleted_at', 'deleted_by'));
SET @ddl := IF(@n_ai_operation_audit = 3, 'DO 0',
  'ALTER TABLE ai_operation_audit ADD COLUMN is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除 0正常 1已删除'', ADD COLUMN deleted_at DATETIME(6) NULL DEFAULT NULL COMMENT ''删除时间（微秒精度，唯一键分量）'', ADD COLUMN deleted_by VARCHAR(64) NOT NULL DEFAULT ''DB'' COMMENT ''删除人：应用写 sys_user.id，直连为 DB''');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_operation_audit'
                        AND INDEX_NAME = 'idx_ai_operation_audit_deleted');
SET @ddl := IF(@has_idx = 0, 'ALTER TABLE ai_operation_audit ADD INDEX idx_ai_operation_audit_deleted (is_deleted)', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- ---------------------------------------------------------------------
--  自检：必须恰好 18 张表 × 3 列 = 54 行；缺失或多余都要停下来排查
-- ---------------------------------------------------------------------
SELECT c.TABLE_NAME, c.COLUMN_NAME, c.COLUMN_TYPE, c.IS_NULLABLE, c.COLUMN_DEFAULT
FROM information_schema.COLUMNS c
WHERE c.TABLE_SCHEMA = DATABASE() AND c.COLUMN_NAME IN ('is_deleted', 'deleted_at', 'deleted_by')
ORDER BY c.TABLE_NAME, c.COLUMN_NAME;

SELECT 'table_count' AS metric, COUNT(DISTINCT TABLE_NAME) AS val
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME = 'is_deleted';

SELECT 'index_count' AS metric, COUNT(DISTINCT TABLE_NAME) AS val
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND INDEX_NAME LIKE 'idx_%_deleted';
