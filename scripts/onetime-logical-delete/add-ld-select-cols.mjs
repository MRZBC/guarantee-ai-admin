/**
 * 批次 1：让系统域 Mapper 的 SELECT 列清单返回逻辑删除三列。
 *
 * 为什么必须显式返回：
 *   - VO 需要 isDeleted / deletedAt 才能在前端显示"已删除"标签与删除时间；
 *   - 实体需要三列才能支撑恢复（restore）与一致性巡检；
 *   - 列别名统一为 `is_deleted AS isDeleted`（**必须带 AS**）：拦截器判断
 *     "SQL 是否已显式带 is_deleted 条件"时会先剔除投影形式的 is_deleted，
 *     因此保留投影不会让默认列表查询失去兜底过滤。
 *
 * 幂等：锚点内已含 isDeleted 则跳过。
 * 用法：node scripts/add-ld-select-cols.mjs
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const m = (f) => join(root, 'guarantee-system/src/main/resources/mapper/system/' + f);

/** [文件, 锚点, 替换] —— 锚点必须唯一，且替换内容里含 isDeleted（幂等标记）。 */
const EDITS = [
  // ---------------- SysOrgMapper ----------------
  ['SysOrgMapper.xml',
    `        sort_no     AS sortNo,
        created_at  AS createdAt
    </sql>`,
    `        sort_no     AS sortNo,
        created_at  AS createdAt,
        is_deleted  AS isDeleted,
        deleted_at  AS deletedAt
    </sql>`],
  ['SysOrgMapper.xml',
    `    <sql id="entityCols">
        id, org_code, org_name, region_code, region_name, org_level, parent_id,
        status, sort_no, created_at, updated_at
    </sql>`,
    `    <sql id="entityCols">
        id, org_code, org_name, region_code, region_name, org_level, parent_id,
        status, sort_no, created_at, updated_at,
        is_deleted AS isDeleted, deleted_at AS deletedAt, deleted_by AS deletedBy
    </sql>`],

  // ---------------- SysDepartmentMapper ----------------
  ['SysDepartmentMapper.xml',
    `        d.sort_no    AS sortNo,
        d.created_at AS createdAt
    </sql>`,
    `        d.sort_no    AS sortNo,
        d.created_at AS createdAt,
        d.is_deleted AS isDeleted,
        d.deleted_at AS deletedAt
    </sql>`],

  // ---------------- SysUserMapper ----------------
  ['SysUserMapper.xml',
    `        u.status        AS status,
        u.last_login_at AS lastLoginAt,
        u.created_at    AS createdAt
    </sql>`,
    `        u.status        AS status,
        u.last_login_at AS lastLoginAt,
        u.created_at    AS createdAt,
        u.is_deleted    AS isDeleted,
        u.deleted_at    AS deletedAt
    </sql>`],

  // ---------------- SysRoleMapper ----------------
  ['SysRoleMapper.xml',
    `        r.status      AS status,
        r.created_at  AS createdAt
    </sql>`,
    `        r.status      AS status,
        r.created_at  AS createdAt,
        r.is_deleted  AS isDeleted,
        r.deleted_at  AS deletedAt
    </sql>`],

  // ---------------- InsuranceTypeMapper ----------------
  ['InsuranceTypeMapper.xml',
    `    <sql id="cols">
        id, type_code, type_name, category, base_rate, min_amount, max_amount,
        status, description, created_at, updated_at
    </sql>`,
    `    <sql id="cols">
        id, type_code, type_name, category, base_rate, min_amount, max_amount,
        status, description, created_at, updated_at,
        is_deleted AS isDeleted, deleted_at AS deletedAt, deleted_by AS deletedBy
    </sql>`],

  // ---------------- SysPermissionMapper ----------------
  ['SysPermissionMapper.xml',
    `               sort_no   AS sortNo
        FROM sys_permission`,
    `               sort_no   AS sortNo,
               is_deleted AS isDeleted,
               deleted_at AS deletedAt
        FROM sys_permission`],
];

/** 实体类内联列清单：把 `updated_at` 结尾的列清单补上三列（逐个精确锚点）。 */
const ENTITY_SELECTS = [
  ['SysDepartmentMapper.xml', 3, 'sys_department'],
  ['SysUserMapper.xml', 3, 'sys_user'],
  ['SysRoleMapper.xml', 4, 'sys_role'],
];

for (const [file, anchor, repl] of EDITS) {
  const path = m(file);
  let src = readFileSync(path, 'utf8');
  if (src.includes('isDeleted')) {
    console.log(`skip (already patched): ${file}`);
    continue;
  }
  if (!src.includes(anchor)) {
    throw new Error(`锚点未命中: ${file}\n---\n${anchor}`);
  }
  src = src.replace(anchor, repl);
  writeFileSync(path, src, 'utf8');
  console.log(`updated: ${file}`);
}

// 实体内联列清单：匹配 "SELECT ... FROM <table>" 中列清单以 updated_at 结束的段落
for (const [file, expected, table] of ENTITY_SELECTS) {
  const path = m(file);
  let src = readFileSync(path, 'utf8');
  const re = new RegExp(
    `((?:SELECT|,)\\s*[\\s\\S]{0,400}?)updated_at(\\s+FROM\\s+${table}\\b)`, 'g');
  let count = 0;
  src = src.replace(re, (all, head, tail) => {
    if (head.includes('isDeleted')) return all;
    count++;
    return `${head}updated_at, is_deleted AS isDeleted, deleted_at AS deletedAt, deleted_by AS deletedBy${tail}`;
  });
  if (count !== expected) {
    throw new Error(`${file}: 实体列清单命中 ${count} 处，期望 ${expected} 处`);
  }
  writeFileSync(path, src, 'utf8');
  console.log(`updated entity selects: ${file} (${count})`);
}
