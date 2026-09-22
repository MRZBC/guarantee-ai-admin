/**
 * 修复 LogicalDeleteWebIT 的夹具机构层级：
 * 原来用 level=1 建夹具，会让 "SELECT id FROM sys_org WHERE org_level = 1" 返回 2 行
 * （headquartersId() 因此抛 IncorrectResultSizeDataAccessException）。
 * 改用 level=2 挂在总部下，并先取总部 id。
 * 用法：node scripts/fix-ld-web-it-fixture.mjs
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const path = join(root, 'guarantee-web/src/test/java/com/guarantee/web/LogicalDeleteWebIT.java');
let s = readFileSync(path, 'utf8');

const anchor = `        OrgDto.CreateRequest request = new OrgDto.CreateRequest();
        request.setOrgCode(P + "audit");
        request.setOrgName("LD-T12 审计夹具机构");
        request.setRegionCode("000000");
        request.setOrgLevel(1);
        request.setParentId(0L);`;
const repl = `        // 注意：夹具机构必须用 level=2 挂在总部下，**不能**再建一个 level=1 的机构——
        // 否则本类与其它类的 "SELECT id FROM sys_org WHERE org_level = 1" 会返回 2 行
        long hq = headquartersId();
        OrgDto.CreateRequest request = new OrgDto.CreateRequest();
        request.setOrgCode(P + "audit");
        request.setOrgName("LD-T12 审计夹具机构");
        request.setRegionCode("000000");
        request.setOrgLevel(2);
        request.setParentId(hq);`;

if (s.includes('request.setOrgLevel(2);')) {
  console.log('skip: already patched');
} else if (!s.includes(anchor)) {
  throw new Error('锚点未命中');
} else {
  writeFileSync(path, s.replace(anchor, repl), 'utf8');
  console.log('patched LogicalDeleteWebIT fixture');
}
