/**
 * 进度文档追加（第二轮实测）：把"用行编号 id 做唯一键分量"的三个变体写进 A.1，
 * 与设计文档 §2.2c（v2.2 修订）互补——§2.2c 说明"为什么要函数索引"，这里说明
 * "为什么不能用自增主键 id 替代时间戳"。
 * 用法：node scripts/update-progress-id-key-experiment.mjs
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const path = join(root, 'docs/IMPL-逻辑删除-进度.md');
let s = readFileSync(path, 'utf8');

if (s.includes('补充实测（第二轮，用行编号 id 做唯一键分量）')) {
  console.log('skip: already appended');
  process.exit(0);
}

const anchor = `     C 可同时满足两个目标，且不是触发器/存储过程（不违反 LD-EX-02），MySQL 8.0.13+ 支持。`;
const insert = `${anchor}
   - **补充实测（第二轮，用行编号 id 做唯一键分量）**——回答"能不能像用户编号/订单编号那样加个唯一键"：

     | 变体 | 实测结果 |
     | --- | --- |
     | D \`UNIQUE(业务键, id)\` | **插入两条同名有效行成功**（\`COUNT(*)=2\`）。因为 id 天然唯一，把 id 放进唯一键等于**取消业务唯一性约束**——"不撞键"是因为不校验，不是校验通过（与设计 §2.2a 结论一致） |
     | C1 函数索引 \`UNIQUE(业务键, (IF(is_deleted=1, id, 0)))\` | \`ERROR 3754: Functional index 'uk' cannot refer to an auto-increment column.\` |
     | C2 生成列 \`active_key BIGINT AS (IF(is_deleted=1, id, 0)) STORED\` | \`ERROR 3109: Generated column 'active_key' cannot refer to auto-increment column.\` |

     结论：**MySQL 明确禁止把自增列用于函数索引/生成列**，所以"用行编号做已删除行的区分分量"
     在技术上不可行；\`deleted_at\`（微秒）是目前唯一可用的区分分量。C 方案的落地形态见设计文档 §2.2c
     与 \`V3__logical_delete_functional_unique_keys.sql\`。`;

if (!s.includes(anchor)) {
  throw new Error('锚点未命中');
}
writeFileSync(path, s.replace(anchor, insert), 'utf8');
console.log('progress doc appended (id-key experiment)');
