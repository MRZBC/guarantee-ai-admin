/**
 * 批次 3：订单模块（guarantee-order）的显式逻辑删除过滤。
 *
 * LEFT JOIN 的维度表条件写在 ON 里（保持外连接语义：订单行不因维度被删而消失），
 * 主表条件写在 WHERE 里。
 *
 * 用法：node scripts/patch-ld-order.mjs
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const dir = 'guarantee-order/src/main/resources/mapper/order/';

for (const [file, base] of [['TenderOrderMapper.xml', 'tender_order'],
  ['PerformanceOrderMapper.xml', 'performance_order']]) {
  const path = join(root, dir + file);
  let s = readFileSync(path, 'utf8');
  const before = s;

  const fromAnchor = `FROM ${base} o
        LEFT JOIN project p ON p.id = o.project_id
        LEFT JOIN enterprise e ON e.id = o.enterprise_id
        LEFT JOIN insurance_type it ON it.id = o.insurance_type_id
        LEFT JOIN sys_org so ON so.id = o.org_id`;
  const fromRepl = `FROM ${base} o
        LEFT JOIN project p ON p.id = o.project_id AND p.is_deleted = 0
        LEFT JOIN enterprise e ON e.id = o.enterprise_id AND e.is_deleted = 0
        LEFT JOIN insurance_type it ON it.id = o.insurance_type_id AND it.is_deleted = 0
        LEFT JOIN sys_org so ON so.id = o.org_id AND so.is_deleted = 0`;
  if (s.includes(fromAnchor)) s = s.replace(fromAnchor, fromRepl);

  const whereAnchor = `    <sql id="queryWhere">
        <where>`;
  const whereRepl = `    <sql id="queryWhere">
        <where>
            <!-- 显式过滤（LD-01）：订单主表；LEFT JOIN 维度表条件写在 ON 里以保持外连接语义 -->
            AND o.is_deleted = 0`;
  if (s.includes(whereAnchor)) s = s.replace(whereAnchor, whereRepl);

  if (s.includes('        WHERE o.id = #{id}\n')) {
    s = s.replace('        WHERE o.id = #{id}\n', '        WHERE o.id = #{id} AND o.is_deleted = 0\n');
  }

  if (s === before) throw new Error('no change: ' + file);
  writeFileSync(path, s, 'utf8');
  console.log(`${file}: is_deleted 条件 ${(s.match(/is_deleted = 0/g) || []).length} 处`);
}
