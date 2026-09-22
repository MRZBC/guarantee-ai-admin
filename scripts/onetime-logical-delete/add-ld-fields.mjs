/**
 * 批次 1：为实体类与 VO 追加逻辑删除字段（isDeleted / deletedAt / deletedBy）。
 *
 * 依据：docs/DEC-逻辑删除设计方案.md v2.1 §10.3 / 任务书 §9 批次 1
 *   - 实体类：isDeleted / deletedAt / deletedBy（三列与表结构一一对应）
 *   - VO：只加 isDeleted / deletedAt（删除操作人从审计表按 target_type+target_id 关联取，
 *     不在 VO 上再放 deletedBy —— 设计文档 §10.3 的明确要求）
 *   - ai_operation_secret 例外（LD-EX-01）：不加字段，本脚本刻意不含它
 *
 * 幂等：已包含 isDeleted 的文件会跳过。
 * 用法：node scripts/add-ld-fields.mjs
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');

const ENTITIES = [
  'guarantee-system/src/main/java/com/guarantee/system/entity/SysOrg.java',
  'guarantee-system/src/main/java/com/guarantee/system/entity/SysDepartment.java',
  'guarantee-system/src/main/java/com/guarantee/system/entity/SysUser.java',
  'guarantee-system/src/main/java/com/guarantee/system/entity/SysRole.java',
  'guarantee-system/src/main/java/com/guarantee/system/entity/SysPermission.java',
  'guarantee-system/src/main/java/com/guarantee/system/entity/InsuranceType.java',
  'guarantee-order/src/main/java/com/guarantee/order/entity/TenderOrder.java',
  'guarantee-order/src/main/java/com/guarantee/order/entity/PerformanceOrder.java',
  'guarantee-analysis/src/main/java/com/guarantee/analysis/entity/Enterprise.java',
  'guarantee-analysis/src/main/java/com/guarantee/analysis/entity/Project.java',
  'guarantee-ai/src/main/java/com/guarantee/ai/entity/AiConversation.java',
  'guarantee-ai/src/main/java/com/guarantee/ai/entity/AiMessage.java',
  'guarantee-ai/src/main/java/com/guarantee/ai/entity/AiToolCall.java',
  'guarantee-ai/src/main/java/com/guarantee/ai/entity/AiAuditLog.java',
  'guarantee-ai/src/main/java/com/guarantee/ai/entity/AiOperationProposal.java',
  'guarantee-ai/src/main/java/com/guarantee/ai/entity/AiOperationAudit.java',
];

const ENTITY_FIELDS = `
    /** 逻辑删除 0正常 1已删除（LD-01：所有业务查询默认只看 0） */
    private Integer isDeleted;
    /** 删除时间（DATETIME(6)，未删除为 null；同时是唯一键分量） */
    private LocalDateTime deletedAt;
    /** 删除人：应用写 sys_user.id 字符串，数据库直连删除为 'DB' */
    private String deletedBy;
`;

const VOS = [
  'guarantee-system/src/main/java/com/guarantee/system/vo/OrgVO.java',
  'guarantee-system/src/main/java/com/guarantee/system/vo/DepartmentVO.java',
  'guarantee-system/src/main/java/com/guarantee/system/vo/UserVO.java',
  'guarantee-system/src/main/java/com/guarantee/system/vo/RoleVO.java',
  'guarantee-system/src/main/java/com/guarantee/system/vo/PermissionVO.java',
];

const VO_FIELDS = `
    /** 逻辑删除 0正常 1已删除 */
    private Integer isDeleted;
    /** 删除时间（DATETIME(6)，未删除为 null） */
    private LocalDateTime deletedAt;
`;

function insertBeforeLastBrace(path, block) {
  let src = readFileSync(path, 'utf8');
  if (/private Integer isDeleted;/.test(src)) {
    console.log(`skip (already has fields): ${path}`);
    return;
  }
  const idx = src.lastIndexOf('\n}');
  if (idx < 0) {
    throw new Error(`未找到类结尾大括号: ${path}`);
  }
  src = src.slice(0, idx) + block + src.slice(idx);
  // 需要 LocalDateTime import（部分 VO 没有）
  if (!src.includes('import java.time.LocalDateTime;')) {
    const pkgEnd = src.indexOf('\n\n', src.indexOf('package '));
    src = src.slice(0, pkgEnd) + '\n\nimport java.time.LocalDateTime;' + src.slice(pkgEnd);
  }
  writeFileSync(path, src, 'utf8');
  console.log(`updated: ${path}`);
}

for (const p of ENTITIES) insertBeforeLastBrace(join(root, p), ENTITY_FIELDS);
for (const p of VOS) insertBeforeLastBrace(join(root, p), VO_FIELDS);
