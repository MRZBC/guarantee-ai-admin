package com.guarantee.web.init;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限码与角色-权限的**幂等补数**（SYS-P-04 / SYS-P-13 / RK-03）。
 *
 * <p><b>为什么必须有这个组件</b>：{@code DataInitializer} 在 {@code sys_user} 非空时直接
 * 返回，存量库永远不会执行权限种子代码。新增的 16 条权限码如果只写在种子逻辑里，
 * 存量库升级后所有 {@code system:*} 校验点都会因"权限码不存在"而全部拒绝，
 * 表现为"功能不可用"（RK-03）。</p>
 *
 * <p><b>行为</b>：</p>
 * <ol>
 *   <li>补齐 {@link PermissionCatalog#PERMISSIONS} 中缺失的权限码（按 perm_code 判定，不覆盖已有名称）；</li>
 *   <li>按 {@link PermissionCatalog#ROLE_PERMISSIONS} 补齐缺失的角色-权限绑定（只增不减，
 *       不删除管理员的既有自定义授权）；</li>
 *   <li>对权限矩阵做一致性自检并告警：ANALYST / VIEWER 不得持有任何写权限或
 *       {@code system:audit:view}（D-1a / SYS-P-12）。</li>
 * </ol>
 *
 * <p>整个组件只触碰 {@code sys_permission} / {@code sys_role} / {@code sys_role_permission}
 * 三张表，不做任何业务数据处理。用 {@code @Order} 保证它在 {@code DataInitializer} **之后**
 * 执行，避免空库场景下角色 id 还没建出来。</p>
 */
@Component
@Order(100)
public class PermissionSyncInitializer implements org.springframework.boot.ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PermissionSyncInitializer.class);

    /** 权限网格自检中禁止出现在 ANALYST / VIEWER 上的权限码。 */
    private static final Set<String> READ_ONLY_FORBIDDEN = Set.of("system:audit:view", "ai:system:write");

    private final JdbcTemplate jdbcTemplate;

    public PermissionSyncInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(org.springframework.boot.ApplicationArguments args) {
        List<String> addedPermissions = syncPermissions();
        List<String> addedBindings = syncRolePermissions();
        verifyMatrix();

        if (addedPermissions.isEmpty() && addedBindings.isEmpty()) {
            log.info("权限码与角色权限已是最新，无需补数（共 {} 条权限码）",
                    PermissionCatalog.PERMISSIONS.length);
        } else {
            log.info("权限补数完成：新增权限码 {} 条 {}，新增角色权限绑定 {} 条",
                    addedPermissions.size(), addedPermissions, addedBindings.size());
        }
    }

    /** 补齐缺失的权限码，返回本次新增的权限编码。 */
    private List<String> syncPermissions() {
        Map<String, Long> existing = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT id, perm_code FROM sys_permission",
                rs -> {
                    existing.put(rs.getString("perm_code"), rs.getLong("id"));
                });

        int nextSortNo = existing.size();
        List<String> added = new ArrayList<>();
        for (String[] permission : PermissionCatalog.PERMISSIONS) {
            String code = permission[0];
            if (existing.containsKey(code)) {
                continue;
            }
            nextSortNo++;
            jdbcTemplate.update("""
                    INSERT INTO sys_permission (perm_code, perm_name, perm_type, parent_id, path, sort_no)
                    VALUES (?, ?, ?, 0, ?, ?)
                    """, code, permission[1], permission[2] == null ? "BUTTON" : "MENU", permission[2], nextSortNo);
            added.add(code);
        }
        return added;
    }

    /** 补齐缺失的角色-权限绑定，返回本次新增的 "ROLE->perm" 描述。 */
    private List<String> syncRolePermissions() {
        Map<String, Long> roleIds = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT id, role_code FROM sys_role",
                rs -> {
                    roleIds.put(rs.getString("role_code"), rs.getLong("id"));
                });
        Map<String, Long> permissionIds = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT id, perm_code FROM sys_permission",
                rs -> {
                    permissionIds.put(rs.getString("perm_code"), rs.getLong("id"));
                });

        Set<String> existingBindings = new LinkedHashSet<>();
        jdbcTemplate.query("SELECT role_id, permission_id FROM sys_role_permission",
                rs -> {
                    existingBindings.add(rs.getLong("role_id") + ":" + rs.getLong("permission_id"));
                });

        List<String> added = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : PermissionCatalog.ROLE_PERMISSIONS.entrySet()) {
            String roleCode = entry.getKey();
            Long roleId = roleIds.get(roleCode);
            if (roleId == null) {
                log.warn("角色 {} 不存在，跳过其权限补数（存量库请确认 sys_role 是否被人工改动）", roleCode);
                continue;
            }
            for (String permCode : entry.getValue()) {
                Long permissionId = permissionIds.get(permCode);
                if (permissionId == null) {
                    log.warn("权限码 {} 不存在，跳过角色 {} 的绑定", permCode, roleCode);
                    continue;
                }
                String key = roleId + ":" + permissionId;
                if (existingBindings.contains(key)) {
                    continue;
                }
                jdbcTemplate.update("INSERT INTO sys_role_permission (role_id, permission_id) VALUES (?, ?)",
                        roleId, permissionId);
                existingBindings.add(key);
                added.add(roleCode + "->" + permCode);
            }
        }
        return added;
    }

    /**
     * 权限矩阵自检（SYS-P-13 的"启动自检告警"）。
     *
     * <p>只告警不阻断：矩阵不一致属于配置错误，但让服务起不来会让问题更难排查。
     * 两类不一致都会被明确打印出来，便于运维第一时间发现"角色被误授权"或"漏授权"。</p>
     */
    private void verifyMatrix() {
        for (Map.Entry<String, Set<String>> entry : PermissionCatalog.ROLE_PERMISSIONS.entrySet()) {
            String roleCode = entry.getKey();
            List<String> actual = jdbcTemplate.queryForList("""
                    SELECT p.perm_code
                    FROM sys_role r
                    INNER JOIN sys_role_permission rp ON rp.role_id = r.id
                    INNER JOIN sys_permission p ON p.id = rp.permission_id
                    WHERE r.role_code = ?
                    """, String.class, roleCode);
            Set<String> actualSet = new LinkedHashSet<>(actual);
            Set<String> expected = entry.getValue();

            List<String> missing = expected.stream().filter(c -> !actualSet.contains(c)).sorted().toList();
            List<String> extra = actualSet.stream().filter(c -> !expected.contains(c)).sorted().toList();
            if (!missing.isEmpty() || !extra.isEmpty()) {
                log.warn("权限矩阵与期望不一致 role={} 缺少={} 多出={}（可在系统管理页面或补数脚本中修正）",
                        roleCode, missing, extra);
            }

            // ANALYST / VIEWER 的写权限必须为 0（SYS-P-12）
            if ("ANALYST".equals(roleCode) || "VIEWER".equals(roleCode)) {
                List<String> writes = PermissionCatalog.writePermissions(actualSet);
                List<String> forbidden = READ_ONLY_FORBIDDEN.stream().filter(actualSet::contains).sorted().toList();
                if (!writes.isEmpty() || !forbidden.isEmpty()) {
                    log.error("安全告警：只读角色 {} 持有写权限/审计权限 写={} 禁止项={}（违反 D-1/D-1a/SYS-P-12）",
                            roleCode, writes, forbidden);
                }
            }
        }
    }
}
