package com.guarantee.web.init;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 角色 / 权限矩阵的幂等落库（SYS-P-04 / SYS-P-13 / SYS-P-26）。
 *
 * <p><b>为什么必须抽出来</b>：权限码与角色-权限映射会被两个入口写入——</p>
 * <ul>
 *   <li>{@code DataInitializer}（空库全量初始化）；</li>
 *   <li>{@link PermissionSyncInitializer}（存量库幂等补数）。</li>
 * </ul>
 * 原来的实现里 {@code DataInitializer} 用"先取角色 id、再按 {@code PERMISSIONS} 的
 * 位置计算权限 id"的方式批量 INSERT，隐含了"角色表与权限表都是我这次刚建的、id 从 1 开始"
 * 这一前提。一旦 sys_permission 已有数据（例如上一次启动的补数留下了 31 条），
 * 就会撞主键 {@code Duplicate entry '1' for key 'sys_permission.PRIMARY'}，
 * 整个初始化事务回滚，表现为"空库启动直接失败"。</p>
 *
 * <p>本类把三件事都改成**按业务键幂等**：权限码、角色编码、角色-权限对。
 * 因此两个入口无论谁先谁后、执行多少次，结果都收敛到 {@link PermissionCatalog} 的矩阵。</p>
 */
final class RolePermissionSeeder {

    private static final Logger log = LoggerFactory.getLogger(RolePermissionSeeder.class);

    private final JdbcTemplate jdbcTemplate;

    RolePermissionSeeder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 确保权限码、角色、角色-权限绑定都存在且与 {@link PermissionCatalog} 一致。
     *
     * @return 本次新增的权限-角色绑定描述（用于日志）
     */
    List<String> seed() {
        ensurePermissions();
        ensureRoles();
        return ensureRolePermissions();
    }

    /** 权限主数据：按 perm_code 幂等补齐，不覆盖已有名称与路由。 */
    private void ensurePermissions() {
        Map<String, Long> existing = loadMap("SELECT id, perm_code FROM sys_permission", "perm_code");
        int nextSortNo = existing.size();
        List<Object[]> batch = new ArrayList<>();
        for (String[] permission : PermissionCatalog.PERMISSIONS) {
            if (existing.containsKey(permission[0])) {
                continue;
            }
            nextSortNo++;
            batch.add(new Object[]{
                    permission[0], permission[1],
                    permission[2] == null ? "BUTTON" : "MENU",
                    permission[2], nextSortNo});
        }
        if (!batch.isEmpty()) {
            // 不指定 id：交给 AUTO_INCREMENT，避免与既有行撞主键（这正是原实现的缺陷）
            jdbcTemplate.batchUpdate("""
                    INSERT INTO sys_permission (perm_code, perm_name, perm_type, parent_id, path, sort_no)
                    VALUES (?, ?, ?, 0, ?, ?)
                    """, batch);
        }
    }

    /** 角色：按 role_code 幂等补齐。 */
    private void ensureRoles() {
        Map<String, Long> existing = loadMap("SELECT id, role_code FROM sys_role", "role_code");
        List<Object[]> batch = new ArrayList<>();
        for (String[] role : PermissionCatalog.ROLES) {
            if (existing.containsKey(role[0])) {
                continue;
            }
            batch.add(new Object[]{role[0], role[1], role[2], 1});
        }
        if (!batch.isEmpty()) {
            jdbcTemplate.batchUpdate("""
                    INSERT INTO sys_role (role_code, role_name, description, status)
                    VALUES (?, ?, ?, ?)
                    """, batch);
        }
    }

    /** 角色-权限：只补缺失的绑定，不删除既有的自定义授权。 */
    private List<String> ensureRolePermissions() {
        Map<String, Long> roleIds = loadMap("SELECT id, role_code FROM sys_role", "role_code");
        Map<String, Long> permissionIds = loadMap("SELECT id, perm_code FROM sys_permission", "perm_code");

        Map<String, Long> existingBindings = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT role_id, permission_id FROM sys_role_permission", rs -> {
            existingBindings.put(rs.getLong("role_id") + ":" + rs.getLong("permission_id"),
                    rs.getLong("permission_id"));
        });

        List<Object[]> batch = new ArrayList<>();
        List<String> added = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : PermissionCatalog.ROLE_PERMISSIONS.entrySet()) {
            String roleCode = entry.getKey();
            Long roleId = roleIds.get(roleCode);
            if (roleId == null) {
                log.warn("角色 {} 不存在，跳过其权限分配", roleCode);
                continue;
            }
            for (String permCode : entry.getValue()) {
                Long permissionId = permissionIds.get(permCode);
                if (permissionId == null) {
                    log.warn("权限码 {} 不存在，跳过角色 {} 的绑定", permCode, roleCode);
                    continue;
                }
                String key = roleId + ":" + permissionId;
                if (existingBindings.containsKey(key)) {
                    continue;
                }
                batch.add(new Object[]{roleId, permissionId});
                existingBindings.put(key, permissionId);
                added.add(roleCode + "->" + permCode);
            }
        }
        if (!batch.isEmpty()) {
            jdbcTemplate.batchUpdate(
                    "INSERT INTO sys_role_permission (role_id, permission_id) VALUES (?, ?)", batch);
        }
        return added;
    }

    private Map<String, Long> loadMap(String sql, String keyColumn) {
        Map<String, Long> map = new LinkedHashMap<>();
        jdbcTemplate.query(sql, rs -> {
            map.put(rs.getString(keyColumn), rs.getLong("id"));
        });
        return map;
    }
}
