-- =====================================================================
--  删除 ai_operation_audit.operator_org_id 迁移脚本（V5）
--
--  依据：docs/PLAN-移除用户与部门的机构归属.md §8 Q3 / C8
--        决策：用户已确认**删掉该列**。
--
--  领域模型：
--    机构(sys_org) = 外部出函机构，服务于**订单**（订单表自带 org_id/region_code）
--    用户 → 部门（唯一归属）；用户与部门都不再挂机构
--    因此「操作人机构」没有任何数据来源
--
--  为什么要删而不是留着恒空：
--    一张审计表上留一列永远为 NULL 的"机构"，读审计的人会以为存在一个
--    可用的机构数据范围，而它从来不会有值——这比没有这一列更容易误导。
--
--  ⚠️ 执行顺序（很重要，顺序错了会立刻炸）：
--    1) 先部署**不再引用该列**的后端（本仓库当前代码即是），并**重启**；
--    2) 再执行本脚本。
--    反过来做，正在运行的旧进程（SQL 里仍带 operator_org_id——本仓库改动前
--    的 AiOperationAuditMapper.xml 的 cols / insert 都含该列）会立刻报
--    "Unknown column 'operator_org_id'"，表现为所有审计读写失败。
--    在列被删之前，新代码照常工作：INSERT 不再提及该列，它保持 NULL。
--
--  ⚠️ 本脚本会**丢弃历史值**：
--    该列在旧版后端里写过真实值（本库实测 601 行有值，全部是旧构建写入的；
--    最新一行为 NULL，说明写入侧确实已切换）。删列即丢掉这些行上的机构归属。
--    因此脚本默认**中止**，需要你先备份并显式确认（见下面的 @v5_ack_history_loss）。
--
--  幂等实现说明（与 V1 / V4 一致）：
--    MySQL 8.0 不支持 DROP COLUMN IF EXISTS，因此 DDL 用 information_schema
--    判断 + PREPARE/EXECUTE 动态执行。这不是存储过程：PREPARE 是会话级语句，
--    不会在库里留下任何数据库对象。本脚本可重复执行（第二次跑时列已不存在，
--    所有校验与 DDL 自动跳过）。
--
--  执行方式（手工执行，应用启动不会自动跑本目录）：
--    mysql -h 127.0.0.1 -P 3307 -u guarantee -p guarantee_ai_admin < <本文件>
--    （或登录后在客户端内 `source /绝对路径/V5__drop_operator_org_id.sql`）
--
--  ⚠️ 执行前请务必备份：
--    mysqldump -h 127.0.0.1 -P 3307 -u guarantee -p guarantee_ai_admin > backup.sql
-- =====================================================================


-- =====================================================================
--  人工确认开关（唯一的"需要你手动改"的地方）
--
--   0（默认）：只要该列还有非 NULL 值 → **中止**，不删任何东西。
--             这是刻意的：那 601 行上的机构归属会随列一起消失，
--             必须由人确认"已备份 / 可以丢弃"。
--   1        ：确认丢弃历史值，脚本继续执行 DDL。
--
--  该列已不存在时（重复执行）无论这里填什么都会正常跳过。
-- =====================================================================
SET @v5_ack_history_loss := 0;


-- =====================================================================
--  前置校验：任一不满足则**中止脚本**（用查询不存在的表制造报错）
--  这是刻意的 fail-fast：宁可脚本报错，也不要带着脏数据/错误顺序把列删掉。
-- =====================================================================

-- 该列是否还存在（重复执行时为 0，后续校验与 DDL 都据此跳过）
SET @v5_has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE()
                       AND TABLE_NAME = 'ai_operation_audit'
                       AND COLUMN_NAME = 'operator_org_id');

-- (0) 审计表必须存在。表不存在说明连基础 schema 都没建，
--     此时继续执行只会得到一句难懂的报错，不如在这里明确中止。
SET @v5_has_table := (SELECT COUNT(*) FROM information_schema.TABLES
                       WHERE TABLE_SCHEMA = DATABASE()
                         AND TABLE_NAME = 'ai_operation_audit');
SET @v5_ddl := IF(@v5_has_table = 1,
  'DO 0',
  'SELECT * FROM __V5_ABORT_ai_operation_audit_table_missing__');
PREPARE v5_stmt FROM @v5_ddl; EXECUTE v5_stmt; DEALLOCATE PREPARE v5_stmt;

