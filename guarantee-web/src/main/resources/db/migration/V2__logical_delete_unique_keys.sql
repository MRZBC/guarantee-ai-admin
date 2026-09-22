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
--  批次 2：13 个业务唯一键改造为 (业务键, deleted_at)
--
--  前置条件（必须先用 V1 加列，且已完成重复数据校验）：见任务书 §1.2
--
--  为什么必须是 (业务键, deleted_at) 而不是 (业务键, is_deleted)：
--    is_deleted 只有 0/1，第二次删除同业务键会撞 'xxx-1'；
--    deleted_at 每次删除都不同（微秒），支持无限次删建循环（设计文档 §2.2 实测）。
--    MySQL 的 UNIQUE 允许多个 NULL，因此"未删除行"之间不冲突。
--
--  执行前请务必确认下列语句全部返回 0 行：
--    SELECT username FROM sys_user GROUP BY username HAVING COUNT(*) > 1;
--    ...（13 个键逐个，见任务书 §1.2）
-- ---------------------------------------------------------------------


-- sys_user.uk_sys_user_username: (username) -> (username, deleted_at)
SET @cols_before := (SELECT COUNT(*) FROM information_schema.STATISTICS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND INDEX_NAME = 'uk_sys_user_username');
SET @cols_after := (SELECT COUNT(*) FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND INDEX_NAME = 'uk_sys_user_username'
                       AND COLUMN_NAME = 'deleted_at');
