-- =====================================================================
--  移除用户与部门的「机构」归属 迁移脚本（V4）
--
--  依据：docs/PLAN-移除用户与部门的机构归属.md（阶段一）
--
--  领域模型：
--    机构(sys_org) = 外部出函机构，服务于**订单**（订单表自带 org_id/region_code）
--    部门(sys_department) = 内部组织单元，服务于**人**
--    用户 → 部门（唯一归属），用户与部门都不再挂机构
--
--  幂等实现说明（与 V1 一致）：
--    MySQL 8.0 不支持 DROP COLUMN IF EXISTS / MODIFY IF NOT EXISTS，
--    因此每个 DDL 都用 information_schema 判断 + PREPARE/EXECUTE 动态执行。
--    这不是存储过程：PREPARE 是会话级语句，不会在库里留下任何数据库对象。
--
--  执行方式（手工执行，应用启动不会自动跑本目录）：
--    mysql -h 127.0.0.1 -P 3307 -u guarantee -p guarantee_ai_admin < <本文件>
--
--  ⚠ 执行前请务必备份：
--    mysqldump -h 127.0.0.1 -P 3307 -u guarantee -p guarantee_ai_admin > backup.sql
--
--  本脚本对"已执行过"的库可安全重复执行（全部 DDL 均带存在性判断）。
-- =====================================================================


-- =====================================================================
--  前置校验：任一不满足则**中止脚本**（用查询不存在的表制造报错）
--  这是刻意的 fail-fast：宁可脚本报错，也不要带着脏数据把 org_id 删掉。
-- =====================================================================

-- (a) dept_id 不能为空
SET @ddl := IF((SELECT COUNT(*) FROM sys_user WHERE is_deleted = 0 AND dept_id IS NULL) = 0,
               'DO 0',
               'SELECT * FROM __V4_ABORT_sys_user_has_null_dept_id__');
PREPARE v4_stmt FROM @ddl; EXECUTE v4_stmt; DEALLOCATE PREPARE v4_stmt;

-- (b) dept_id 必须指向存在且未删除的部门
SET @ddl := IF((SELECT COUNT(*) FROM sys_user u
                  LEFT JOIN sys_department d ON d.id = u.dept_id
                 WHERE u.is_deleted = 0 AND (d.id IS NULL OR d.is_deleted = 1)) = 0,
               'DO 0',
               'SELECT * FROM __V4_ABORT_sys_user_dept_missing_or_deleted__');
PREPARE v4_stmt FROM @ddl; EXECUTE v4_stmt; DEALLOCATE PREPARE v4_stmt;

-- (c) 用户机构必须与其部门所属机构一致
--     仅当两列都还在时才有意义（重复执行时列已删，跳过）
SET @has_user_org := (SELECT COUNT(*) FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user'
                         AND COLUMN_NAME = 'org_id');
SET @has_dept_org := (SELECT COUNT(*) FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_department'
                         AND COLUMN_NAME = 'org_id');

SET @chk := IF(@has_user_org = 1 AND @has_dept_org = 1,
  'SET @v4_bad := (SELECT COUNT(*) FROM sys_user u JOIN sys_department d ON d.id = u.dept_id WHERE u.is_deleted = 0 AND d.org_id <> u.org_id)',
  'SET @v4_bad := 0');
PREPARE v4_stmt FROM @chk; EXECUTE v4_stmt; DEALLOCATE PREPARE v4_stmt;

SET @ddl := IF(@v4_bad = 0,
               'DO 0',
               'SELECT * FROM __V4_ABORT_sys_user_org_id_mismatch_dept_org_id__');
PREPARE v4_stmt FROM @ddl; EXECUTE v4_stmt; DEALLOCATE PREPARE v4_stmt;


-- =====================================================================
--  批次 1：sys_user —— 去掉 org_id，dept_id 收紧为 NOT NULL
-- =====================================================================

-- 1.1 先删索引（org_id 上的索引；不先删，列删掉后索引名仍会残留判断）
SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user'
                    AND INDEX_NAME = 'idx_sys_user_org');
SET @ddl := IF(@has_idx = 0, 'DO 0', 'ALTER TABLE sys_user DROP INDEX idx_sys_user_org');
PREPARE v4_stmt FROM @ddl; EXECUTE v4_stmt; DEALLOCATE PREPARE v4_stmt;

-- 1.2 dept_id 收紧为 NOT NULL（用户必须属于一个部门）
SET @dept_nullable := (SELECT IS_NULLABLE FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user'
                          AND COLUMN_NAME = 'dept_id');
SET @ddl := IF(@dept_nullable = 'NO', 'DO 0',
  'ALTER TABLE sys_user MODIFY COLUMN dept_id BIGINT NOT NULL COMMENT ''所属部门''');
PREPARE v4_stmt FROM @ddl; EXECUTE v4_stmt; DEALLOCATE PREPARE v4_stmt;

-- 1.3 删列
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user'
                    AND COLUMN_NAME = 'org_id');
SET @ddl := IF(@has_col = 0, 'DO 0', 'ALTER TABLE sys_user DROP COLUMN org_id');
PREPARE v4_stmt FROM @ddl; EXECUTE v4_stmt; DEALLOCATE PREPARE v4_stmt;


-- =====================================================================
--  批次 2：sys_department —— 去掉 org_id
-- =====================================================================

-- 2.1 先删索引
SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_department'
                    AND INDEX_NAME = 'idx_sys_dept_org');
SET @ddl := IF(@has_idx = 0, 'DO 0', 'ALTER TABLE sys_department DROP INDEX idx_sys_dept_org');
PREPARE v4_stmt FROM @ddl; EXECUTE v4_stmt; DEALLOCATE PREPARE v4_stmt;

-- 2.2 删列
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_department'
                    AND COLUMN_NAME = 'org_id');
SET @ddl := IF(@has_col = 0, 'DO 0', 'ALTER TABLE sys_department DROP COLUMN org_id');
PREPARE v4_stmt FROM @ddl; EXECUTE v4_stmt; DEALLOCATE PREPARE v4_stmt;


-- =====================================================================
--  核对（应全部为 0 / NO，且两列均已不存在）
-- =====================================================================
SELECT '用户无部门(应=0)'            AS 指标, COUNT(*) AS 值 FROM sys_user WHERE is_deleted = 0 AND dept_id IS NULL
UNION ALL SELECT '部门指向不存在(应=0)', COUNT(*) FROM sys_user u
    LEFT JOIN sys_department d ON d.id = u.dept_id
    WHERE u.is_deleted = 0 AND (d.id IS NULL OR d.is_deleted = 1)
UNION ALL SELECT 'sys_user.org_id 仍存在(应=0)', COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'org_id'
UNION ALL SELECT 'sys_department.org_id 仍存在(应=0)', COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_department' AND COLUMN_NAME = 'org_id'
UNION ALL SELECT 'sys_user.dept_id 可空(应=NO)', COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'dept_id'
      AND IS_NULLABLE = 'YES';
