/**
 * 批次 2：鉴权路径过滤（任务书 §4.3 的 9 处 + 关联的统计/唯一性校验）+ 关联表 UPSERT 改造（§5）。
 *
 * 依据：docs/DEC-逻辑删除设计方案.md v2.1 §4（关联表 UPSERT）/ §5.3（显式过滤场景）
 *
 * 为什么必须显式写而不是只靠拦截器：
 *   §4.3 的 listPermissionCodesByUserId 决定用户权限集合——漏加 = 被删除角色的权限
 *   仍会签发进 JWT，是**提权漏洞**；§5.1 明确要求"手工改 + 机制兜底，两层都要"。
 *   显式条件同时让"关闭拦截器开关"这一回滚路径不会降级为安全漏洞。
 *
 * 幂等：锚点已含 is_deleted 则跳过。
 * 用法：node scripts/patch-ld-batch2.mjs
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const P = {
  userXml: 'guarantee-system/src/main/resources/mapper/system/SysUserMapper.xml',
  roleXml: 'guarantee-system/src/main/resources/mapper/system/SysRoleMapper.xml',
  userMapper: 'guarantee-system/src/main/java/com/guarantee/system/mapper/SysUserMapper.java',
  roleMapper: 'guarantee-system/src/main/java/com/guarantee/system/mapper/SysRoleMapper.java',
  userService: 'guarantee-system/src/main/java/com/guarantee/system/service/UserService.java',
  roleService: 'guarantee-system/src/main/java/com/guarantee/system/service/RoleService.java',
};

/** [relPath, 锚点, 替换, 说明] */
const EDITS = [
  // ================= SysUserMapper.xml =================
  [P.userXml,
    `        WHERE r.role_code = #{roleCode} AND u.status = 1
    </select>`,
    `        WHERE r.role_code = #{roleCode} AND u.status = 1
          AND u.is_deleted = 0 AND r.is_deleted = 0 AND ur.is_deleted = 0
    </select>`,
    'countEnabledUsersByRoleCode：统计口径只算未删除的用户/角色/绑定'],

  [P.userXml,
    `        WHERE r.role_code = 'ADMIN' AND u.status = 1 AND u.id &lt;&gt; #{excludeUserId}
    </select>`,
    `        WHERE r.role_code = 'ADMIN' AND u.status = 1 AND u.id &lt;&gt; #{excludeUserId}
          AND u.is_deleted = 0 AND r.is_deleted = 0 AND ur.is_deleted = 0
    </select>`,
    'countOtherEnabledAdmins：危险动作保护必须排除已删除的用户与绑定（§4.3）'],

  [P.userXml,
    `        SELECT ur.role_id FROM sys_user_role ur WHERE ur.user_id = #{userId} ORDER BY ur.role_id ASC`,
    `        SELECT ur.role_id FROM sys_user_role ur
        WHERE ur.user_id = #{userId} AND ur.is_deleted = 0
        ORDER BY ur.role_id ASC`,
    'listRoleIdsByUserId：角色 id 列表排除已删除绑定（§4.3）'],

  [P.userXml,
    `        SELECT DISTINCT ur.user_id
        FROM sys_user_role ur
        INNER JOIN sys_role r ON r.id = ur.role_id
        WHERE r.role_code = #{roleCode}
        ORDER BY ur.user_id ASC
    </select>`,
    `        SELECT DISTINCT ur.user_id
        FROM sys_user_role ur
        INNER JOIN sys_role r ON r.id = ur.role_id
        WHERE r.role_code = #{roleCode}
          AND ur.is_deleted = 0 AND r.is_deleted = 0
        ORDER BY ur.user_id ASC
    </select>`,
    'listUserIdsByRoleCode：令牌撤销范围排除已删除绑定'],

  [P.userXml,
    `        FROM sys_user
        WHERE username = #{username}
    </select>`,
    `        FROM sys_user
        WHERE username = #{username}
          AND is_deleted = 0
    </select>`,
    'LD-05b：改为复合唯一键后同一 username 可能有多行（含已删除），必须显式过滤，不能再依赖"用户名唯一"'],

  [P.userXml,
    `        ORDER BY ur.user_id ASC, r.id ASC
    </select>`,
    `          AND ur.is_deleted = 0
          AND r.is_deleted = 0
        ORDER BY ur.user_id ASC, r.id ASC
    </select>`,
    'selectRoleRefsByUserIds：角色回填排除已删除绑定与已删除角色（§4.3）'],

  [P.userXml,
    `        WHERE ur.user_id = #{userId}
          AND r.status = 1
        ORDER BY r.role_code ASC`,
    `        WHERE ur.user_id = #{userId}
          AND r.status = 1
          AND ur.is_deleted = 0
          AND r.is_deleted = 0
        ORDER BY r.role_code ASC`,
    'listRoleCodesByUserId：鉴权路径（角色判定）'],

  [P.userXml,
    `        WHERE ur.user_id = #{userId}
        ORDER BY p.perm_code ASC`,
    `        WHERE ur.user_id = #{userId}
          AND ur.is_deleted = 0
          AND r.is_deleted = 0
          AND rp.is_deleted = 0
          AND p.is_deleted = 0
        ORDER BY p.perm_code ASC`,
    'listPermissionCodesByUserId：**最关键处**，决定用户权限集合，漏加 = 提权'],

  // 关联表 UPSERT（SysUserMapper.xml）
  [P.userXml,
    `    <!-- 角色分配：先清空再插入，保证"变更后角色"与提案一致 -->
    <delete id="deleteUserRoles">
        DELETE FROM sys_user_role WHERE user_id = #{userId}
    </delete>

    <insert id="insertUserRoles">
        INSERT INTO sys_user_role (user_id, role_id) VALUES
        <foreach collection="roleIds" item="roleId" separator=",">
            (#{userId}, #{roleId})
        </foreach>
    </insert>

    <delete id="deleteUserRoleByCode">
        DELETE ur FROM sys_user_role ur
        INNER JOIN sys_role r ON r.id = ur.role_id
        WHERE ur.user_id = #{userId} AND r.role_code = #{roleCode}
    </delete>`,
    `    <!--
      角色分配：从"先清后插"改为 UPSERT（设计 §4.2）。
      逻辑删除后已绑定的行仍在表里，(user_id, role_id) 唯一键会拒绝新的 INSERT，
      因此必须用"标记不在目标集合中的绑定 + 命中唯一键则复活"两条语句表达最终状态。
      **两条语句顺序无关**，比原来的先清后插更稳健（原来插入失败会清空用户角色且无法恢复）。
    -->
    <update id="softDeleteUserRolesNotIn">
        UPDATE sys_user_role
        SET is_deleted = 1,
            deleted_at = NOW(6),
            deleted_by = COALESCE(#{operatorId}, 'DB')
        WHERE user_id = #{userId}
          AND is_deleted = 0
        <if test="roleIds != null and roleIds.size() > 0">
            AND role_id NOT IN
            <foreach collection="roleIds" item="roleId" open="(" separator="," close=")">
                #{roleId}
            </foreach>
        </if>
    </update>

    <insert id="upsertUserRoles">
        INSERT INTO sys_user_role (user_id, role_id, is_deleted) VALUES
        <foreach collection="roleIds" item="roleId" separator=",">
            (#{userId}, #{roleId}, 0)
        </foreach>
        ON DUPLICATE KEY UPDATE is_deleted = 0, deleted_at = NULL, deleted_by = 'DB'
    </insert>

    <!-- 按角色编码解绑（软删除，保留历史；deleted_by 记录操作者） -->
    <update id="softDeleteUserRoleByCode">
        UPDATE sys_user_role ur
        INNER JOIN sys_role r ON r.id = ur.role_id
        SET ur.is_deleted = 1,
            ur.deleted_at = NOW(6),
            ur.deleted_by = COALESCE(#{operatorId}, 'DB')
        WHERE ur.user_id = #{userId}
          AND r.role_code = #{roleCode}
          AND ur.is_deleted = 0
    </update>`,
    '关联表 UPSERT 改造（LD-R7 / LD-T5）'],

  // ================= SysRoleMapper.xml =================
  [P.roleXml,
    `        </foreach>
        GROUP BY rp.role_id
    </select>`,
    `        </foreach>
          AND rp.is_deleted = 0
        GROUP BY rp.role_id
    </select>`,
    'countPermissionsByRoleIds：权限数只算未删除绑定'],

  [P.roleXml,
    `        FROM sys_user_role ur
        INNER JOIN sys_user u ON u.id = ur.user_id
        WHERE ur.role_id IN
        <foreach collection="roleIds" item="roleId" open="(" separator="," close=")">
            #{roleId}
        </foreach>
        GROUP BY ur.role_id`,
    `        FROM sys_user_role ur
        INNER JOIN sys_user u ON u.id = ur.user_id
        WHERE ur.role_id IN
        <foreach collection="roleIds" item="roleId" open="(" separator="," close=")">
            #{roleId}
        </foreach>
          AND ur.is_deleted = 0
          AND u.is_deleted = 0
        GROUP BY ur.role_id`,
    'countUsersByRoleIds：用户数统计排除已删除绑定与已删除用户（§4.3）'],

  [P.roleXml,
    `        ORDER BY rp.role_id ASC, p.sort_no ASC, p.id ASC
    </select>`,
    `          AND rp.is_deleted = 0
          AND p.is_deleted = 0
        ORDER BY rp.role_id ASC, p.sort_no ASC, p.id ASC
    </select>`,
    'selectPermissionRefsByRoleIds：权限回填（§4.3）'],

  [P.roleXml,
    `        WHERE r.role_code = #{roleCode}
        ORDER BY ur.user_id ASC
    </select>`,
    `        WHERE r.role_code = #{roleCode}
          AND ur.is_deleted = 0
          AND r.is_deleted = 0
        ORDER BY ur.user_id ASC
    </select>`,
    'selectUserIdsByRoleCode：令牌撤销范围（§4.3）'],

  [P.roleXml,
    `    <delete id="deleteRolePermissions">
        DELETE FROM sys_role_permission WHERE role_id = #{roleId}
    </delete>

    <insert id="insertRolePermissions">
        INSERT INTO sys_role_permission (role_id, permission_id) VALUES
        <foreach collection="permissionIds" item="permissionId" separator=",">
            (#{roleId}, #{permissionId})
        </foreach>
    </insert>`,
    `    <!-- 角色授权同构改造：先清后插 -> UPSERT（设计 §4.2） -->
    <update id="softDeleteRolePermissionsNotIn">
        UPDATE sys_role_permission
        SET is_deleted = 1,
            deleted_at = NOW(6),
            deleted_by = COALESCE(#{operatorId}, 'DB')
        WHERE role_id = #{roleId}
          AND is_deleted = 0
        <if test="permissionIds != null and permissionIds.size() > 0">
            AND permission_id NOT IN
            <foreach collection="permissionIds" item="permissionId" open="(" separator="," close=")">
                #{permissionId}
            </foreach>
        </if>
    </update>

    <insert id="upsertRolePermissions">
        INSERT INTO sys_role_permission (role_id, permission_id, is_deleted) VALUES
        <foreach collection="permissionIds" item="permissionId" separator=",">
            (#{roleId}, #{permissionId}, 0)
        </foreach>
        ON DUPLICATE KEY UPDATE is_deleted = 0, deleted_at = NULL, deleted_by = 'DB'
    </insert>`,
    '角色-权限关联表 UPSERT 改造'],

  [P.roleXml,
    `        SELECT id FROM sys_permission
        WHERE perm_code IN`,
    `        SELECT id FROM sys_permission
        WHERE is_deleted = 0
          AND perm_code IN`,
    'selectPermissionIdsByCodes：唯一性/授权校验只认未删除权限'],

  [P.roleXml,
    `        FROM sys_permission
        WHERE perm_code IN`,
    `        FROM sys_permission
        WHERE is_deleted = 0
          AND perm_code IN`,
    'selectPermissionEntitiesByCodes：授权校验只认未删除权限'],

  [P.roleXml,
    `        SELECT id FROM sys_role
        WHERE role_code IN`,
    `        SELECT id FROM sys_role
        WHERE is_deleted = 0
          AND role_code IN`,
    'selectIdsByCodes：角色解析排除已删除角色'],

  [P.roleXml,
    `        SELECT role_code FROM sys_role
        WHERE status = 1 AND role_code IN`,
    `        SELECT role_code FROM sys_role
        WHERE status = 1 AND is_deleted = 0 AND role_code IN`,
    'selectEnabledCodes：角色分配校验排除已删除角色（§5.3 唯一性/校验口径）'],

  [P.roleXml,
    `        SELECT id, role_code, role_name, description, status, created_at, updated_at,
        is_deleted AS isDeleted, deleted_at AS deletedAt, deleted_by AS deletedBy
        FROM sys_role WHERE role_code = #{roleCode}`,
    `        SELECT id, role_code, role_name, description, status, created_at, updated_at,
        is_deleted AS isDeleted, deleted_at AS deletedAt, deleted_by AS deletedBy
        FROM sys_role WHERE role_code = #{roleCode} AND is_deleted = 0`,
    'selectEntityByCode：角色编码查重/解析必须排除已删除角色'],

  [P.roleXml,
    `        SELECT id, role_code, role_name, description, status, created_at, updated_at,
        is_deleted AS isDeleted, deleted_at AS deletedAt, deleted_by AS deletedBy
        FROM sys_role WHERE id = #{id}`,
    `        SELECT id, role_code, role_name, description, status, created_at, updated_at,
        is_deleted AS isDeleted, deleted_at AS deletedAt, deleted_by AS deletedBy
        FROM sys_role WHERE id = #{id} AND is_deleted = 0`,
    'selectEntityById：已删除角色不参与改/授权等写操作'],

  // ================= Mapper 接口 =================
  [P.userMapper,
    `    int deleteUserRoles(@Param("userId") Long userId);

    int insertUserRoles(@Param("userId") Long userId, @Param("roleIds") List<Long> roleIds);

    int deleteUserRoleByCode(@Param("userId") Long userId, @Param("roleCode") String roleCode);`,
    `    /** 把不在目标集合中的用户-角色绑定置为已删除（设计 §4.2 的 UPSERT 前半段）。 */
    int softDeleteUserRolesNotIn(@Param("userId") Long userId,
                                 @Param("roleIds") List<Long> roleIds,
                                 @Param("operatorId") String operatorId);

    /** 命中唯一键则复活为有效绑定，否则新建（设计 §4.2 的 UPSERT 后半段）。 */
    int upsertUserRoles(@Param("userId") Long userId, @Param("roleIds") List<Long> roleIds);

    /** 按角色编码解绑（软删除）。 */
    int softDeleteUserRoleByCode(@Param("userId") Long userId,
                                 @Param("roleCode") String roleCode,
                                 @Param("operatorId") String operatorId);`,
    'SysUserMapper 接口：关联表 UPSERT'],

  [P.roleMapper,
    `    int deleteRolePermissions(@Param("roleId") Long roleId);

    int insertRolePermissions(@Param("roleId") Long roleId,
                              @Param("permissionIds") List<Long> permissionIds);`,
    `    /** 把不在目标集合中的角色-权限绑定置为已删除。 */
    int softDeleteRolePermissionsNotIn(@Param("roleId") Long roleId,
                                       @Param("permissionIds") List<Long> permissionIds,
                                       @Param("operatorId") String operatorId);

    /** 命中唯一键则复活为有效绑定，否则新建。 */
    int upsertRolePermissions(@Param("roleId") Long roleId,
                              @Param("permissionIds") List<Long> permissionIds);`,
    'SysRoleMapper 接口：关联表 UPSERT'],

  // ================= Service =================
  [P.userService,
    `        List<Long> roleIds = resolveRoleIds(normalized);
        sysUserMapper.deleteUserRoles(id);
        if (!roleIds.isEmpty()) {
            sysUserMapper.insertUserRoles(id, roleIds);
        }`,
    `        List<Long> roleIds = resolveRoleIds(normalized);
        // 关联表改造（设计 §4）：不再"先清后插"——逻辑删除后旧行仍在表中，
        // (user_id, role_id) 唯一键会拒绝 INSERT。改为一次 UPSERT 表达最终状态，两条语句顺序无关。
        String operator = operatorUserId == null ? null : String.valueOf(operatorUserId);
        sysUserMapper.softDeleteUserRolesNotIn(id, roleIds, operator);
        if (!roleIds.isEmpty()) {
            sysUserMapper.upsertUserRoles(id, roleIds);
        }`,
    'UserService.assignRoles：UPSERT 改造'],

  [P.roleService,
    `        sysRoleMapper.deleteRolePermissions(existing.getId());
        if (!permissionIds.isEmpty()) {
            sysRoleMapper.insertRolePermissions(existing.getId(), permissionIds);
        }`,
    `        // 关联表改造（设计 §4）：先清后插会撞 (role_id, permission_id) 唯一键，改为 UPSERT。
        sysRoleMapper.softDeleteRolePermissionsNotIn(existing.getId(), permissionIds, null);
        if (!permissionIds.isEmpty()) {
            sysRoleMapper.upsertRolePermissions(existing.getId(), permissionIds);
        }`,
    'RoleService.assignPermissions：UPSERT 改造'],
];

let applied = 0;
for (const [rel, anchor, repl, why] of EDITS) {
  const path = join(root, rel);
  const src = readFileSync(path, 'utf8');
  if (!src.includes(anchor)) {
    if (src.includes(repl.split('\n')[0].trim()) && repl.includes('is_deleted')) {
      console.log(`skip (already applied): ${rel} :: ${why}`);
      continue;
    }
    throw new Error(`锚点未命中: ${rel} :: ${why}`);
  }
  writeFileSync(path, src.replace(anchor, repl), 'utf8');
  applied++;
  console.log(`applied: ${why}`);
}
console.log(`done, applied=${applied}`);
