/**
 * 批次 3：guarantee-analysis 的 OrderAnalysisMapper 显式过滤补丁。
 * 用法：node scripts/patch-ld-order-analysis.mjs
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const path = join(root, 'guarantee-analysis/src/main/resources/mapper/analysis/OrderAnalysisMapper.xml');
let s = readFileSync(path, 'utf8');

const EDITS = [
  [`    <sql id="criteriaFilter">
        <if test="c.startDate != null">`,
    `    <sql id="criteriaFilter">
        <!-- 显式过滤（LD-01）：作用于 UNION ALL 的每个分支 -->
        AND is_deleted = 0
        <if test="c.startDate != null">`],

  [`                FROM tender_order
                WHERE 1 = 0`,
    `                FROM tender_order
                WHERE 1 = 0 AND is_deleted = 0`],

  [`LEFT JOIN enterprise et ON et.id = o.enterprise_id AND et.region_code = o.region_code`,
    `LEFT JOIN enterprise et ON et.id = o.enterprise_id AND et.region_code = o.region_code
            AND et.is_deleted = 0`],

  [`LEFT JOIN insurance_type t ON t.id = o.insurance_type_id AND t.status = 1`,
    `LEFT JOIN insurance_type t ON t.id = o.insurance_type_id AND t.status = 1 AND t.is_deleted = 0`],

  [`LEFT JOIN sys_org g ON g.id = o.org_id AND g.status = 1`,
    `LEFT JOIN sys_org g ON g.id = o.org_id AND g.status = 1 AND g.is_deleted = 0`],
];

for (const [a, b] of EDITS) {
  if (!s.includes(a)) throw new Error('锚点未命中:\n' + a);
  if (s.includes(b)) continue;
  s = s.replace(a, b);
}
writeFileSync(path, s, 'utf8');
console.log('OrderAnalysisMapper.xml: is_deleted 条件', (s.match(/is_deleted = 0/g) || []).length, '处');
