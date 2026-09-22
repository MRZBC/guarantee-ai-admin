/**
 * 批次 1：为 5 个列表查询 DTO 的 Query 内部类追加 includeDeleted（默认 false）。
 * 依据：设计文档 §10.3 / 任务书 §4.2、§7（列表 includeDeleted=true 需 system:*:delete 权限）。
 *
 * 幂等：已存在 includeDeleted 的文件跳过。
 * 用法：node scripts/add-ld-dto-flag.mjs
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const base = 'guarantee-system/src/main/java/com/guarantee/system/dto/';

const ANCHOR = `        /** 数据范围（服务端强制注入，SYS-P-08）。 */
        private QueryScope scope = QueryScope.unrestricted();`;

const BLOCK = `

        /**
         * 是否包含已删除记录（LD-04b「显示已删除」开关）。
         *
         * <p>默认 false。仅持有 {@code system:*:delete} 权限的调用方可传 true；
         * 权限判定在 Controller 的 {@code @PreAuthorize} 完成，不依赖本字段。</p>
         */
        private Boolean includeDeleted = false;`;

const TARGETS = ['OrgDto.java', 'DepartmentDto.java', 'UserDto.java', 'RoleDto.java'];
const INSURANCE_ANCHOR = `        /** TENDER / PERFORMANCE / OTHER。 */
        private String category;

        private Integer status;`;

for (const f of TARGETS) {
  const path = join(root, base + f);
  let src = readFileSync(path, 'utf8');
  if (src.includes('includeDeleted')) {
    console.log(`skip: ${f}`);
    continue;
  }
  if (!src.includes(ANCHOR)) {
    throw new Error(`锚点未命中: ${f}`);
  }
  src = src.replace(ANCHOR, ANCHOR + BLOCK);
  writeFileSync(path, src, 'utf8');
  console.log(`updated: ${f}`);
}

{
  const path = join(root, base + 'InsuranceTypeDto.java');
  let src = readFileSync(path, 'utf8');
  if (src.includes('includeDeleted')) {
    console.log('skip: InsuranceTypeDto.java');
  } else if (!src.includes(INSURANCE_ANCHOR)) {
    throw new Error('锚点未命中: InsuranceTypeDto.java');
  } else {
    src = src.replace(INSURANCE_ANCHOR, INSURANCE_ANCHOR + BLOCK);
    writeFileSync(path, src, 'utf8');
    console.log('updated: InsuranceTypeDto.java');
  }
}
