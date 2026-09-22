-- ============================================================================
--  部门树改造迁移：把演示数据的部门结构改为
--      总部 → 业务部 / 财务部 / 人事部 / 行政部 / 技术部
--               业务部 → 杭州部 / 台州部 / 温州部
--               技术部 → 大数据部 / 系统部
--  并把 sys_user.dept_id 重新分配到新部门。
--
--  用法：pwsh -File scripts/migrate-dept-tree.ps1 -Apply
--
--  为什么用迁移而不是重跑 DataInitializer：DataInitializer 只在 sys_user 为空时执行，
--  开发库已有 300 用户与 15 万订单，重置会连带清掉它们。本迁移只动部门 + 用户的部门归属。
--
--  两个必须记住的 MySQL 限制（都实际踩过）：
--  ① **TEMPORARY 表不能在同一个语句里被引用两次**（报 `Can't reopen table`），
--     因此下面的映射表用普通表 + 前后显式清理；
--  ② `mysql --execute="... SOURCE f.sql"` 不解析 SOURCE 这类客户端命令，
--     会把整串按 ';' 拆开逐条发送 → 静默什么都不做。运行器改用标准输入喂脚本。
--
--  对应 Java 侧规格表：DataInitializer.DEPT_SPEC（两边必须一致）。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 0. 落表并核对前置条件
-- ---------------------------------------------------------------------------
SET @org_count := (SELECT COUNT(*) FROM sys_org WHERE is_deleted = 0);

DROP TABLE IF EXISTS _mig_dept;
DROP TABLE IF EXISTS _mig_user_map;

CREATE TABLE _mig_dept (
    org_id      BIGINT      NOT NULL,
    ordinal     INT         NOT NULL,
    dept_name   VARCHAR(64) NOT NULL,
    parent_name VARCHAR(64) NULL,
    short_code  VARCHAR(16) NOT NULL,
    new_id      BIGINT      NOT NULL,
    PRIMARY KEY (org_id, ordinal)
) ENGINE = InnoDB;

CREATE TABLE _mig_user_map (
    user_id  BIGINT NOT NULL,
    new_dept BIGINT NULL,
    PRIMARY KEY (user_id)
) ENGINE = InnoDB;

-- 规格表：机构 × 11 个部门（新部门 id 从 1001 起，避开旧 id 1~80）
INSERT INTO _mig_dept (org_id, ordinal, dept_name, parent_name, short_code, new_id)
SELECT o.id,
       s.ordinal,
       s.dept_name,
       s.parent_name,
       s.short_code,
       1000 + ROW_NUMBER() OVER (ORDER BY o.id, s.ordinal)
FROM sys_org o
JOIN (
              SELECT '总部'     AS dept_name,  1 AS ordinal, NULL     AS parent_name, 'HQ'       AS short_code
    UNION ALL SELECT '业务部',               2,             '总部',                'BIZ'
    UNION ALL SELECT '财务部',               3,             '总部',                'FIN'
    UNION ALL SELECT '人事部',               4,             '总部',                'HR'
    UNION ALL SELECT '行政部',               5,             '总部',                'ADM'
    UNION ALL SELECT '技术部',               6,             '总部',                'TECH'
    UNION ALL SELECT '杭州部',               7,             '业务部',              'BIZ-HZ'
    UNION ALL SELECT '台州部',               8,             '业务部',              'BIZ-TZ'
    UNION ALL SELECT '温州部',               9,             '业务部',              'BIZ-WZ'
    UNION ALL SELECT '大数据部',            10,             '技术部',              'TECH-BD'
    UNION ALL SELECT '系统部',              11,             '技术部',              'TECH-SYS'
) s
WHERE o.is_deleted = 0;

-- ---------------------------------------------------------------------------
-- 1. 用户 -> 新部门：旧序位（(旧 dept_id - 1) DIV 机构数，0 起）映射到新序位（1 起）
--    旧结构：每机构第 1 个部门为顶级、其余挂其下，用户原先分布在 4 个部门里
--    （NULL 的 dept_id 保持 NULL，例如 admin）
-- ---------------------------------------------------------------------------
INSERT INTO _mig_user_map (user_id, new_dept)
SELECT u.id, nd.new_id
FROM sys_user u
JOIN sys_department od ON od.id = u.dept_id AND od.is_deleted = 0
JOIN _mig_dept nd
  ON nd.org_id = od.org_id
 AND nd.ordinal = ((od.id - 1) DIV @org_count) + 1
WHERE u.is_deleted = 0;

-- ---------------------------------------------------------------------------
-- 2. 破坏性操作前的自检（只打印，不做判定）
--    ——判定交给运行器在**删除之前**用独立查询完成：
--    裸 SQL 里不能用 SIGNAL（那是存储程序的语法），
--    而"用一个不存在的列名触发报错"这种写法既脆弱又难读。
--    上一版没有这道关，曾出现"部门已删、新部门没插进去"的半残状态。
-- ---------------------------------------------------------------------------
SET @expect_depts := @org_count * 11;
SET @actual_depts := (SELECT COUNT(*) FROM _mig_dept);
SET @users_with_dept := (SELECT COUNT(*) FROM sys_user WHERE is_deleted = 0 AND dept_id IS NOT NULL);
SET @mapped_users := (SELECT COUNT(*) FROM _mig_user_map);

SELECT CONCAT('自检：机构=', @org_count, ' 期望部门=', @expect_depts, ' 映射表部门=', @actual_depts,
              ' 需映射用户=', @users_with_dept, ' 已映射=', @mapped_users) AS 自检;

-- 破坏性操作前的定点备份（部门表 + 用户的部门归属），万一后续失败可精确还原
DROP TABLE IF EXISTS _mig_backup_dept;
DROP TABLE IF EXISTS _mig_backup_user_dept;
CREATE TABLE _mig_backup_dept AS SELECT * FROM sys_department;
CREATE TABLE _mig_backup_user_dept AS SELECT id AS user_id, dept_id FROM sys_user;

-- ============================================================================
--  阶段 1 到此结束（**未改动任何业务数据**）。
--  阶段 2（换树 + 核对 + 清理）见 scripts/migrate-dept-tree-apply.sql，
--  由 scripts/migrate-dept-tree.ps1 在**校验下面这些计数之后**再调用：
--      期望 _mig_dept 行数     = 机构数 × 11
--      期望 _mig_user_map 行数 = 有效用户中有部门的行数
--  这道校验是关键：上一版把"自检"写成裸 SQL 里的 SIGNAL（非法语法），
--  结果删除执行了、插入没执行，留下"部门表空、用户 dept_id 全空"的半残状态。
-- ============================================================================
SELECT CONCAT('阶段1 完成：_mig_dept=', (SELECT COUNT(*) FROM _mig_dept),
              ' 行, _mig_user_map=', (SELECT COUNT(*) FROM _mig_user_map), ' 行') AS 阶段1结果;