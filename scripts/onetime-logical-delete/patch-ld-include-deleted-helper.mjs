/**
 * 把 5 个 Controller 里重复的 includeDeleted 权限判定收敛到
 * LogicalDeletePermissions.requireIncludeDeleted（单一实现，可单测）。
 * 用法：node scripts/patch-ld-include-deleted-helper.mjs
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const CTL = 'guarantee-system/src/main/java/com/guarantee/system/controller/';

const targets = [
  ['OrgController.java', 'ORG_DELETE', '机构'],
  ['DepartmentController.java', 'DEPT_DELETE', '部门'],
  ['UserController.java', 'USER_DELETE', '用户'],
  ['RoleController.java', 'ROLE_DELETE', '角色'],
  ['InsuranceTypeController.java', 'INSURANCE_DELETE', '险种'],
];

for (const [file, perm, label] of targets) {
  const path = join(root, CTL + file);
  let s = readFileSync(path, 'utf8');
  const anchor = `    private static void requireDeletePermissionWhenIncludingDeleted(Boolean includeDeleted) {
        if (Boolean.TRUE.equals(includeDeleted) && !CurrentUser.hasPermission(Permissions.${perm})) {
            throw new BizException(ResultCode.FORBIDDEN,
                    "${label}的「显示已删除」需要权限：" + Permissions.${perm});
        }
    }`;
  const repl = `    private static void requireDeletePermissionWhenIncludingDeleted(Boolean includeDeleted) {
        // 判定逻辑收敛到 guarantee-common（单一实现，可单测）：参数级权限无法用 @PreAuthorize 表达
        LogicalDeletePermissions.requireIncludeDeleted(includeDeleted, Permissions.${perm}, "${label}");
    }`;
  if (s.includes('LogicalDeletePermissions.requireIncludeDeleted')) {
    console.log('skip: ' + file);
    continue;
  }
  if (!s.includes(anchor)) {
    throw new Error('锚点未命中: ' + file);
  }
  s = s.replace(anchor, repl);
  if (!s.includes('import com.guarantee.common.security.LogicalDeletePermissions;')) {
    s = s.replace('import com.guarantee.common.security.Permissions;',
      'import com.guarantee.common.security.LogicalDeletePermissions;\n'
      + 'import com.guarantee.common.security.Permissions;');
  }
  writeFileSync(path, s, 'utf8');
  console.log('patched: ' + file);
}
