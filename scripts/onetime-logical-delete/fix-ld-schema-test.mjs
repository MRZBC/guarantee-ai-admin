/**
 * 修复 LogicalDeleteSchemaIntegrationTest 的三处问题（改名后路径）：
 *   1. @TestPropertySource 属性名写错（location → schema-locations）；
 *   2. sys_org 夹具的 parent_id 用了一个不相干的 id；
 *   3. LD-T17 里多余的 hqId 变量。
 * 用法：node scripts/fix-ld-schema-test.mjs
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const path = join(root,
  'guarantee-system/src/test/java/com/guarantee/system/mybatis/LogicalDeleteSchemaIntegrationTest.java');
let s = readFileSync(path, 'utf8');
const before = s;

s = s.replace('"spring.sql.init.location=classpath:db/schema.sql"',
  '"spring.sql.init.schema-locations=classpath:db/schema.sql"');

s = s.replace(`                new KeyCase("sys_org", "org_code", P + "_o1",
                        "INSERT INTO sys_org (org_code, org_name, region_code, region_name, org_level, parent_id, status) "
                                + "VALUES (?, '夹具', '000000', '未指定', 3, ?, 1)",
                        List.of(projectId /* 任意已存在 id，本用例不校验层级 */)),`,
`                new KeyCase("sys_org", "org_code", P + "_o1",
                        "INSERT INTO sys_org (org_code, org_name, region_code, region_name, org_level, parent_id, status) "
                                + "VALUES (?, '夹具', '000000', '未指定', 3, 0, 1)",
                        List.of()),`);

s = s.replace(`    void deletedByDistinguishesDirectDbFromApplication() {
        long hqId = hqOrgId();
        // 直连删除风格`, `    void deletedByDistinguishesDirectDbFromApplication() {
        // 直连删除风格`);
s = s.replace(`        assertThat(nulls).as("列是 NOT NULL，不允许出现 NULL（'未知'不能被伪装成已知用户）").isZero();
        assertThat(hqId).isPositive();
    }`, `        assertThat(nulls).as("列是 NOT NULL，不允许出现 NULL（'未知'不能被伪装成已知用户）").isZero();
    }`);

if (s === before) {
  throw new Error('没有任何修改，请检查锚点');
}
writeFileSync(path, s, 'utf8');
console.log('schema test fixed');
