/**
 * 批次 4（后端 A 部分）：Mapper 的删除/恢复/已删除读取 + includeDeleted 列表条件 + 接口方法声明。
 *
 * 依据：设计文档 §2.2b（统一写入口）/ §5.3 / §7 / §9.4.1 / §9.4.2；任务书 §4.2、§7
 *
 * 三条硬约束在代码里的落点：
 *   1. 三列必须在**同一条 UPDATE** 里写全（is_deleted / deleted_at / deleted_by）；
 *   2. deleted_at 用 NOW(6)（微秒），否则同一秒内重复删建会撞唯一键；
 *   3. 删除必须带 AND is_deleted = 0，重复删除的 ROW_COUNT() 才为 0，调用方能判并发。
 *
 * 幂等：已含 id="softDelete" 或软删除方法声明时跳过。
 * 用法：node scripts/patch-ld-batch4a.mjs
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const X = 'guarantee-system/src/main/resources/mapper/system/';
const J = 'guarantee-system/src/main/java/com/guarantee/system/mapper/';

const delBlock = (table) => `    <!--
      逻辑删除（设计 §9.4.1）：三列必须在**同一条语句**里写全。
      只写一半会让唯一键判定错乱，且现象与原因看起来无关，极难排查（风险 LD-R10）。
      deleted_at 必须用 NOW(6)（微秒精度），否则同一秒内"删除→重建→再删除"会撞唯一键；
      AND is_deleted = 0 让重复删除的 ROW_COUNT() 为 0，调用方据此判定并发冲突。
    -->
    <update id="softDelete">
        UPDATE ${table}
        SET is_deleted = 1,
            deleted_at = NOW(6),
            deleted_by = COALESCE(#{operatorId}, 'DB')
        WHERE id = #{id} AND is_deleted = 0
    </update>

    <!-- 恢复（设计 §9.4.2 / LD-04）：三列一起归零，只允许从"已删除"恢复 -->
    <update id="restore">
        UPDATE ${table}
        SET is_deleted = 0,
            deleted_at = NULL,
            deleted_by = 'DB'
        WHERE id = #{id} AND is_deleted = 1
    </update>
`;

const includedEntityBlock = (table, entityFqn, entityCols = true) => `
    <!--
      恢复前的读取：必须能看到已删除行。
      方法名以 IncludingDeleted 结尾 → 拦截器豁免注入（设计 §5.2 的豁免约定）。
    -->
    <select id="selectEntityByIdIncludingDeleted" resultType="${entityFqn}">
        SELECT ${entityCols ? '<include refid="entityCols"/>' : 'id'} FROM ${table} WHERE id = #{id}
    </select>
`;

const includeDeletedBranch = (alias) => `            <choose>
                <when test="q.includeDeleted != null and q.includeDeleted">
                    <!--
                      「显示已删除」视图（LD-04b）：显式放行 0/1 两种状态。
                      本语句已含 is_deleted，拦截器按约定跳过（避免重复条件），
                      因此默认视图的过滤责任仍在拦截器与显式条件上。
                    -->
                    AND ${alias}is_deleted IN (0, 1)
                </when>
            </choose>`;

// ---------------------------------------------------------------------
// 1. Mapper XML 尾部追加（删除/恢复/已删除读取/统计）
// ---------------------------------------------------------------------
const XML_TAILS = [
  [X + 'SysOrgMapper.xml',
    `${delBlock('sys_org')}${includedEntityBlock('sys_org', 'com.guarantee.system.entity.SysOrg')}
    <!-- 删除前置检查：机构下**未删除**的用户数（含停用用户，与停用检查的"启用用户"口径不同，§6.2） -->
    <select id="countUserByOrg" resultType="long">
        SELECT COUNT(*) FROM sys_user WHERE org_id = #{orgId} AND is_deleted = 0
    </select>

</mapper>`],

  [X + 'SysDepartmentMapper.xml',
    `${delBlock('sys_department')}
    <!-- 恢复前的读取（IncludingDeleted 后缀 → 拦截器豁免） -->
    <select id="selectEntityByIdIncludingDeleted" resultType="com.guarantee.system.entity.SysDepartment">
        SELECT id, dept_code, dept_name, org_id, parent_id, status, sort_no, created_at, updated_at,
               is_deleted AS isDeleted, deleted_at AS deletedAt, deleted_by AS deletedBy
        FROM sys_department WHERE id = #{id}
    </select>

    <!-- 删除前置检查：部门下**未删除**的用户数（含停用用户，§6.2） -->
    <select id="countUserByDept" resultType="long">
        SELECT COUNT(*) FROM sys_user WHERE dept_id = #{deptId} AND is_deleted = 0
    </select>

</mapper>`],

  [X + 'SysUserMapper.xml',
    `${delBlock('sys_user')}${includedEntityBlock('sys_user', 'com.guarantee.system.entity.SysUser')}
</mapper>`],

  [X + 'SysRoleMapper.xml',
    `${delBlock('sys_role')}${includedEntityBlock('sys_role', 'com.guarantee.system.entity.SysRole')}
</mapper>`],

  [X + 'InsuranceTypeMapper.xml',
    `${delBlock('insurance_type')}
    <!-- 恢复前的读取（IncludingDeleted 后缀 → 拦截器豁免） -->
    <select id="selectByIdIncludingDeleted" resultType="com.guarantee.system.entity.InsuranceType">
        SELECT <include refid="cols"/> FROM insurance_type WHERE id = #{id}
    </select>

</mapper>`],
];

// ---------------------------------------------------------------------
// 2. queryWhere 的 includeDeleted 条件
// ---------------------------------------------------------------------
const QUERY_WHERE_EDITS = [
  [X + 'SysOrgMapper.xml',
    `    <sql id="queryWhere">
        <where>
            <if test="q.orgName != null and q.orgName != ''">`,
    `    <sql id="queryWhere">
        <where>
${includeDeletedBranch('')}
            <if test="q.orgName != null and q.orgName != ''">`],

  [X + 'SysDepartmentMapper.xml',
    `    <sql id="queryWhere">
        <where>
            <if test="q.orgId != null">`,
    `    <sql id="queryWhere">
        <where>
${includeDeletedBranch('d.')}
            <if test="q.orgId != null">`],

  [X + 'SysUserMapper.xml',
    `    <sql id="queryWhere">
        <where>
            <if test="q.username != null and q.username != ''">`,
    `    <sql id="queryWhere">
        <where>
${includeDeletedBranch('u.')}
            <if test="q.username != null and q.username != ''">`],

  [X + 'SysRoleMapper.xml',
    `    <sql id="queryWhere">
        <where>
            <if test="q.roleCode != null and q.roleCode != ''">`,
    `    <sql id="queryWhere">
        <where>
${includeDeletedBranch('r.')}
            <if test="q.roleCode != null and q.roleCode != ''">`],

  [X + 'InsuranceTypeMapper.xml',
    `    <sql id="queryWhere">
        <where>
            <if test="q.typeName != null and q.typeName != ''">`,
    `    <sql id="queryWhere">
        <where>
${includeDeletedBranch('')}
            <if test="q.typeName != null and q.typeName != ''">`],
];

// ---------------------------------------------------------------------
// 3. Mapper 接口：新增统计方法 + 软删除/恢复/已删除读取声明
// ---------------------------------------------------------------------
const IFACE_STAT_EDITS = [
  [J + 'SysOrgMapper.java',
    `    long countOrderByOrg(@Param("orgId") Long orgId);`,
    `    long countOrderByOrg(@Param("orgId") Long orgId);

    /** 删除前置检查：机构下未删除的用户数（含停用用户）。 */
    long countUserByOrg(@Param("orgId") Long orgId);`,
    'countUserByOrg'],

  [J + 'SysDepartmentMapper.java',
    `    long countEnabledUserByDept(@Param("deptId") Long deptId);`,
    `    long countEnabledUserByDept(@Param("deptId") Long deptId);

    /** 删除前置检查：部门下未删除的用户数（含停用用户）。 */
    long countUserByDept(@Param("deptId") Long deptId);`,
    'countUserByDept'],
];

const softDeleteMethods = (entity, includingMethod) => `
    // ---------------- 逻辑删除（LD-02 / LD-04） ----------------

    /**
     * 逻辑删除：一条语句写全 is_deleted / deleted_at / deleted_by（设计 §9.4.1）。
     *
     * @param operatorId 应用侧传 sys_user.id 的字符串形式；直连/未知时传 null，落默认 'DB'
     * @return 受影响行数：0 表示该行不存在或已被删除（并发冲突）
     */
    int softDelete(@Param("id") Long id, @Param("operatorId") String operatorId);

    /** 恢复：is_deleted = 0 且 deleted_at = NULL；只允许从"已删除"恢复。 */
    int restore(@Param("id") Long id);

    /** 恢复前的读取：包含已删除行（方法名后缀触发拦截器豁免）。 */
    ${entity} ${includingMethod}(@Param("id") Long id);`;

const IFACE_CRUD_EDITS = [
  [J + 'SysOrgMapper.java',
    `    /** 供其它模块按主键读取机构实体。 */
    SysOrg selectEntityById(@Param("id") Long id);`,
    `    /** 供其它模块按主键读取机构实体。 */
    SysOrg selectEntityById(@Param("id") Long id);
