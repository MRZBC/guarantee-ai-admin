-- =====================================================================
--  用户「首次登录强制改密」迁移脚本（P-10 / D1=C）
--
--  依据：docs/REQ-用户管理新增与修改.md §5.2
--
--  背景：
--    新建账号与管理员重置密码都写入**固定默认密码**，并把 must_change_password 置 1，
--    要求该用户下次登录必须先改密才能使用系统（服务端强制闸门，见 §5.4）。
--
--  ⚠️ 本仓库**没有 Flyway**：db/migration/V1~V6 全部是**手工执行的幂等脚本**，
--     应用启动不会自动跑本目录。**上线前必须手工执行本文件**，否则所有读到
--     must_change_password 的 SQL 会直接报 Unknown column。
--
--  幂等实现说明（与 V1~V5 同款）：
--    MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS，因此用 information_schema 判断 +
--    PREPARE/EXECUTE 动态执行。这不是存储过程：PREPARE 是会话级语句，
--    不会在库里留下任何数据库对象。
--
--  存量数据的取值：
--    默认 0（不强制）。即**既有账号不受影响**，只有本脚本执行之后新建/被重置的账号
--    才会置 1。若希望对既有演示账号也走一次强制改密，需另行决策（不建议：会让所有
--    既有用户被踢一次）。
--
--  执行方式（手工执行）：
--    mysql -h 127.0.0.1 -P 3307 -u guarantee -p guarantee_ai_admin < <本文件>
--
--  回滚（一般不需要；保留供演练）：
--    ALTER TABLE sys_user DROP COLUMN must_change_password;
-- =====================================================================

-- sys_user.must_change_password
SET @n_sys_user_mcp := (SELECT COUNT(*) FROM information_schema.COLUMNS
                          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user'
                            AND COLUMN_NAME = 'must_change_password');
SET @ddl := IF(@n_sys_user_mcp = 1, 'DO 0',
  'ALTER TABLE sys_user ADD COLUMN must_change_password TINYINT NOT NULL DEFAULT 0 COMMENT ''首次登录强制改密 1是 0否''');
PREPARE mcp_stmt FROM @ddl; EXECUTE mcp_stmt; DEALLOCATE PREPARE mcp_stmt;

-- 复核（应输出 1）
SELECT COUNT(*) AS must_change_password_column_present
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user'
  AND COLUMN_NAME = 'must_change_password';
