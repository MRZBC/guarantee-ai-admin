-- ============================================================================
--  ⛔ 历史留存 · 已执行完毕 · 勿再执行（DO NOT RUN AGAIN）
--
--  本脚本引用的 sys_user.org_id / sys_department.org_id 两列**已被 V4 迁移删除**
--  （guarantee-web/src/main/resources/db/migration/V4__drop_org_from_user_and_dept.sql，
--   依据 docs/PLAN-移除用户与部门的机构归属.md 阶段一），
--  现在再执行必然报 "Unknown column 'org_id' in ..."；而且它开头的
--  `UPDATE sys_user SET dept_id = NULL` 在 dept_id 已收紧为 NOT NULL 之后会直接失败。
--
--  它是一次性迁移脚本的阶段 2（破坏性部分），已执行完毕并成为现状；
--  保留此文件仅为记录当时的迁移口径，**不要重跑，也不要按它改逻辑**（改逻辑会掩盖历史）。
--
--  阶段一之后的结构校验改用：pwsh -File scripts/verify-no-org-on-user-dept.ps1
-- ============================================================================
-- ============================================================================
--  部门树改造迁移 · 阶段 2（破坏性部分）
--
--  由 migrate-dept-tree.ps1 在**校验阶段 1 的映射结果之后**调用，不要手工单独执行。
--  阶段 1：scripts/migrate-dept-tree-prepare.sql（建映射表 + 定点备份，不改业务数据）
--
--  做三件事：置空 dept_id → 删旧部门 → 插新部门 + 回写用户 dept_id。
--  完成后留下 _mig_backup_dept / _mig_backup_user_dept 供回滚。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 换树
-- ---------------------------------------------------------------------------
UPDATE sys_user SET dept_id = NULL WHERE is_deleted = 0;

DELETE FROM sys_department;

INSERT INTO sys_department (id, dept_code, dept_name, org_id, parent_id, status, sort_no)
SELECT m.new_id,
       CONCAT(o.org_code, '-', m.short_code),
       m.dept_name,
       m.org_id,
       COALESCE(p.new_id, 0),
       1,
       m.ordinal
FROM _mig_dept m
JOIN sys_org o ON o.id = m.org_id
LEFT JOIN _mig_dept p ON p.org_id = m.org_id AND p.dept_name = m.parent_name
ORDER BY m.new_id;

UPDATE sys_user u
JOIN _mig_user_map m ON m.user_id = u.id
SET u.dept_id = m.new_dept;

-- ---------------------------------------------------------------------------
-- 收尾修正：消除"用户机构 ≠ 部门机构"的悬挂
--
-- 这不是迁移引入的问题，而是**既有**的数据不一致：DataInitializer 为覆盖数据范围场景，
-- 把 operator/analyst/user0004 的 org_id 显式改成了省级/市级机构，
-- 但 dept_id 仍按"原机构 + 序号"算出来，于是落到别的机构名下。
-- 页面上看不出来，但按机构统计部门人数会错位。Java 侧已同步修复（pickDeptId）。
-- ---------------------------------------------------------------------------
UPDATE sys_user u
JOIN (
    SELECT u2.id AS uid, (
        SELECT d.id FROM sys_department d
        WHERE d.org_id = u2.org_id AND d.is_deleted = 0
        ORDER BY d.sort_no LIMIT 1 OFFSET 1
    ) AS new_dept
    FROM sys_user u2
    LEFT JOIN sys_department d2 ON d2.id = u2.dept_id
    WHERE u2.is_deleted = 0 AND u2.id <> 1
      AND (u2.dept_id IS NULL OR d2.org_id <> u2.org_id)
) fix ON fix.uid = u.id
SET u.dept_id = fix.new_dept;
-- ---------------------------------------------------------------------------
-- 结果核对
-- ---------------------------------------------------------------------------
SELECT '部门总数'                          AS 指标, COUNT(*) AS 值 FROM sys_department WHERE is_deleted = 0
UNION ALL SELECT '顶级部门数(应=机构数)',   COUNT(*) FROM sys_department WHERE is_deleted = 0 AND parent_id = 0
UNION ALL SELECT '机构数',                 COUNT(*) FROM sys_org WHERE is_deleted = 0
UNION ALL SELECT '每机构部门数(应恒为11)',  MIN(cnt) FROM (
    SELECT COUNT(*) AS cnt FROM sys_department WHERE is_deleted = 0 GROUP BY org_id) t
UNION ALL SELECT '有效用户数',             COUNT(*) FROM sys_user WHERE is_deleted = 0
UNION ALL SELECT '用户部门为空(应=1)',     COUNT(*) FROM sys_user WHERE is_deleted = 0 AND dept_id IS NULL
UNION ALL SELECT '用户部门指向不存在部门(应=0)', COUNT(*) FROM sys_user u
    WHERE u.is_deleted = 0 AND u.dept_id IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM sys_department d WHERE d.id = u.dept_id AND d.is_deleted = 0)
UNION ALL SELECT '用户跨机构挂部门(应=0)', COUNT(*) FROM sys_user u
    JOIN sys_department d ON d.id = u.dept_id
    WHERE u.is_deleted = 0 AND d.org_id <> u.org_id;

SELECT d.dept_name AS 部门, d.dept_code AS 编码, IFNULL(p.dept_name, '(顶级)') AS 上级,
       d.sort_no AS 排序,
       (SELECT COUNT(*) FROM sys_user u WHERE u.dept_id = d.id AND u.is_deleted = 0) AS 人数
FROM sys_department d
LEFT JOIN sys_department p ON p.id = d.parent_id
WHERE d.org_id = 1 AND d.is_deleted = 0
ORDER BY d.sort_no;

-- ---------------------------------------------------------------------------
-- 清理映射表与定点备份
--
-- 定点备份**必须删掉**：它们由 CREATE TABLE AS SELECT 生成、带 is_deleted 列，
-- 会被 LogicalDeleteSchemaIntegrationTest 的"受管表必须恰好 18 张"判据当成业务表而失败。
-- 回滚手段是全库备份文件（migrate-dept-tree.ps1 在动手前自动生成），不需要留在库里。
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS _mig_user_map;
DROP TABLE IF EXISTS _mig_dept;
DROP TABLE IF EXISTS _mig_backup_dept;
DROP TABLE IF EXISTS _mig_backup_user_dept;
SELECT '迁移结束：映射表与定点备份已清理' AS 提示;
