-- =====================================================================
--  逻辑删除（is_deleted）迁移脚本 V3：业务唯一键改为函数索引
--
--  依据：docs/DEC-逻辑删除设计方案.md（v2.2 修订项）
--
--  【为什么必须改】V2 建的 (业务键, deleted_at) 有一个致命漏洞：
--    未删除行的 deleted_at 是 NULL，而 MySQL 唯一索引**允许多个 NULL**，
--    因此"两条同名有效行"在数据库层面是被放行的。实测：
--      INSERT INTO t (username) VALUES ('u1');   -- OK
--      INSERT INTO t (username) VALUES ('u1');   -- OK（错误！本应被拒绝）
--    后果：selectByUsername 返回多行 -> MyBatis TooManyResultsException -> 登录失败。
--    连带风险：`UPDATE ... SET deleted_at = NOW(6) WHERE 业务键 = ? AND is_deleted = 0`
--    命中多行时，NOW(6) 是语句级常量 -> 所有行拿到相同 deleted_at -> 直接撞唯一键。
--
--  【修复】把 NULL 归一化为一个哨兵值后再进唯一索引：
--    UNIQUE KEY uk_xxx (业务键, (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')))
--      - 有效行：IFNULL 结果都是哨兵值 -> 同一业务键只能有一条有效行 ✔
--      - 已删除行：各自 deleted_at 不同 -> 可多条共存、支持无限次删建 ✔
--    这正是 PostgreSQL "partial unique index WHERE is_deleted = 0" 的等价实现。
--
--  【合规】函数索引是 MySQL 8.0.13+ 的**索引表达式**，不是触发器 / 存储过程 / 函数对象，
--    不违反 LD-EX-02；LD-T22（库中无 TRIGGERS / ROUTINES）仍然通过。
--
--  【幂等】判据：该索引是否已含表达式分量（STATISTICS.EXPRESSION 非空）。
--    已是表达式形态 -> 不做任何事；否则 DROP 后按正确形态重建。
--    重复执行无副作用；对"V2 已建普通复合键"的库也会自愈。
--
--  【执行方式】手工执行（应用启动不会自动跑本目录）：
--    mysql -h 127.0.0.1 -P 3307 -u guarantee -p guarantee_ai_admin \
--      < V3__logical_delete_functional_unique_keys.sql
--
--  【前置条件】必须先执行 V2（唯一键已存在），且已完成重复数据校验（任务书 §1.2）。
-- =====================================================================

SET NAMES utf8mb4;

-- sys_user.uk_sys_user_username: (username, deleted_at) -> (username, IFNULL(deleted_at, 哨兵))
SET @has_expr := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND INDEX_NAME = 'uk_sys_user_username'
     AND COLUMN_NAME IS NULL AND EXPRESSION IS NOT NULL);
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND INDEX_NAME = 'uk_sys_user_username');
SET @ddl := IF(@idx_exists > 0 AND @has_expr = 0,
  'ALTER TABLE sys_user DROP INDEX uk_sys_user_username', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;
SET @has_expr := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND INDEX_NAME = 'uk_sys_user_username'
     AND COLUMN_NAME IS NULL AND EXPRESSION IS NOT NULL);
SET @ddl := IF(@has_expr = 0,
  'ALTER TABLE sys_user ADD UNIQUE KEY uk_sys_user_username (username, (IFNULL(deleted_at, ''1970-01-01 00:00:00.000000'')))', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- sys_org.uk_sys_org_code: (org_code, deleted_at) -> (org_code, IFNULL(deleted_at, 哨兵))
SET @has_expr := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_org' AND INDEX_NAME = 'uk_sys_org_code'
     AND COLUMN_NAME IS NULL AND EXPRESSION IS NOT NULL);
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_org' AND INDEX_NAME = 'uk_sys_org_code');
SET @ddl := IF(@idx_exists > 0 AND @has_expr = 0,
  'ALTER TABLE sys_org DROP INDEX uk_sys_org_code', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;
SET @has_expr := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_org' AND INDEX_NAME = 'uk_sys_org_code'
     AND COLUMN_NAME IS NULL AND EXPRESSION IS NOT NULL);
SET @ddl := IF(@has_expr = 0,
  'ALTER TABLE sys_org ADD UNIQUE KEY uk_sys_org_code (org_code, (IFNULL(deleted_at, ''1970-01-01 00:00:00.000000'')))', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- sys_department.uk_sys_dept_code
SET @has_expr := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_department' AND INDEX_NAME = 'uk_sys_dept_code'
     AND COLUMN_NAME IS NULL AND EXPRESSION IS NOT NULL);
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_department' AND INDEX_NAME = 'uk_sys_dept_code');
SET @ddl := IF(@idx_exists > 0 AND @has_expr = 0,
  'ALTER TABLE sys_department DROP INDEX uk_sys_dept_code', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;
SET @has_expr := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_department' AND INDEX_NAME = 'uk_sys_dept_code'
     AND COLUMN_NAME IS NULL AND EXPRESSION IS NOT NULL);