SET @ddl := IF(@cols_before > 0 AND @cols_after = 0,
  'ALTER TABLE sys_user DROP INDEX uk_sys_user_username', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

SET @cols_after := (SELECT COUNT(*) FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND INDEX_NAME = 'uk_sys_user_username'
                       AND COLUMN_NAME = 'deleted_at');
SET @ddl := IF(@cols_after = 0,
  'ALTER TABLE sys_user ADD UNIQUE KEY uk_sys_user_username (username, deleted_at)', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- sys_org.uk_sys_org_code: (org_code) -> (org_code, deleted_at)
SET @cols_before := (SELECT COUNT(*) FROM information_schema.STATISTICS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_org' AND INDEX_NAME = 'uk_sys_org_code');
SET @cols_after := (SELECT COUNT(*) FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_org' AND INDEX_NAME = 'uk_sys_org_code'
                       AND COLUMN_NAME = 'deleted_at');
SET @ddl := IF(@cols_before > 0 AND @cols_after = 0,
  'ALTER TABLE sys_org DROP INDEX uk_sys_org_code', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

SET @cols_after := (SELECT COUNT(*) FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_org' AND INDEX_NAME = 'uk_sys_org_code'
                       AND COLUMN_NAME = 'deleted_at');
SET @ddl := IF(@cols_after = 0,
  'ALTER TABLE sys_org ADD UNIQUE KEY uk_sys_org_code (org_code, deleted_at)', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- sys_department.uk_sys_dept_code: (dept_code) -> (dept_code, deleted_at)
SET @cols_before := (SELECT COUNT(*) FROM information_schema.STATISTICS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_department' AND INDEX_NAME = 'uk_sys_dept_code');
SET @cols_after := (SELECT COUNT(*) FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_department' AND INDEX_NAME = 'uk_sys_dept_code'
                       AND COLUMN_NAME = 'deleted_at');
SET @ddl := IF(@cols_before > 0 AND @cols_after = 0,
  'ALTER TABLE sys_department DROP INDEX uk_sys_dept_code', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

SET @cols_after := (SELECT COUNT(*) FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_department' AND INDEX_NAME = 'uk_sys_dept_code'
                       AND COLUMN_NAME = 'deleted_at');
SET @ddl := IF(@cols_after = 0,
  'ALTER TABLE sys_department ADD UNIQUE KEY uk_sys_dept_code (dept_code, deleted_at)', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- sys_role.uk_sys_role_code: (role_code) -> (role_code, deleted_at)
SET @cols_before := (SELECT COUNT(*) FROM information_schema.STATISTICS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_role' AND INDEX_NAME = 'uk_sys_role_code');
SET @cols_after := (SELECT COUNT(*) FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_role' AND INDEX_NAME = 'uk_sys_role_code'
                       AND COLUMN_NAME = 'deleted_at');
SET @ddl := IF(@cols_before > 0 AND @cols_after = 0,
  'ALTER TABLE sys_role DROP INDEX uk_sys_role_code', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

SET @cols_after := (SELECT COUNT(*) FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_role' AND INDEX_NAME = 'uk_sys_role_code'
                       AND COLUMN_NAME = 'deleted_at');
SET @ddl := IF(@cols_after = 0,
  'ALTER TABLE sys_role ADD UNIQUE KEY uk_sys_role_code (role_code, deleted_at)', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- sys_permission.uk_sys_perm_code: (perm_code) -> (perm_code, deleted_at)
SET @cols_before := (SELECT COUNT(*) FROM information_schema.STATISTICS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_permission' AND INDEX_NAME = 'uk_sys_perm_code');
SET @cols_after := (SELECT COUNT(*) FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_permission' AND INDEX_NAME = 'uk_sys_perm_code'
                       AND COLUMN_NAME = 'deleted_at');
SET @ddl := IF(@cols_before > 0 AND @cols_after = 0,
  'ALTER TABLE sys_permission DROP INDEX uk_sys_perm_code', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

SET @cols_after := (SELECT COUNT(*) FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_permission' AND INDEX_NAME = 'uk_sys_perm_code'
                       AND COLUMN_NAME = 'deleted_at');
SET @ddl := IF(@cols_after = 0,
  'ALTER TABLE sys_permission ADD UNIQUE KEY uk_sys_perm_code (perm_code, deleted_at)', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- insurance_type.uk_insurance_type_code: (type_code) -> (type_code, deleted_at)
SET @cols_before := (SELECT COUNT(*) FROM information_schema.STATISTICS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'insurance_type' AND INDEX_NAME = 'uk_insurance_type_code');
SET @cols_after := (SELECT COUNT(*) FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'insurance_type' AND INDEX_NAME = 'uk_insurance_type_code'
                       AND COLUMN_NAME = 'deleted_at');
SET @ddl := IF(@cols_before > 0 AND @cols_after = 0,
  'ALTER TABLE insurance_type DROP INDEX uk_insurance_type_code', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

SET @cols_after := (SELECT COUNT(*) FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'insurance_type' AND INDEX_NAME = 'uk_insurance_type_code'
                       AND COLUMN_NAME = 'deleted_at');
SET @ddl := IF(@cols_after = 0,
  'ALTER TABLE insurance_type ADD UNIQUE KEY uk_insurance_type_code (type_code, deleted_at)', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- enterprise.uk_enterprise_code: (ent_code) -> (ent_code, deleted_at)
SET @cols_before := (SELECT COUNT(*) FROM information_schema.STATISTICS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'enterprise' AND INDEX_NAME = 'uk_enterprise_code');
SET @cols_after := (SELECT COUNT(*) FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'enterprise' AND INDEX_NAME = 'uk_enterprise_code'
                       AND COLUMN_NAME = 'deleted_at');
SET @ddl := IF(@cols_before > 0 AND @cols_after = 0,
  'ALTER TABLE enterprise DROP INDEX uk_enterprise_code', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

SET @cols_after := (SELECT COUNT(*) FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'enterprise' AND INDEX_NAME = 'uk_enterprise_code'
                       AND COLUMN_NAME = 'deleted_at');
SET @ddl := IF(@cols_after = 0,
  'ALTER TABLE enterprise ADD UNIQUE KEY uk_enterprise_code (ent_code, deleted_at)', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- enterprise.uk_enterprise_credit: (credit_code) -> (credit_code, deleted_at)
SET @cols_before := (SELECT COUNT(*) FROM information_schema.STATISTICS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'enterprise' AND INDEX_NAME = 'uk_enterprise_credit');
SET @cols_after := (SELECT COUNT(*) FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'enterprise' AND INDEX_NAME = 'uk_enterprise_credit'
                       AND COLUMN_NAME = 'deleted_at');
SET @ddl := IF(@cols_before > 0 AND @cols_after = 0,
  'ALTER TABLE enterprise DROP INDEX uk_enterprise_credit', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

SET @cols_after := (SELECT COUNT(*) FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'enterprise' AND INDEX_NAME = 'uk_enterprise_credit'
                       AND COLUMN_NAME = 'deleted_at');
SET @ddl := IF(@cols_after = 0,
  'ALTER TABLE enterprise ADD UNIQUE KEY uk_enterprise_credit (credit_code, deleted_at)', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- project.uk_project_code: (project_code) -> (project_code, deleted_at)
SET @cols_before := (SELECT COUNT(*) FROM information_schema.STATISTICS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project' AND INDEX_NAME = 'uk_project_code');
SET @cols_after := (SELECT COUNT(*) FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project' AND INDEX_NAME = 'uk_project_code'
                       AND COLUMN_NAME = 'deleted_at');
SET @ddl := IF(@cols_before > 0 AND @cols_after = 0,
  'ALTER TABLE project DROP INDEX uk_project_code', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

SET @cols_after := (SELECT COUNT(*) FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project' AND INDEX_NAME = 'uk_project_code'
                       AND COLUMN_NAME = 'deleted_at');
SET @ddl := IF(@cols_after = 0,
  'ALTER TABLE project ADD UNIQUE KEY uk_project_code (project_code, deleted_at)', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- tender_order.uk_tender_order_no: (order_no) -> (order_no, deleted_at)
SET @cols_before := (SELECT COUNT(*) FROM information_schema.STATISTICS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tender_order' AND INDEX_NAME = 'uk_tender_order_no');
SET @cols_after := (SELECT COUNT(*) FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tender_order' AND INDEX_NAME = 'uk_tender_order_no'
                       AND COLUMN_NAME = 'deleted_at');
SET @ddl := IF(@cols_before > 0 AND @cols_after = 0,
  'ALTER TABLE tender_order DROP INDEX uk_tender_order_no', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

SET @cols_after := (SELECT COUNT(*) FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tender_order' AND INDEX_NAME = 'uk_tender_order_no'
                       AND COLUMN_NAME = 'deleted_at');
SET @ddl := IF(@cols_after = 0,
  'ALTER TABLE tender_order ADD UNIQUE KEY uk_tender_order_no (order_no, deleted_at)', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- performance_order.uk_perf_order_no: (order_no) -> (order_no, deleted_at)
SET @cols_before := (SELECT COUNT(*) FROM information_schema.STATISTICS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'performance_order' AND INDEX_NAME = 'uk_perf_order_no');
SET @cols_after := (SELECT COUNT(*) FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'performance_order' AND INDEX_NAME = 'uk_perf_order_no'
                       AND COLUMN_NAME = 'deleted_at');
SET @ddl := IF(@cols_before > 0 AND @cols_after = 0,
  'ALTER TABLE performance_order DROP INDEX uk_perf_order_no', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

SET @cols_after := (SELECT COUNT(*) FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'performance_order' AND INDEX_NAME = 'uk_perf_order_no'
                       AND COLUMN_NAME = 'deleted_at');
SET @ddl := IF(@cols_after = 0,
  'ALTER TABLE performance_order ADD UNIQUE KEY uk_perf_order_no (order_no, deleted_at)', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- ai_conversation.uk_ai_conv_no: (conversation_no) -> (conversation_no, deleted_at)
SET @cols_before := (SELECT COUNT(*) FROM information_schema.STATISTICS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_conversation' AND INDEX_NAME = 'uk_ai_conv_no');
SET @cols_after := (SELECT COUNT(*) FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_conversation' AND INDEX_NAME = 'uk_ai_conv_no'
                       AND COLUMN_NAME = 'deleted_at');
SET @ddl := IF(@cols_before > 0 AND @cols_after = 0,
  'ALTER TABLE ai_conversation DROP INDEX uk_ai_conv_no', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

SET @cols_after := (SELECT COUNT(*) FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_conversation' AND INDEX_NAME = 'uk_ai_conv_no'
                       AND COLUMN_NAME = 'deleted_at');
SET @ddl := IF(@cols_after = 0,
  'ALTER TABLE ai_conversation ADD UNIQUE KEY uk_ai_conv_no (conversation_no, deleted_at)', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- ai_operation_proposal.uk_proposal_no: (proposal_no) -> (proposal_no, deleted_at)
SET @cols_before := (SELECT COUNT(*) FROM information_schema.STATISTICS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_operation_proposal' AND INDEX_NAME = 'uk_proposal_no');
SET @cols_after := (SELECT COUNT(*) FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_operation_proposal' AND INDEX_NAME = 'uk_proposal_no'
                       AND COLUMN_NAME = 'deleted_at');
SET @ddl := IF(@cols_before > 0 AND @cols_after = 0,
  'ALTER TABLE ai_operation_proposal DROP INDEX uk_proposal_no', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

SET @cols_after := (SELECT COUNT(*) FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_operation_proposal' AND INDEX_NAME = 'uk_proposal_no'
                       AND COLUMN_NAME = 'deleted_at');
SET @ddl := IF(@cols_after = 0,
  'ALTER TABLE ai_operation_proposal ADD UNIQUE KEY uk_proposal_no (proposal_no, deleted_at)', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- ---------------------------------------------------------------------
--  自检 1：13 个唯一键都必须恰好由 2 列组成，且其中一列是 deleted_at
--  期望：每个 key 返回 (2, 1)
-- ---------------------------------------------------------------------
SELECT TABLE_NAME, INDEX_NAME, COUNT(*) AS col_cnt,
       SUM(COLUMN_NAME = 'deleted_at') AS has_deleted_at
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND NON_UNIQUE = 0
  AND INDEX_NAME IN ('uk_sys_user_username', 'uk_sys_org_code', 'uk_sys_dept_code', 'uk_sys_role_code', 'uk_sys_perm_code', 'uk_insurance_type_code', 'uk_enterprise_code', 'uk_enterprise_credit', 'uk_project_code', 'uk_tender_order_no', 'uk_perf_order_no', 'uk_ai_conv_no', 'uk_proposal_no')
GROUP BY TABLE_NAME, INDEX_NAME
ORDER BY TABLE_NAME, INDEX_NAME;

-- ---------------------------------------------------------------------
--  自检 2：业务唯一性未被削弱（LD-T8a）
--  未删除行之间仍必须互斥：下面两句都必须报 1062 Duplicate entry
--  （手工执行，用 t_ld_check 临时表验证，不污染业务表）：
--
--  CREATE TABLE IF NOT EXISTS t_ld_check (
--      id BIGINT AUTO_INCREMENT PRIMARY KEY,
--      username VARCHAR(64) NOT NULL,
--      is_deleted TINYINT NOT NULL DEFAULT 0,
--      deleted_at DATETIME(6) NULL DEFAULT NULL,
--      deleted_by VARCHAR(64) NOT NULL DEFAULT 'DB',
--      UNIQUE KEY uk_t_ld_check (username, deleted_at)
--  );
--  INSERT INTO t_ld_check (username) VALUES ('dup');
--  INSERT INTO t_ld_check (username) VALUES ('dup');   -- 期望 1062
-- ---------------------------------------------------------------------
