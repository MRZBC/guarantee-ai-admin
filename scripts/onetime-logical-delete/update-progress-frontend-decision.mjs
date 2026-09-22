/**
 * 进度文档更新（2026-09-22 产品决定）：
 *   1. 前端不展示 deleted_at / deleted_by（已实现，替换原"任务书 §8 与设计 §10.3 冲突"条目）；
 *   2. 唯一键方案 A/B/C 的实测证据与待决策项写入"设计矛盾"第 1 条。
 * 用法：node scripts/update-progress-frontend-decision.mjs
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const path = join(root, 'docs/IMPL-逻辑删除-进度.md');
let s = readFileSync(path, 'utf8');

// ---------- 1. 前端决定 ----------
const old3 = `3. **任务书 §8 与设计 §10.3 冲突**：任务书要求前端展示 \`deleted_by\`（\`'DB'\` → "数据库直连"、
   数字 → 关联账号），设计 §10.3 明确"VO 不加 \`deletedBy\`，操作人从审计表关联取"。
   按"设计文档为唯一依据"实现：VO 只有 \`isDeleted\` / \`deletedAt\`，前端只展示删除时间。
   若确需展示操作人，建议单独提供一个按 \`target_type + target_id\` 查审计的接口（前端已预留展示位）。`;
const new3 = `3. **任务书 §8 与设计 §10.3 冲突（已按产品决定收敛）**：任务书要求前端展示 \`deleted_by\`
   （\`'DB'\` → "数据库直连"、数字 → 关联账号），设计 §10.3 明确"VO 不加 \`deletedBy\`"。
   **产品最终决定（2026-09-22）：前端不展示 \`deleted_at\` 与 \`deleted_by\`。**
   - 5 个系统管理页已去掉删除时间文本，列由「删除信息」改为「删除标记」（只保留 \`已删除\` 标签，
     仍满足 AC-4 的"置灰 + 标签"），并删除随之失效的 \`.deleted-at\` 样式
   - **接口契约未变**：VO 仍按设计 §10.3 返回 \`isDeleted\` + \`deletedAt\`（前端只是不渲染）。
     若要求接口也去掉 \`deletedAt\`，需同步改 5 个 VO 与 mapper 列、以及 AI 查询工具的 Result record
     （属对 §10.3 的收紧，需另行确认）
   - 验证：\`npx vue-tsc --noEmit\` exit 0、\`npm run build\` exit 0`;
if (!s.includes(old3)) {
  throw new Error('前端决定条目锚点未命中');
}
s = s.replace(old3, new3);

// ---------- 2. 唯一键决策项：补实测证据 ----------
const anchor = `   - 建议（需评审）：把唯一键改为函数索引 \`UNIQUE (username, (IFNULL(deleted_at,'1970-01-01 00:00:00.000000')))\`
     或增加生成列 \`active_key\`（两者都不是触发器/存储过程，不违反 LD-EX-02），即可同时满足
     "有效行互斥"与"无限次删建"；代价是 AC-2 / LD-T2a 的断言形式要改、并需在 MySQL 8.0.13+ 上验证。
     若维持现方案，则必须在文档中显式承认"业务唯一性由应用层保证，DB 不保证"。`;
const replacement = `   - **三个方案的实测证据（本机 MySQL 8.0.29，测试表已清理）**：

     | 方案 | 两条未删除同名行 | 删除后重建同业务键 | 5 轮删建 |
     | --- | --- | --- | --- |
     | A \`UNIQUE(业务键, deleted_at)\`（设计原案，当前实现） | 允许（NULL 不冲突） | 允许 | 通过 |
     | B \`UNIQUE(业务键)\`（单列） | **拒绝**（1062） | **拒绝**（1062 \`Duplicate entry 'u1'\`） | 不可能 |
     | C 函数索引 \`UNIQUE(业务键, (IFNULL(deleted_at,'1970-01-01 00:00:00.000000')))\` | **拒绝**（1062，哨兵值） | 允许 | 通过（5 行 / 5 个不同时间戳） |

     B 的代价：被删除的业务键被**永久占用**，只有物理删除软删行或删除时改写业务键才能回收
     （前者与 R-04 冲突，后者被设计 §2.2 明确否决——会破坏按名称查询/日志/导出）。
     C 可同时满足两个目标，且不是触发器/存储过程（不违反 LD-EX-02），MySQL 8.0.13+ 支持。
   - **建议**：采用 C；若产品接受"删除后不回收业务键"，则 B 也可，且 B 的实现最简单。
     维持 A 则必须在文档中显式承认"业务唯一性由应用层保证、DB 不保证"。
   - **当前状态：未改动**——唯一键方案属设计决策（牵动 §3 唯一键清单、AC-2、LD-T2/LD-T2a/LD-T15），
     按任务书"发现设计矛盾要报告、不要自行改变设计"保持 A 不变，等待评审结论后落地。`;
if (!s.includes(anchor)) {
  throw new Error('唯一键建议锚点未命中');
}
s = s.replace(anchor, replacement);

writeFileSync(path, s, 'utf8');
console.log('progress doc updated');