${softDeleteMethods('SysOrg', 'selectEntityByIdIncludingDeleted')}`],

  [J + 'SysDepartmentMapper.java',
    `    SysDepartment selectEntityById(@Param("id") Long id);`,
    `    SysDepartment selectEntityById(@Param("id") Long id);
${softDeleteMethods('SysDepartment', 'selectEntityByIdIncludingDeleted')}`],

  [J + 'SysUserMapper.java',
    `    /** 写操作/危险保护使用：按主键读取实体（含 status 与 org_id）。 */
    SysUser selectEntityById(@Param("id") Long id);`,
    `    /** 写操作/危险保护使用：按主键读取实体（含 status 与 org_id）。 */
    SysUser selectEntityById(@Param("id") Long id);
${softDeleteMethods('SysUser', 'selectEntityByIdIncludingDeleted')}`],

  [J + 'SysRoleMapper.java',
    `    SysRole selectEntityById(@Param("id") Long id);`,
    `    SysRole selectEntityById(@Param("id") Long id);
${softDeleteMethods('SysRole', 'selectEntityByIdIncludingDeleted')}`],

  [J + 'InsuranceTypeMapper.java',
    `    InsuranceType selectById(@Param("id") Long id);`,
    `    InsuranceType selectById(@Param("id") Long id);
