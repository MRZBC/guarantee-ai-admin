/**
 * 修复：/system/orgs/tree 与分页列表共用同一 queryWhere（含 includeDeleted 分支），
 * 因此必须同样做 includeDeleted 的参数级权限校验，否则可绕过列表校验从树接口读到已删除机构。
 * 用法：node scripts/fix-ld-org-tree-perm.mjs
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const path = join(root, 'guarantee-system/src/main/java/com/guarantee/system/controller/OrgController.java');

const anchor = `    public Result<List<OrgVO>> tree(@Valid OrgDto.Query query) {
        return Result.ok(orgService.tree(query, currentScope()));
    }`;
const repl = `    public Result<List<OrgVO>> tree(@Valid OrgDto.Query query) {
        // 树与分页列表共用同一查询口径，因此 includeDeleted 的权限校验必须同样生效，
        // 否则可以绕过列表的参数级校验，从树接口读到已删除机构（设计 §7.1）
        requireDeletePermissionWhenIncludingDeleted(query.getIncludeDeleted());
        return Result.ok(orgService.tree(query, currentScope()));
    }`;

const src = readFileSync(path, 'utf8');
if (src.includes('requireDeletePermissionWhenIncludingDeleted(query.getIncludeDeleted());\n        return Result.ok(orgService.tree')) {
  console.log('skip: already patched');
} else if (!src.includes(anchor)) {
  throw new Error('锚点未命中');
} else {
  writeFileSync(path, src.replace(anchor, repl), 'utf8');
  console.log('patched: OrgController.tree');
}
