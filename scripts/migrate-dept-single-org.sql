-- ============================================================================
--  部门树收敛为单一机构（ORGHQ）迁移
--
--  目标：只保留 ORGHQ 那一棵树（总部 + 5 个一级 + 5 个二级 = 11 个部门），
--        删掉其余 20 个机构的 220 个部门；
--        300 个用户全部改为归属 ORGHQ（org_id=1），并分配到这 11 个部门。
--
--  用法：pwsh -File scripts/migrate-dept-single-org.ps1 -Apply
--
--  ⚠ 已知后果（评审已确认接受）：operator（省级）/ analyst（市级）/ user0004 的
--    数据范围演示随之失效——他们的 org_id 都会变成总部，所见范围与 admin 相同。
--
--  回滚：运行器在动手前自动生成全库备份（%TEMP%\guarantee_before_single_org_*.sql）。
-- ============================================================================

SET @hq_org_id := (SELECT id FROM sys_org WHERE org_code = 'ORGHQ' AND is_deleted = 0);
SET @hq_depts := (SELECT COUNT(*) FROM sys_department WHERE org_id = @hq_org_id AND is_deleted = 0);

SELECT CONCAT('目标机构 ORGHQ id=', @hq_org_id, '，其下部门数=', @hq_depts) AS 前置;

-- ---------------------------------------------------------------------------
-- 1. 删掉非 ORGHQ 的部门（先置空引用，避免悬挂）
-- ---------------------------------------------------------------------------
UPDATE sys_user SET dept_id = NULL
WHERE is_deleted = 0 AND dept_id IS NOT NULL
  AND dept_id NOT IN (SELECT id FROM sys_department WHERE org_id = @hq_org_id AND is_deleted = 0);

DELETE FROM sys_department WHERE org_id <> @hq_org_id;

-- ---------------------------------------------------------------------------
-- 2. 300 个用户全部归属 ORGHQ，并铺满 11 个部门
--    分配口径：用户按机构内 id 序号、部门按 sort_no 序号，取模对齐。
--    不要用 `LIMIT 1 OFFSET <表达式>`——MySQL 的 LIMIT 不接受表达式参数（踩过），
--    改用窗口函数 ROW_NUMBER() 预编号。
-- ---------------------------------------------------------------------------
UPDATE sys_user SET org_id = @hq_org_id WHERE is_deleted = 0;

UPDATE sys_user u
JOIN (
    WITH dept_no AS (
        SELECT id,
               ROW_NUMBER() OVER (ORDER BY sort_no) AS dno,
               COUNT(*) OVER () AS dtotal
        FROM sys_department
        WHERE org_id = @hq_org_id AND is_deleted = 0
    ),
    user_no AS (
        SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS uno
        FROM sys_user WHERE is_deleted = 0
    )
    SELECT un.id AS uid, d.id AS new_dept
    FROM user_no un
    JOIN dept_no d ON d.dno = 1 + ((un.uno - 1) % d.dtotal)
) m ON m.uid = u.id
SET u.dept_id = m.new_dept;

-- ---------------------------------------------------------------------------
-- 3. 核对
-- ---------------------------------------------------------------------------
SELECT '部门总数(应=11)'            AS 指标, COUNT(*) AS 值 FROM sys_department WHERE is_deleted = 0
UNION ALL SELECT '涉及机构数(应=1)', COUNT(DISTINCT org_id) FROM sys_department WHERE is_deleted = 0
UNION ALL SELECT '机构数(应=21，未动)', COUNT(*) FROM sys_org WHERE is_deleted = 0
UNION ALL SELECT '有效用户数(应=300)', COUNT(*) FROM sys_user WHERE is_deleted = 0
UNION ALL SELECT '用户归属机构数(应=1)', COUNT(DISTINCT org_id) FROM sys_user WHERE is_deleted = 0
UNION ALL SELECT '用户挂空部门(应=0)', COUNT(*) FROM sys_user WHERE is_deleted = 0 AND dept_id IS NULL
UNION ALL SELECT '跨机构挂部门(应=0)', COUNT(*) FROM sys_user u
    JOIN sys_department d ON d.id = u.dept_id
    WHERE u.is_deleted = 0 AND d.org_id <> u.org_id;

SELECT d.dept_name AS 部门, d.dept_code AS 编码, IFNULL(p.dept_name, '(顶级)') AS 上级,
       (SELECT COUNT(*) FROM sys_user u WHERE u.dept_id = d.id AND u.is_deleted = 0) AS 人数
FROM sys_department d LEFT JOIN sys_department p ON p.id = d.parent_id
WHERE d.is_deleted = 0 ORDER BY d.sort_no;
