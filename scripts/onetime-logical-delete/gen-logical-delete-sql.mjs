/**
 * 逻辑删除（is_deleted）数据库脚本生成器。
 *
 * 依据：docs/DEC-逻辑删除设计方案.md v2.1（§2.1 字段定义 / §3 唯一键改造 / §3.1 迁移模板）
 *
 * 为什么用生成器：
 *   18 张表 × 3 列 + 13 个唯一键的 DDL 完全同构，手写极易出现"某张表漏一列"
 *   这种很难自查的错误；且迁移脚本需要把 DDL 再嵌一层到单引号字符串里（幂等判断），
 *   引号转义手写几乎必错。生成器保证 18 张表一字不差地一致。
 *
 * 产物：
 *   1. guarantee-web/src/main/resources/db/schema.sql
 *        —— 同步 CREATE TABLE 定义（供空库自举）
 *   2. guarantee-web/src/main/resources/db/migration/V1__logical_delete.sql
 *        —— 存量库加列 + 索引（幂等）
 *   3. guarantee-web/src/main/resources/db/migration/V2__logical_delete_unique_keys.sql
 *        —— 存量库唯一键改造（幂等）
 *
 * 用法：node scripts/gen-logical-delete-sql.mjs
 */
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const schemaPath = join(root, 'guarantee-web/src/main/resources/db/schema.sql');
const migrationDir = join(root, 'guarantee-web/src/main/resources/db/migration');

/** 需要加逻辑删除字段的 18 张表（顺序与 schema.sql 一致）。 */
const TABLES = [
  'sys_org',
  'sys_department',
  'sys_user',
  'sys_role',
  'sys_permission',
  'sys_user_role',
  'sys_role_permission',
  'insurance_type',
  'enterprise',
  'project',
  'tender_order',
  'performance_order',
  'ai_conversation',
  'ai_message',
  'ai_tool_call',
  'ai_audit_log',
  'ai_operation_proposal',
  'ai_operation_audit',
];

/**
 * ai_operation_secret 是唯一例外（LD-EX-01）：不加这三个字段，保持物理删除。
 * 该表的唯一存在目的就是缩短敏感数据（手机号密文）的存储窗口，
 * 逻辑删除会让密文从 15 分钟驻留变成永久，直接违反 D-4 / SYS-A-02b。
 */
const EXCLUDED = ['ai_operation_secret'];

/** 13 个必须改造的业务唯一键：(表, 唯一键名, 业务键列)。 */
const UNIQUE_KEYS = [
  ['sys_user', 'uk_sys_user_username', 'username'],
  ['sys_org', 'uk_sys_org_code', 'org_code'],
  ['sys_department', 'uk_sys_dept_code', 'dept_code'],
  ['sys_role', 'uk_sys_role_code', 'role_code'],
  ['sys_permission', 'uk_sys_perm_code', 'perm_code'],
  ['insurance_type', 'uk_insurance_type_code', 'type_code'],
  ['enterprise', 'uk_enterprise_code', 'ent_code'],
  ['enterprise', 'uk_enterprise_credit', 'credit_code'],
  ['project', 'uk_project_code', 'project_code'],
  ['tender_order', 'uk_tender_order_no', 'order_no'],
  ['performance_order', 'uk_perf_order_no', 'order_no'],
  ['ai_conversation', 'uk_ai_conv_no', 'conversation_no'],
  ['ai_operation_proposal', 'uk_proposal_no', 'proposal_no'],
];

const indexName = (t) => `idx_${t}_deleted`;

/** schema.sql 中的列定义（缩进 4 空格，与文件风格一致）。 */
const SCHEMA_COLS = [
  `    is_deleted  TINYINT     NOT NULL DEFAULT 0    COMMENT '逻辑删除 0正常 1已删除',`,
  `    deleted_at  DATETIME(6) NULL     DEFAULT NULL COMMENT '删除时间（微秒精度，唯一键分量）',`,
  `    deleted_by  VARCHAR(64) NOT NULL DEFAULT 'DB' COMMENT '删除人：应用写 sys_user.id，直连为 DB',`,
];

/** CREATE TABLE 单行拼接形态（用于 ADD COLUMN 动态 DDL 的内部字符串）。 */
const COL_DEFS = [
  `ADD COLUMN is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0正常 1已删除'`,
  `ADD COLUMN deleted_at DATETIME(6) NULL DEFAULT NULL COMMENT '删除时间（微秒精度，唯一键分量）'`,
  `ADD COLUMN deleted_by VARCHAR(64) NOT NULL DEFAULT 'DB' COMMENT '删除人：应用写 sys_user.id，直连为 DB'`,
];

const HEADER = `-- =====================================================================
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
`;

function guard(table, columnList, ddl) {
  return `-- ${table}
SET @n_${table} := (SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '${table}'
                       AND COLUMN_NAME IN (${columnList.map((c) => `'${c}'`).join(', ')}));
SET @ddl := IF(@n_${table} = ${columnList.length}, 'DO 0',
  '${ddl.replace(/'/g, "''")}');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;
`;
}

// ---------------------------------------------------------------------
// 1. V1：加列 + 索引
// ---------------------------------------------------------------------
const v1 = [HEADER];
v1.push(`-- ---------------------------------------------------------------------
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

`);
for (const t of TABLES) {
  v1.push(guard(t, ['is_deleted', 'deleted_at', 'deleted_by'],
    `ALTER TABLE ${t} ${COL_DEFS.join(', ')}`));
  v1.push(`SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '${t}'
                        AND INDEX_NAME = '${indexName(t)}');
SET @ddl := IF(@has_idx = 0, 'ALTER TABLE ${t} ADD INDEX ${indexName(t)} (is_deleted)', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;
`);
}