SET @ddl := IF(@has_expr = 0,
  'ALTER TABLE sys_department ADD UNIQUE KEY uk_sys_dept_code (dept_code, (IFNULL(deleted_at, ''1970-01-01 00:00:00.000000'')))', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- sys_role.uk_sys_role_code
SET @has_expr := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_role' AND INDEX_NAME = 'uk_sys_role_code'
     AND COLUMN_NAME IS NULL AND EXPRESSION IS NOT NULL);
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_role' AND INDEX_NAME = 'uk_sys_role_code');
SET @ddl := IF(@idx_exists > 0 AND @has_expr = 0,
  'ALTER TABLE sys_role DROP INDEX uk_sys_role_code', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;
SET @has_expr := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_role' AND INDEX_NAME = 'uk_sys_role_code'
     AND COLUMN_NAME IS NULL AND EXPRESSION IS NOT NULL);
SET @ddl := IF(@has_expr = 0,
  'ALTER TABLE sys_role ADD UNIQUE KEY uk_sys_role_code (role_code, (IFNULL(deleted_at, ''1970-01-01 00:00:00.000000'')))', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- sys_permission.uk_sys_perm_code
SET @has_expr := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_permission' AND INDEX_NAME = 'uk_sys_perm_code'
     AND COLUMN_NAME IS NULL AND EXPRESSION IS NOT NULL);
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_permission' AND INDEX_NAME = 'uk_sys_perm_code');
SET @ddl := IF(@idx_exists > 0 AND @has_expr = 0,
  'ALTER TABLE sys_permission DROP INDEX uk_sys_perm_code', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;
SET @has_expr := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_permission' AND INDEX_NAME = 'uk_sys_perm_code'
     AND COLUMN_NAME IS NULL AND EXPRESSION IS NOT NULL);
SET @ddl := IF(@has_expr = 0,
  'ALTER TABLE sys_permission ADD UNIQUE KEY uk_sys_perm_code (perm_code, (IFNULL(deleted_at, ''1970-01-01 00:00:00.000000'')))', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- insurance_type.uk_insurance_type_code
SET @has_expr := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'insurance_type' AND INDEX_NAME = 'uk_insurance_type_code'
     AND COLUMN_NAME IS NULL AND EXPRESSION IS NOT NULL);
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'insurance_type' AND INDEX_NAME = 'uk_insurance_type_code');
SET @ddl := IF(@idx_exists > 0 AND @has_expr = 0,
  'ALTER TABLE insurance_type DROP INDEX uk_insurance_type_code', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;
SET @has_expr := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'insurance_type' AND INDEX_NAME = 'uk_insurance_type_code'
     AND COLUMN_NAME IS NULL AND EXPRESSION IS NOT NULL);
SET @ddl := IF(@has_expr = 0,
  'ALTER TABLE insurance_type ADD UNIQUE KEY uk_insurance_type_code (type_code, (IFNULL(deleted_at, ''1970-01-01 00:00:00.000000'')))', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- enterprise.uk_enterprise_code
SET @has_expr := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'enterprise' AND INDEX_NAME = 'uk_enterprise_code'
     AND COLUMN_NAME IS NULL AND EXPRESSION IS NOT NULL);
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'enterprise' AND INDEX_NAME = 'uk_enterprise_code');
SET @ddl := IF(@idx_exists > 0 AND @has_expr = 0,
  'ALTER TABLE enterprise DROP INDEX uk_enterprise_code', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;
SET @has_expr := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'enterprise' AND INDEX_NAME = 'uk_enterprise_code'
     AND COLUMN_NAME IS NULL AND EXPRESSION IS NOT NULL);
SET @ddl := IF(@has_expr = 0,
  'ALTER TABLE enterprise ADD UNIQUE KEY uk_enterprise_code (ent_code, (IFNULL(deleted_at, ''1970-01-01 00:00:00.000000'')))', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- enterprise.uk_enterprise_credit
SET @has_expr := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'enterprise' AND INDEX_NAME = 'uk_enterprise_credit'
     AND COLUMN_NAME IS NULL AND EXPRESSION IS NOT NULL);
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'enterprise' AND INDEX_NAME = 'uk_enterprise_credit');
SET @ddl := IF(@idx_exists > 0 AND @has_expr = 0,
  'ALTER TABLE enterprise DROP INDEX uk_enterprise_credit', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;
SET @has_expr := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'enterprise' AND INDEX_NAME = 'uk_enterprise_credit'
     AND COLUMN_NAME IS NULL AND EXPRESSION IS NOT NULL);
SET @ddl := IF(@has_expr = 0,
  'ALTER TABLE enterprise ADD UNIQUE KEY uk_enterprise_credit (credit_code, (IFNULL(deleted_at, ''1970-01-01 00:00:00.000000'')))', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- project.uk_project_code
SET @has_expr := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project' AND INDEX_NAME = 'uk_project_code'
     AND COLUMN_NAME IS NULL AND EXPRESSION IS NOT NULL);
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project' AND INDEX_NAME = 'uk_project_code');
SET @ddl := IF(@idx_exists > 0 AND @has_expr = 0,
  'ALTER TABLE project DROP INDEX uk_project_code', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;
SET @has_expr := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project' AND INDEX_NAME = 'uk_project_code'
     AND COLUMN_NAME IS NULL AND EXPRESSION IS NOT NULL);
