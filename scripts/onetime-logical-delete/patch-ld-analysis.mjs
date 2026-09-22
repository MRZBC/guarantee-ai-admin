/**
 * 批次 3：分析模块与订单模块的**显式**逻辑删除过滤（任务书 §4.1 风险提示）。
 *
 * 为什么不依赖拦截器：
 *   这两个模块的 SQL 大量使用 "FROM (…UNION ALL…) 派生表 + GROUP BY"，
 *   拦截器只能处理最外层 FROM/JOIN，派生表内部的表它不会碰（设计文档 §5.2 也明确
 *   "子查询内的表由其自身条件负责"）。任务书因此要求分析模块**手工显式加过滤并逐个测试**。
 *   显式写了 is_deleted 之后，拦截器对这些语句自动跳过（不重复注入）。
 *
 * 幂等：锚点已含 is_deleted 则跳过。
 * 用法：node scripts/patch-ld-analysis.mjs
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const A = 'guarantee-analysis/src/main/resources/mapper/analysis/';
const O = 'guarantee-order/src/main/resources/mapper/order/';

const EDITS = [
  // ---------------- OverviewMapper ----------------
  [A + 'OverviewMapper.xml',
    `            FROM tender_order
            UNION ALL`,
    `            FROM tender_order
            WHERE is_deleted = 0
            UNION ALL`],
  [A + 'OverviewMapper.xml',
    `            FROM performance_order
        ) merged`,
    `            FROM performance_order
            WHERE is_deleted = 0
        ) merged`],
  [A + 'OverviewMapper.xml',
    `(SELECT COUNT(*) FROM sys_org)`,
    `(SELECT COUNT(*) FROM sys_org WHERE is_deleted = 0)`],
  [A + 'OverviewMapper.xml',
    `(SELECT COUNT(*) FROM tender_order WHERE status = 'EFFECTIVE')`,
    `(SELECT COUNT(*) FROM tender_order WHERE status = 'EFFECTIVE' AND is_deleted = 0)`],
  [A + 'OverviewMapper.xml',
    `(SELECT COUNT(*) FROM performance_order WHERE status = 'EFFECTIVE')`,
    `(SELECT COUNT(*) FROM performance_order WHERE status = 'EFFECTIVE' AND is_deleted = 0)`],
  [A + 'OverviewMapper.xml',
    `                SELECT enterprise_id FROM tender_order
                UNION`,
    `                SELECT enterprise_id FROM tender_order WHERE is_deleted = 0
                UNION`],
  [A + 'OverviewMapper.xml',
    `                SELECT enterprise_id FROM performance_order
            ) e)`,
    `                SELECT enterprise_id FROM performance_order WHERE is_deleted = 0
            ) e)`],
  [A + 'OverviewMapper.xml',
    `                SELECT project_id FROM tender_order
                UNION`,
    `                SELECT project_id FROM tender_order WHERE is_deleted = 0
                UNION`],
  [A + 'OverviewMapper.xml',
    `                SELECT project_id FROM performance_order
            ) p)`,
    `                SELECT project_id FROM performance_order WHERE is_deleted = 0
            ) p)`],

  // ---------------- OrderStatisticsMapper ----------------
  [O + 'OrderStatisticsMapper.xml',
    `    <sql id="branchFilter">
        <if test="c.startDate != null">`,
    `    <sql id="branchFilter">
        <!-- 逻辑删除过滤（LD-01）：订单本身不提供删除入口，此处保证口径与全库一致 -->
        AND is_deleted = 0
        <if test="c.startDate != null">`],

  // ---------------- EnterpriseMapper ----------------
  [A + 'EnterpriseMapper.xml',
    `            FROM tender_order
            UNION ALL`,
    `            FROM tender_order
            WHERE is_deleted = 0
            UNION ALL`],
  [A + 'EnterpriseMapper.xml',
    `            FROM performance_order
        ) merged`,
    `            FROM performance_order
            WHERE is_deleted = 0
        ) merged`],
  [A + 'EnterpriseMapper.xml',
    `    <sql id="queryWhere">
        <where>`,
    `    <sql id="queryWhere">
        <where>
            <!-- 显式过滤：企业可被逻辑删除（§6.2），列表/计数必须排除已删除企业 -->
            AND en.is_deleted = 0`],
  [A + 'EnterpriseMapper.xml',
    `            FROM tender_order
            WHERE enterprise_id = #{id}
            UNION ALL`,
    `            FROM tender_order
            WHERE enterprise_id = #{id} AND is_deleted = 0
            UNION ALL`],
  [A + 'EnterpriseMapper.xml',
    `            FROM performance_order
            WHERE enterprise_id = #{id}
        ) merged`,
    `            FROM performance_order
            WHERE enterprise_id = #{id} AND is_deleted = 0
        ) merged`],

  // ---------------- ProjectMapper ----------------
  [A + 'ProjectMapper.xml',
    `    <sql id="queryWhere">
        <where>`,
    `    <sql id="queryWhere">
        <where>
            <!-- 显式过滤：项目可被逻辑删除（§6.2） -->
            AND p.is_deleted = 0`],
  [A + 'ProjectMapper.xml',
    `            FROM tender_order
            WHERE project_id = #{id}
            UNION ALL`,
    `            FROM tender_order
            WHERE project_id = #{id} AND is_deleted = 0
            UNION ALL`],
  [A + 'ProjectMapper.xml',
    `            FROM performance_order
            WHERE project_id = #{id}
        ) merged`,
    `            FROM performance_order
            WHERE project_id = #{id} AND is_deleted = 0
        ) merged`],
];

let applied = 0;
for (const [rel, anchor, repl] of EDITS) {
  const path = join(root, rel);
  const src = readFileSync(path, 'utf8');
  if (!src.includes(anchor)) {
    throw new Error(`锚点未命中: ${rel}\n---\n${anchor}`);
  }
  writeFileSync(path, src.replace(anchor, repl), 'utf8');
  applied++;
}
console.log(`analysis/order mappers patched: ${applied}`);
