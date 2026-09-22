/**
 * 批次 3：拦截器**盖不住**的场景，按设计文档 §5.3 / 任务书 §4.2 显式加过滤。
 *
 * 覆盖三类：
 *   1. 数据范围递归 CTE（selectVisibleOrgIds）—— 漏加 = **越权**（LD-T7）；
 *   2. 相关子查询（countOrderByOrg / selectOrgCounts / countOrderByType / EXISTS 片段）
 *      —— 拦截器只处理最外层 FROM/JOIN，派生表与子查询由 SQL 自身负责；
 *   3. 停用前置检查（LD-03）—— 只统计 is_deleted=0 的行，否则已删除用户会永远阻塞部门/机构停用。
 *
 * 用法：node scripts/patch-ld-explicit.mjs
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const S = 'guarantee-system/src/main/resources/mapper/system/';

const EDITS = [
  // ---------------- SysOrgMapper：数据范围递归 CTE（防越权） ----------------
  [S + 'SysOrgMapper.xml',
    `        WITH RECURSIVE org_tree (id, depth) AS (
            SELECT id, 1 FROM sys_org WHERE id = #{orgId}
            UNION ALL
            SELECT o.id, t.depth + 1
            FROM sys_org o
            INNER JOIN org_tree t ON o.parent_id = t.id
            WHERE t.depth &lt; 10
        )
        SELECT DISTINCT id FROM org_tree`,
    `        WITH RECURSIVE org_tree (id, depth) AS (
            <!-- 起手与递归都必须带 is_deleted = 0，否则范围会穿过已删除机构继续向下展开（越权，LD-T7） -->
            SELECT id, 1 FROM sys_org WHERE id = #{orgId} AND is_deleted = 0
            UNION ALL
            SELECT o.id, t.depth + 1
            FROM sys_org o
            INNER JOIN org_tree t ON o.parent_id = t.id AND o.is_deleted = 0
            WHERE t.depth &lt; 10
        )
        SELECT DISTINCT id FROM org_tree`],

  // ---------------- SysOrgMapper：停用前置检查（LD-03）与计数 ----------------
  [S + 'SysOrgMapper.xml',
    `            SELECT id, 1 FROM sys_org WHERE parent_id = #{id}
            UNION ALL
            SELECT o.id, t.depth + 1
            FROM sys_org o
            INNER JOIN org_tree t ON o.parent_id = t.id
            WHERE t.depth &lt; 10
        )
        SELECT COUNT(*) FROM sys_org WHERE status = 1 AND id IN (SELECT id FROM org_tree)`,
    `            SELECT id, 1 FROM sys_org WHERE parent_id = #{id} AND is_deleted = 0
            UNION ALL
            SELECT o.id, t.depth + 1
            FROM sys_org o
            INNER JOIN org_tree t ON o.parent_id = t.id AND o.is_deleted = 0
            WHERE t.depth &lt; 10
        )
        SELECT COUNT(*) FROM sys_org
        WHERE status = 1 AND is_deleted = 0 AND id IN (SELECT id FROM org_tree)`],

  [S + 'SysOrgMapper.xml',
    `        SELECT COUNT(*) FROM sys_org WHERE parent_id = #{id}`,
    `        SELECT COUNT(*) FROM sys_org WHERE parent_id = #{id} AND is_deleted = 0`],

  [S + 'SysOrgMapper.xml',
    `        SELECT COUNT(*) FROM sys_user WHERE org_id = #{orgId} AND status = 1`,
    `        <!-- LD-03：停用前置检查只统计未删除的启用用户 -->\n        SELECT COUNT(*) FROM sys_user WHERE org_id = #{orgId} AND status = 1 AND is_deleted = 0`],

  [S + 'SysOrgMapper.xml',
    `        SELECT COUNT(*) FROM sys_department WHERE org_id = #{orgId}`,
    `        SELECT COUNT(*) FROM sys_department WHERE org_id = #{orgId} AND is_deleted = 0`],

  [S + 'SysOrgMapper.xml',
    `        SELECT (SELECT COUNT(*) FROM tender_order WHERE org_id = #{orgId})
             + (SELECT COUNT(*) FROM performance_order WHERE org_id = #{orgId})`,
    `        SELECT (SELECT COUNT(*) FROM tender_order WHERE org_id = #{orgId} AND is_deleted = 0)
             + (SELECT COUNT(*) FROM performance_order WHERE org_id = #{orgId} AND is_deleted = 0)`],

  [S + 'SysOrgMapper.xml',
    `               (SELECT COUNT(*) FROM sys_department d WHERE d.org_id = o.id) AS deptCount,
               (SELECT COUNT(*) FROM sys_user u WHERE u.org_id = o.id AND u.status = 1) AS userCount`,
    `               (SELECT COUNT(*) FROM sys_department d
                 WHERE d.org_id = o.id AND d.is_deleted = 0) AS deptCount,
               (SELECT COUNT(*) FROM sys_user u
                 WHERE u.org_id = o.id AND u.status = 1 AND u.is_deleted = 0) AS userCount`],

  // ---------------- SysUserMapper：角色过滤 EXISTS 子查询 ----------------
  [S + 'SysUserMapper.xml',
    `                    WHERE ur2.user_id = u.id AND r2.role_code = #{q.roleCode}`,
    `                    WHERE ur2.user_id = u.id AND r2.role_code = #{q.roleCode}
                      AND ur2.is_deleted = 0 AND r2.is_deleted = 0`],

  // ---------------- SysDepartmentMapper：停用前置检查（LD-03 / LD-T13） ----------------
  [S + 'SysDepartmentMapper.xml',
    `        SELECT COUNT(*) FROM sys_user WHERE dept_id = #{deptId} AND status = 1`,
    `        <!-- LD-03：只统计未删除的启用用户；已删除用户不该继续阻塞部门停用 -->
        SELECT COUNT(*) FROM sys_user
        WHERE dept_id = #{deptId} AND status = 1 AND is_deleted = 0`],

  // ---------------- SysRoleMapper：机构过滤 EXISTS 子查询 ----------------
  [S + 'SysRoleMapper.xml',
    `                    WHERE ur3.role_id = r.id AND u3.org_id = #{q.orgId}`,
    `                    WHERE ur3.role_id = r.id AND u3.org_id = #{q.orgId}
                      AND ur3.is_deleted = 0 AND u3.is_deleted = 0`],

  // ---------------- InsuranceTypeMapper：引用计数子查询 ----------------
  [S + 'InsuranceTypeMapper.xml',
    `        SELECT (SELECT COUNT(*) FROM tender_order WHERE insurance_type_id = #{id})
             + (SELECT COUNT(*) FROM performance_order WHERE insurance_type_id = #{id})`,
    `        SELECT (SELECT COUNT(*) FROM tender_order
                 WHERE insurance_type_id = #{id} AND is_deleted = 0)
             + (SELECT COUNT(*) FROM performance_order
                 WHERE insurance_type_id = #{id} AND is_deleted = 0)`],
];

for (const [rel, anchor, repl] of EDITS) {
  const path = join(root, rel);
  const src = readFileSync(path, 'utf8');
  if (!src.includes(anchor)) {
    throw new Error(`锚点未命中: ${rel}\n---\n${anchor}`);
  }
  writeFileSync(path, src.replace(anchor, repl), 'utf8');
  console.log('patched: ' + rel.replace(S, ''));
}
console.log(`explicit filters applied: ${EDITS.length}`);