SET @ddl := IF(@has_expr = 0,
  'ALTER TABLE project ADD UNIQUE KEY uk_project_code (project_code, (IFNULL(deleted_at, ''1970-01-01 00:00:00.000000'')))', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- tender_order.uk_tender_order_no（10 万行，索引重建需注意时长）
SET @has_expr := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tender_order' AND INDEX_NAME = 'uk_tender_order_no'
     AND COLUMN_NAME IS NULL AND EXPRESSION IS NOT NULL);
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tender_order' AND INDEX_NAME = 'uk_tender_order_no');
SET @ddl := IF(@idx_exists > 0 AND @has_expr = 0,
  'ALTER TABLE tender_order DROP INDEX uk_tender_order_no', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;
SET @has_expr := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tender_order' AND INDEX_NAME = 'uk_tender_order_no'
     AND COLUMN_NAME IS NULL AND EXPRESSION IS NOT NULL);
SET @ddl := IF(@has_expr = 0,
  'ALTER TABLE tender_order ADD UNIQUE KEY uk_tender_order_no (order_no, (IFNULL(deleted_at, ''1970-01-01 00:00:00.000000'')))', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- performance_order.uk_perf_order_no
SET @has_expr := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'performance_order' AND INDEX_NAME = 'uk_perf_order_no'
     AND COLUMN_NAME IS NULL AND EXPRESSION IS NOT NULL);
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'performance_order' AND INDEX_NAME = 'uk_perf_order_no');
SET @ddl := IF(@idx_exists > 0 AND @has_expr = 0,
  'ALTER TABLE performance_order DROP INDEX uk_perf_order_no', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;
SET @has_expr := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'performance_order' AND INDEX_NAME = 'uk_perf_order_no'
     AND COLUMN_NAME IS NULL AND EXPRESSION IS NOT NULL);
SET @ddl := IF(@has_expr = 0,
  'ALTER TABLE performance_order ADD UNIQUE KEY uk_perf_order_no (order_no, (IFNULL(deleted_at, ''1970-01-01 00:00:00.000000'')))', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- ai_conversation.uk_ai_conv_no
SET @has_expr := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_conversation' AND INDEX_NAME = 'uk_ai_conv_no'
     AND COLUMN_NAME IS NULL AND EXPRESSION IS NOT NULL);
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_conversation' AND INDEX_NAME = 'uk_ai_conv_no');
SET @ddl := IF(@idx_exists > 0 AND @has_expr = 0,
  'ALTER TABLE ai_conversation DROP INDEX uk_ai_conv_no', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;
SET @has_expr := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_conversation' AND INDEX_NAME = 'uk_ai_conv_no'
     AND COLUMN_NAME IS NULL AND EXPRESSION IS NOT NULL);
SET @ddl := IF(@has_expr = 0,
  'ALTER TABLE ai_conversation ADD UNIQUE KEY uk_ai_conv_no (conversation_no, (IFNULL(deleted_at, ''1970-01-01 00:00:00.000000'')))', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- ai_operation_proposal.uk_proposal_no
SET @has_expr := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_operation_proposal' AND INDEX_NAME = 'uk_proposal_no'
     AND COLUMN_NAME IS NULL AND EXPRESSION IS NOT NULL);
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_operation_proposal' AND INDEX_NAME = 'uk_proposal_no');
SET @ddl := IF(@idx_exists > 0 AND @has_expr = 0,
  'ALTER TABLE ai_operation_proposal DROP INDEX uk_proposal_no', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;
SET @has_expr := (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_operation_proposal' AND INDEX_NAME = 'uk_proposal_no'
     AND COLUMN_NAME IS NULL AND EXPRESSION IS NOT NULL);
SET @ddl := IF(@has_expr = 0,
  'ALTER TABLE ai_operation_proposal ADD UNIQUE KEY uk_proposal_no (proposal_no, (IFNULL(deleted_at, ''1970-01-01 00:00:00.000000'')))', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

-- ---------------------------------------------------------------------
--  自检：13 个唯一键必须全部含表达式分量（expr_parts 应为 13 行）
-- ---------------------------------------------------------------------
SELECT TABLE_NAME, INDEX_NAME, COUNT(*) AS expr_parts
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME IS NULL AND EXPRESSION IS NOT NULL
 GROUP BY TABLE_NAME, INDEX_NAME ORDER BY TABLE_NAME;

-- 同时确认：不再存在"普通列形态"的 13 个旧唯一键（应为 0 行）
SELECT TABLE_NAME, INDEX_NAME, COLUMN_NAME
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE()
   AND INDEX_NAME IN ('uk_sys_user_username','uk_sys_org_code','uk_sys_dept_code','uk_sys_role_code',
                      'uk_sys_perm_code','uk_insurance_type_code','uk_enterprise_code','uk_enterprise_credit',
                      'uk_project_code','uk_tender_order_no','uk_perf_order_no','uk_ai_conv_no','uk_proposal_no')
   AND COLUMN_NAME = 'deleted_at';
