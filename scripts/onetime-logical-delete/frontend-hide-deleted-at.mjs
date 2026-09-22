/**
 * 按用户决定：前端**不展示** deleted_at（与 deleted_by）。
 *
 * 具体做法：
 *   1. 去掉「删除信息」列里的删除时间文本，列改名为「删除标记」并收窄宽度，只保留「已删除」标签
 *      （AC-4 要求"已删除行有置灰与标签"，标签保留；时间不展示）；
 *   2. 删除随之失效的 .deleted-at 样式；
 *   3. 类型与 API 层仍保留 deletedAt 字段（后端契约未变，前端只是不渲染）。
 *
 * 用法：node scripts/frontend-hide-deleted-at.mjs
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const dir = 'frontend/src/views/system/';

const pages = ['Orgs.vue', 'Departments.vue', 'Users.vue', 'Roles.vue', 'InsuranceTypes.vue'];

for (const page of pages) {
  const path = join(root, dir + page);
  let s = readFileSync(path, 'utf8');
  const before = s;

  // 1. 去掉删除时间那一行
  s = s.replace(/\s*<span class="deleted-at">\{\{ formatDateTime\(row\.deletedAt\) \}\}<\/span>/g, '');

  // 2. 注释与列名改为"删除标记"，宽度收窄
  s = s.replace(/<!-- 已删除行：整行置灰（rowClassName）\+ `已删除` 标签 \+ 删除时间；删除操作人不返回（设计文档 §10\.3） -->/g,
    '<!-- 已删除行：整行置灰（rowClassName）+ `已删除` 标签。\n             按产品决定**不展示**删除时间与删除人（deleted_at / deleted_by 均不上屏） -->');
  s = s.replace(/label="删除信息" width="200"/g, 'label="删除标记" width="90"');

  // 3. 删除失效样式块
  s = s.replace(/\n\.deleted-at \{[^}]*\}\n/g, '\n');

  if (s === before) {
    console.log('skip (no change): ' + page);
    continue;
  }
  writeFileSync(path, s, 'utf8');
  console.log('updated: ' + page);
}

// 自检：不应再有 deletedAt 的渲染
for (const page of pages) {
  const s = readFileSync(join(root, dir + page), 'utf8');
  const hits = [...s.matchAll(/formatDateTime\(row\.deletedAt\)|class="deleted-at"/g)];
  console.log(`${page}: 剩余 deletedAt 渲染 ${hits.length} 处（应为 0）`);
}
