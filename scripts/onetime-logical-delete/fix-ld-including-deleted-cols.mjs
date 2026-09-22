/**
 * 修复：SysUserMapper / SysRoleMapper 没有 entityCols 片段，
 * 新增的 selectEntityByIdIncludingDeleted 必须写显式列清单。
 * 用法：node scripts/fix-ld-including-deleted-cols.mjs
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const X = 'guarantee-system/src/main/resources/mapper/system/';

const fixes = [
  [X + 'SysUserMapper.xml',
    `        SELECT <include refid="entityCols"/> FROM sys_user WHERE id = #{id}`,
    `        SELECT id, username, password, real_name, org_id, dept_id, phone, email,
               status, last_login_at, created_at, updated_at,
               is_deleted AS isDeleted, deleted_at AS deletedAt, deleted_by AS deletedBy
        FROM sys_user WHERE id = #{id}`],
  [X + 'SysRoleMapper.xml',
    `        SELECT <include refid="entityCols"/> FROM sys_role WHERE id = #{id}`,
    `        SELECT id, role_code, role_name, description, status, created_at, updated_at,
               is_deleted AS isDeleted, deleted_at AS deletedAt, deleted_by AS deletedBy
        FROM sys_role WHERE id = #{id}`],
];

for (const [rel, a, b] of fixes) {
  const path = join(root, rel);
  const s = readFileSync(path, 'utf8');
  if (!s.includes(a)) {
    throw new Error('锚点未命中: ' + rel);
  }
  writeFileSync(path, s.replace(a, b), 'utf8');
  console.log('fixed: ' + rel);
}
