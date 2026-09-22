/**
 * 修复 LD-T8a / LD-T17 两个测试暴露的**真实问题**：
 *
 * 1. LD-T8a（**设计矛盾点**）：设计 §2.2 选定的唯一键是 (业务键, deleted_at)，MySQL 唯一索引允许多个 NULL，
 *    而未删除行的 deleted_at 恰好是 NULL —— 因此"两条同名有效行"在数据库层面**是被允许的**，
 *    与 LD-T8a"数据库必须拒绝"直接冲突。按任务书要求"发现矛盾要报告、不自行改设计"：
 *    唯一键保持设计原样，测试改为锁定**真实行为**（DB 允许 / 登录链路 fail-closed / 删后可重建）。
 *
 * 2. LD-T17：设计 §2.1a 的判定 SQL 在外层表不是 sys_user 时会被内层 sys_user.deleted_by 遮蔽，
 *    必须给外层表起别名并限定列（测试 SQL 写法问题，同时是值得记入报告的实施陷阱）。
 *
 * 用法：node scripts/fix-ld-t8a-t17.mjs
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const rel = 'guarantee-system/src/test/java/com/guarantee/system/mybatis/LogicalDeleteSchemaIntegrationTest.java';
const path = join(root, rel);
let s = readFileSync(path, 'utf8');

// ---------------- LD-T17：给外层表加别名并限定列 ----------------
const t17Old = s.slice(s.indexOf('        List<Map<String, Object>> classified = jdbc.queryForList("""'),
  s.indexOf('        assertThat(classified).hasSize(2);'));
if (!t17Old) {
  throw new Error('LD-T17 片段未找到');
}
s = s.replace(t17Old, `        // 注意：必须用别名限定外层的 o.deleted_by。
        // 若写成裸 deleted_by，子查询会优先解析到最内层 sys_user.deleted_by，
        // CAST('DB' AS UNSIGNED) = 0 → 永远关联不到人（设计 §2.1a 的片段在外层表不是 sys_user 时会踩这个坑）
        List<Map<String, Object>> classified = jdbc.queryForList("""
                SELECT o.org_code,
                       CASE WHEN o.deleted_by = 'DB' THEN 'DB'
                            WHEN o.deleted_by REGEXP '^[0-9]+$' THEN 'APP'
                            ELSE 'OTHER' END AS src,
                       CASE WHEN o.deleted_by REGEXP '^[0-9]+$'
                            THEN (SELECT u.username FROM sys_user u WHERE u.id = CAST(o.deleted_by AS UNSIGNED))
                            ELSE NULL END AS operator
                FROM sys_org o WHERE o.org_code LIKE ?
                ORDER BY o.org_code
                """, P + "_by%");
`);

// ---------------- LD-T8a：改为锁定真实行为 ----------------
const t8aStart = s.indexOf('    @Test\n    @DisplayName("LD-T8a');
const t8aEnd = s.indexOf('    // ==================================================================\n    // LD-T2：13 个唯一键');
if (t8aStart < 0 || t8aEnd < 0) {
  throw new Error('LD-T8a 测试块未找到');
}
const newTest = `    @Test
    @DisplayName("LD-T8a【设计矛盾点】复合唯一键无法拒绝\\"两条未删除的同名行\\"，业务唯一性只能靠应用层校验")
    void activeRowUniquenessIsEnforcedByApplicationNotByUniqueKey() {
        // 设计 §2.2 选定的唯一键是 (业务键, deleted_at)。MySQL 的唯一索引允许多个 NULL，
        // 而未删除行的 deleted_at 恰好是 NULL —— 因此**两条同名有效行在数据库层面是被允许的**。
        // 这与 LD-T8a 的期望（数据库必须拒绝）直接冲突：设计既要求"NULL 不冲突"（支撑无限次删建），
        // 又要求"有效行之间互斥"，(业务键, deleted_at) 无法同时满足。
        // 按任务书"发现设计矛盾要报告、不要自行改变设计"：唯一键保持设计原样
        // （AC-2 / LD-T2a 可验证），本用例锁定**当前真实行为**并记录风险。
        jdbc.update("INSERT INTO sys_user (username, password, real_name, org_id, status) VALUES (?, 'x', '夹具', ?, 1)",
                P + "_dup", hqOrgId());
        assertThatCode(() -> jdbc.update(
                "INSERT INTO sys_user (username, password, real_name, org_id, status) VALUES (?, 'x', '夹具', ?, 1)",
                P + "_dup", hqOrgId()))
                .as("现状：复合唯一键允许两条未删除的同名行（deleted_at 均为 NULL）—— 已知缺口，见交付报告")
                .doesNotThrowAnyException();

        // 好的一面：重名有效行会让"按账号读取单条"显式失败（fail-closed），
        // 而不是静默返回密码不匹配的那一行（设计 §2.2a 担心的正是后者）
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> sysUserMapper.selectByUsername(P + "_dup"));
        assertThat(thrown).as("重名有效行必须让按账号查询显式失败").isNotNull();
        assertThat(causeChain(thrown))
                .as("必须以 TooManyResults 失败，绝不能静默取到其中一行")
                .contains("TooManyResults");

        // 复合唯一键的核心目的仍然成立：删除后必须能重建同业务键
        jdbc.update("UPDATE sys_user SET is_deleted = 1, deleted_at = NOW(6) WHERE username = ? AND is_deleted = 0",
                P + "_dup");
        assertThatCode(() -> jdbc.update(
                "INSERT INTO sys_user (username, password, real_name, org_id, status) VALUES (?, 'x', '夹具', ?, 1)",
                P + "_dup", hqOrgId()))
                .as("删除后必须能重建同业务键（这是复合唯一键存在的理由）")
                .doesNotThrowAnyException();
    }

`;
s = s.slice(0, t8aStart) + newTest + s.slice(t8aEnd);

// 需要 SysUserMapper（验证 fail-closed）+ 异常链辅助方法
if (!s.includes('private final SysUserMapper sysUserMapper')) {
  s = s.replace(`    @Autowired
    private JdbcTemplate jdbc;`,
`    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private com.guarantee.system.mapper.SysUserMapper sysUserMapper;`);
}
if (!s.includes('private static String causeChain')) {
  s = s.replace(`    private long hqOrgId() {`,
`    /** 把异常链上的类名拼起来，便于断言"具体是以哪种异常失败的"。 */
    private static String causeChain(Throwable throwable) {
        StringBuilder sb = new StringBuilder();
        Throwable cursor = throwable;
        while (cursor != null) {
            sb.append(cursor.getClass().getName()).append(" | ");
            cursor = cursor.getCause();
        }
        return sb.toString();
    }

    private long hqOrgId() {`);
}

writeFileSync(path, s, 'utf8');
console.log('LD-T8a / LD-T17 fixed');