${softDeleteMethods('InsuranceType', 'selectByIdIncludingDeleted')}`],
];

// ---------------------------------------------------------------------
function run(edits) {
  for (const [rel, anchor, repl, marker] of edits) {
    const path = join(root, rel);
    const src = readFileSync(path, 'utf8');
    if (marker && src.includes(marker)) {
      console.log(`skip: ${rel} (${marker})`);
      continue;
    }
    if (!src.includes(anchor)) {
      throw new Error(`锚点未命中: ${rel}\n---\n${anchor.slice(0, 160)}`);
    }
    writeFileSync(path, src.replace(anchor, repl), 'utf8');
    console.log(`patched: ${rel}`);
  }
}

// XML 尾部
for (const [rel, body] of XML_TAILS) {
  const path = join(root, rel);
  const src = readFileSync(path, 'utf8');
  if (src.includes('id="softDelete"')) {
    console.log(`skip xml: ${rel}`);
    continue;
  }
  if (!src.includes('</mapper>')) {
    throw new Error(`缺少 </mapper>: ${rel}`);
  }
  writeFileSync(path, src.replace('</mapper>', body), 'utf8');
  console.log(`xml updated: ${rel}`);
}

run(QUERY_WHERE_EDITS.map(([f, a, b]) => [f, a, b, 'includeDeleted != null and q.includeDeleted']));
run(IFACE_STAT_EDITS);
run(IFACE_CRUD_EDITS.map(([f, a, b]) => [f, a, b, 'int softDelete(']));
console.log('batch4a done');