v1.push(`-- ---------------------------------------------------------------------
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
`);

// ---------------------------------------------------------------------
// 2. V2：13 个唯一键改造
// ---------------------------------------------------------------------
const v2 = [HEADER];
v2.push(`-- ---------------------------------------------------------------------
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

`);
for (const [t, uk, col] of UNIQUE_KEYS) {
  v2.push(`-- ${t}.${uk}: (${col}) -> (${col}, deleted_at)
SET @cols_before := (SELECT COUNT(*) FROM information_schema.STATISTICS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '${t}' AND INDEX_NAME = '${uk}');
SET @cols_after := (SELECT COUNT(*) FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '${t}' AND INDEX_NAME = '${uk}'
                       AND COLUMN_NAME = 'deleted_at');
SET @ddl := IF(@cols_before > 0 AND @cols_after = 0,
  'ALTER TABLE ${t} DROP INDEX ${uk}', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;

SET @cols_after := (SELECT COUNT(*) FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '${t}' AND INDEX_NAME = '${uk}'
                       AND COLUMN_NAME = 'deleted_at');
SET @ddl := IF(@cols_after = 0,
  'ALTER TABLE ${t} ADD UNIQUE KEY ${uk} (${col}, deleted_at)', 'DO 0');
PREPARE ld_stmt FROM @ddl; EXECUTE ld_stmt; DEALLOCATE PREPARE ld_stmt;
`);
}

v2.push(`-- ---------------------------------------------------------------------
--  自检 1：13 个唯一键都必须恰好由 2 列组成，且其中一列是 deleted_at
--  期望：每个 key 返回 (2, 1)
-- ---------------------------------------------------------------------
SELECT TABLE_NAME, INDEX_NAME, COUNT(*) AS col_cnt,
       SUM(COLUMN_NAME = 'deleted_at') AS has_deleted_at
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND NON_UNIQUE = 0
  AND INDEX_NAME IN (${UNIQUE_KEYS.map(([, uk]) => `'${uk}'`).join(', ')})
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
`);

// ---------------------------------------------------------------------
// 3. schema.sql 变换
// ---------------------------------------------------------------------
function transformSchema(sql) {
  const lines = sql.split(/\r?\n/);
  const out = [];
  let current = null;
  const ukMap = new Map(UNIQUE_KEYS.map(([t, uk, col]) => [uk, { t, col }]));
  for (const line of lines) {
    const create = line.match(/^CREATE TABLE IF NOT EXISTS (\w+) \(/);
    if (create) current = create[1];
    // 唯一键改造
    // 注意：唯一键可能是列区的最后一行，此时没有结尾逗号
    const uk = line.match(/^(\s*)UNIQUE KEY (\w+) \((\w+)\)(,?)\s*$/);
    if (uk && ukMap.has(uk[2])) {
      out.push(`${uk[1]}UNIQUE KEY ${uk[2]} (${uk[3]}, deleted_at)${uk[4]}`);
      continue;
    }
    // 主键前插入三列
    if (current && TABLES.includes(current) && /^\s*PRIMARY KEY \(/.test(line)) {
      out.push(...SCHEMA_COLS);
    }
    out.push(line);
    // 主键后插入 is_deleted 索引
    if (current && TABLES.includes(current) && /^\s*PRIMARY KEY \(.*\),\s*$/.test(line)) {
      out.push(`    KEY ${indexName(current)} (is_deleted),`);
      current = null; // 一张表的列区只处理一次
    }
  }
  return out.join('\n');
}

// ---------------------------------------------------------------------
const original = readFileSync(schemaPath, 'utf8');
const transformed = transformSchema(original);
writeFileSync(schemaPath, transformed, 'utf8');
mkdirSync(migrationDir, { recursive: true });
writeFileSync(join(migrationDir, 'V1__logical_delete.sql'), v1.join('\n'), 'utf8');
writeFileSync(join(migrationDir, 'V2__logical_delete_unique_keys.sql'), v2.join('\n'), 'utf8');

// ---------------------------------------------------------------------
// 生成后自检（生成器自身的断言，保证"18 张表一个不落"）
// ---------------------------------------------------------------------
const countCols = (transformed.match(/is_deleted\s+TINYINT/g) || []).length;
const countIdx = (transformed.match(/KEY idx_\w+_deleted \(is_deleted\)/g) || []).length;
const countUk = (transformed.match(/UNIQUE KEY \w+ \(\w+, deleted_at\)/g) || []).length;
console.log(`schema.sql: is_deleted 列 ${countCols} 处（期望 18）`);
console.log(`schema.sql: idx_*_deleted 索引 ${countIdx} 处（期望 18）`);
console.log(`schema.sql: 复合唯一键 ${countUk} 处（期望 13）`);
if (countCols !== TABLES.length || countIdx !== TABLES.length || countUk !== UNIQUE_KEYS.length) {
  console.error('生成结果与预期不符，请检查 schema.sql 结构');
  process.exit(1);
}
// ai_operation_secret 必须仍然没有任何逻辑删除字段
const secretBlock = transformed.split('CREATE TABLE IF NOT EXISTS ai_operation_secret')[1] ?? '';
if (/is_deleted|deleted_at|deleted_by/.test(secretBlock.split(';')[0])) {
  console.error('ai_operation_secret 不应包含逻辑删除字段（LD-EX-01）');
  process.exit(1);
}
console.log(`排除表校验通过：${EXCLUDED.join(', ')} 未加字段`);
