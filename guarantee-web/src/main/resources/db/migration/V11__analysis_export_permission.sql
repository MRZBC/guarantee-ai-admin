-- 无结构变更。空库由 PermissionCatalog 初始化；存量库也由 PermissionSyncInitializer
-- 幂等补齐。本脚本用于不运行初始化器的部署，重复执行不新增重复绑定。
INSERT INTO sys_permission (perm_code, perm_name, perm_type, parent_id, sort_no)
SELECT 'analysis:export', '分析摘要导出', 'BUTTON', 0, 100
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE perm_code='analysis:export' AND is_deleted=0);

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r CROSS JOIN sys_permission p
WHERE r.role_code IN ('ADMIN','ANALYST') AND r.is_deleted=0
  AND p.perm_code='analysis:export' AND p.is_deleted=0
  AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);