-- (1) 写入侧必须已经切换：**最新**一条审计行的 operator_org_id 必须是 NULL。
--     若最新一行仍有值，说明仍有旧版后端在写机构值（部署顺序错了）——
--     此时删列会先丢新数据，必须先重启后端再来。
--     表中一行都没有时跳过（无法判断，也不构成阻塞）。
SET @v5_latest_has_value := 0;
SET @v5_row_count := (SELECT COUNT(*) FROM ai_operation_audit);
SET @v5_chk := IF(@v5_has_col = 0 OR @v5_row_count = 0,
  'SET @v5_latest_has_value := 0',
  'SET @v5_latest_has_value := (SELECT COUNT(*) FROM (SELECT operator_org_id FROM ai_operation_audit ORDER BY operated_at DESC, id DESC LIMIT 1) t WHERE t.operator_org_id IS NOT NULL)');
PREPARE v5_stmt FROM @v5_chk; EXECUTE v5_stmt; DEALLOCATE PREPARE v5_stmt;

SET @v5_ddl := IF(@v5_latest_has_value = 0,
  'DO 0',
  'SELECT * FROM __V5_ABORT_latest_row_has_org_id_restart_first__');
PREPARE v5_stmt FROM @v5_ddl; EXECUTE v5_stmt; DEALLOCATE PREPARE v5_stmt;

-- (2) 历史值确认：统计该列还有多少非 NULL 值，未确认则中止。
--     列已不存在时直接置 0（重复执行不会被这一步挡住）。
SET @v5_non_null := 0;
SET @v5_chk := IF(@v5_has_col = 0,
  'SET @v5_non_null := 0',
  'SET @v5_non_null := (SELECT COUNT(*) FROM ai_operation_audit WHERE operator_org_id IS NOT NULL)');
PREPARE v5_stmt FROM @v5_chk; EXECUTE v5_stmt; DEALLOCATE PREPARE v5_stmt;

SELECT CONCAT('ai_operation_audit 共 ', @v5_row_count, ' 行，其中 operator_org_id 非 NULL ',
              @v5_non_null, ' 行；@v5_ack_history_loss = ', @v5_ack_history_loss) AS 前置校验;

SET @v5_ddl := IF(@v5_non_null = 0 OR @v5_ack_history_loss = 1,
  'DO 0',
  'SELECT * FROM __V5_ABORT_history_values_would_be_lost__');
PREPARE v5_stmt FROM @v5_ddl; EXECUTE v5_stmt; DEALLOCATE PREPARE v5_stmt;


-- =====================================================================
--  DDL：先删单列索引（若有），再删列
-- =====================================================================

-- 3.1 防御性处理：如果某个索引**只**由 operator_org_id 一列构成，先删该索引。
--     当前 schema 里该列没有索引（见 db/schema.sql），这里保留与 V4 一致的写法，
--     以便在有人自行加过索引的库上也能一次跑通。
--     若该列只是复合索引的一部分，MySQL 的 DROP COLUMN 会自动把它从索引里去掉，
--     不需要（也不能）单独删索引。
SET @v5_idx := (SELECT INDEX_NAME FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'ai_operation_audit'
                   AND COLUMN_NAME = 'operator_org_id'
                   AND INDEX_NAME <> 'PRIMARY'
                 GROUP BY INDEX_NAME
                 HAVING COUNT(*) = 1
                 LIMIT 1);

SET @v5_ddl := IF(@v5_idx IS NULL,
  'DO 0',
  CONCAT('ALTER TABLE ai_operation_audit DROP INDEX `', @v5_idx, '`'));
PREPARE v5_stmt FROM @v5_ddl; EXECUTE v5_stmt; DEALLOCATE PREPARE v5_stmt;

-- 3.2 删列（幂等：列不存在时什么都不做）
SET @v5_ddl := IF(@v5_has_col = 0,
  'DO 0',
  'ALTER TABLE ai_operation_audit DROP COLUMN operator_org_id');
PREPARE v5_stmt FROM @v5_ddl; EXECUTE v5_stmt; DEALLOCATE PREPARE v5_stmt;


-- =====================================================================
--  核对（应全部为 0）
--  注意：这里**不能**再 SELECT operator_org_id 去数它，只能用 information_schema，
--  否则重复执行时脚本会在最后一步因为"列不存在"而报错。
-- =====================================================================
SELECT 'ai_operation_audit.operator_org_id 仍存在(应=0)' AS 指标, COUNT(*) AS 值
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_operation_audit'
      AND COLUMN_NAME = 'operator_org_id'
UNION ALL
SELECT '该列上的单列索引仍存在(应=0)', COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_operation_audit'
      AND COLUMN_NAME = 'operator_org_id'
UNION ALL
SELECT '审计表行数（删列不改行数，应等于前置校验里的行数）', COUNT(*)
    FROM ai_operation_audit;
