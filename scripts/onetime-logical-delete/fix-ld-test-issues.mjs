/**
 * 修复 4 处测试暴露出的实现/断言问题：
 *   1. SysOrgMapper.entityCols 漏了逻辑删除三列（早前的脚本按"文件已含 isDeleted"误跳过），
 *      导致删除响应里的 isDeleted / deletedAt 为 null；
 *   2. listRoleIdsByUserId 只过滤了绑定行的 is_deleted，没有过滤角色自身被删除的情况
 *      （用户角色回填应看不到已删除角色）；
 *   3. LD-T9 关于"删除角色撤销令牌"的断言不成立：§6.2 要求角色无持有者才允许删除，
 *      因此删除角色时必然没有持有者可撤——改为断言"变更角色权限 → 撤销持有者"；
 *   4. 改写器单测对空格敏感，统一做空白归一化。
 * 用法：node scripts/fix-ld-test-issues.mjs
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');

function patch(rel, anchor, repl, marker) {
  const path = join(root, rel);
  const s = readFileSync(path, 'utf8');
  if (marker && s.includes(marker)) {
    console.log('skip: ' + rel);
    return;
  }
  if (!s.includes(anchor)) {
    throw new Error('锚点未命中: ' + rel + '\n---\n' + anchor.slice(0, 200));
  }
  writeFileSync(path, s.replace(anchor, repl), 'utf8');
  console.log('patched: ' + rel);
}

// 1. SysOrgMapper.entityCols 补齐三列
patch('guarantee-system/src/main/resources/mapper/system/SysOrgMapper.xml',
  `    <sql id="entityCols">
        id, org_code, org_name, region_code, region_name, org_level, parent_id,
        status, sort_no, created_at, updated_at
    </sql>`,
  `    <sql id="entityCols">
        id, org_code, org_name, region_code, region_name, org_level, parent_id,
        status, sort_no, created_at, updated_at,
        is_deleted AS isDeleted, deleted_at AS deletedAt, deleted_by AS deletedBy
    </sql>`,
  'is_deleted AS isDeleted, deleted_at AS deletedAt, deleted_by AS deletedBy\n    </sql>');

// 2. listRoleIdsByUserId 同时过滤"角色已被删除"
patch('guarantee-system/src/main/resources/mapper/system/SysUserMapper.xml',
  `        SELECT ur.role_id FROM sys_user_role ur
        WHERE ur.user_id = #{userId} AND ur.is_deleted = 0
        ORDER BY ur.role_id ASC`,
  `        SELECT ur.role_id
        FROM sys_user_role ur
        INNER JOIN sys_role r ON r.id = ur.role_id
        WHERE ur.user_id = #{userId}
          AND ur.is_deleted = 0
          AND r.is_deleted = 0
        ORDER BY ur.role_id ASC`,
  'INNER JOIN sys_role r ON r.id = ur.role_id\n        WHERE ur.user_id = #{userId}\n          AND ur.is_deleted = 0');

// 3. LD-T9：把"删除角色撤销令牌"改为"变更角色权限撤销持有者令牌"
patch('guarantee-system/src/test/java/com/guarantee/system/service/LogicalDeleteServiceIntegrationTest.java',
  `        // 角色：先解绑用户（§6.2 要求无用户持有才允许删除），再删除 → 仍应触发撤销调用
        jdbc.update("UPDATE sys_user_role SET is_deleted = 1, deleted_at = NOW(6) WHERE role_id = ?", roleId);
        revoker.clear();
        RoleVO role = roleService.delete(roleId, adminUserId());
        assertThat(role.getIsDeleted()).isEqualTo(1);
        assertThat(revoker.reasons).as("删除角色后必须撤销持有者令牌，使权限变更立即生效")
                .anyMatch(r -> r.contains("角色被删除"));`,
  `        // 角色：§6.2 要求"无未删除用户持有"才允许删除，因此删除角色时必然没有持有者可撤；
        // 真正需要撤销令牌的是"变更角色权限"（SYS-C-07），这里断言该路径；
        // 删除角色这一路径的撤销调用仍然保留在实现里（供直连/历史数据场景兜底）。
        revoker.clear();
        RoleVO role = roleService.assignPermissions(P + "t9r", List.of("system:permission:view"));
        assertThat(role.getId()).isEqualTo(roleId);
        assertThat(revoker.revoked).as("角色权限变更必须撤销所有持有者的令牌（否则权限变更不生效）")
                .contains(userId);

        // 解绑后删除角色（无持有者）→ 删除成功
        jdbc.update("UPDATE sys_user_role SET is_deleted = 1, deleted_at = NOW(6) WHERE role_id = ?", roleId);
        revoker.clear();
        RoleVO deletedRole = roleService.delete(roleId, adminUserId());
        assertThat(deletedRole.getIsDeleted()).isEqualTo(1);`,
  '角色权限变更必须撤销所有持有者的令牌');

// 4. 改写器单测：空白归一化
patch('guarantee-system/src/test/java/com/guarantee/system/mybatis/LogicalDeleteSqlRewriterTest.java',
  `    void injectsJoinConditionIntoOnClause() {
        String sql = "SELECT u.id, o.org_name FROM sys_user u "
                + "LEFT JOIN sys_org o ON o.id = u.org_id WHERE u.status = 1";
        String rewritten = LogicalDeleteSqlRewriter.rewrite(sql);`,
  `    void injectsJoinConditionIntoOnClause() {
        String sql = "SELECT u.id, o.org_name FROM sys_user u "
                + "LEFT JOIN sys_org o ON o.id = u.org_id WHERE u.status = 1";
        // 注入会在关键字前留一个空格，断言前统一把连续空白压成一个空格
        String rewritten = LogicalDeleteSqlRewriter.rewrite(sql).replaceAll("\\\\s+", " ");`,
  'replaceAll("\\\\s+", " ")');

console.log('done');
