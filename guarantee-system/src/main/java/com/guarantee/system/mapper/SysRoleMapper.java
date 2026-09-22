package com.guarantee.system.mapper;

import com.guarantee.system.dto.RoleCountRef;
import com.guarantee.system.dto.RoleDto;
import com.guarantee.system.dto.RolePermissionRef;
import com.guarantee.system.entity.SysPermission;
import com.guarantee.system.entity.SysRole;
import com.guarantee.system.vo.RoleVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 角色与权限 Mapper。SQL 统一维护在 mapper/system/SysRoleMapper.xml。
 *
 * <p>权限主数据（{@code sys_permission}）**只读**：R-04 明确禁止删除/修改权限主数据，
 * 角色授权只能在既有权限码中选择（5.2.3 角色表）。</p>
 */
@Mapper
public interface SysRoleMapper {

    // ---------------- 角色读 ----------------

    List<RoleVO> selectPage(@Param("q") RoleDto.Query query,
                            @Param("offset") int offset,
                            @Param("limit") int limit);

    long countByQuery(@Param("q") RoleDto.Query query);

    RoleVO selectVoById(@Param("id") Long id);

    SysRole selectEntityById(@Param("id") Long id);

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
    SysRole selectEntityByIdIncludingDeleted(@Param("id") Long id);

    SysRole selectEntityByCode(@Param("roleCode") String roleCode);

    List<SysRole> selectEntityByCodes(@Param("roleCodes") List<String> roleCodes);

    /** 写操作目标解析：按编码/名称模糊匹配（SYS-W-10）。 */
    List<SysRole> selectCandidates(@Param("keyword") String keyword, @Param("limit") int limit);

    /** 角色的权限数与用户数：让 queryRole 一次拿全，避免 N+1。 */
    List<RoleCountRef> countPermissionsByRoleIds(@Param("roleIds") List<Long> roleIds);

    List<RoleCountRef> countUsersByRoleIds(@Param("roleIds") List<Long> roleIds);

    List<RolePermissionRef> selectPermissionRefsByRoleIds(@Param("roleIds") List<Long> roleIds);

    // ---------------- 角色写 ----------------

    int insert(SysRole entity);

    int updateById(SysRole entity);

    /** 把不在目标集合中的角色-权限绑定置为已删除。 */
    int softDeleteRolePermissionsNotIn(@Param("roleId") Long roleId,
                                       @Param("permissionIds") List<Long> permissionIds,
                                       @Param("operatorId") String operatorId);

    /** 命中唯一键则复活为有效绑定，否则新建。 */
    int upsertRolePermissions(@Param("roleId") Long roleId,
                              @Param("permissionIds") List<Long> permissionIds);

    // ---------------- 权限读（主数据只读） ----------------

    /** 只在既有权限码中选择（不接受模型自由构造，5.2.3）。 */
    List<Long> selectPermissionIdsByCodes(@Param("permCodes") List<String> permCodes);

    List<SysPermission> selectPermissionEntitiesByCodes(@Param("permCodes") List<String> permCodes);

    List<SysPermission> selectPermissionsByIds(@Param("ids") List<Long> ids);

    /** 全部权限主数据（按 sort_no 排序），供角色授权界面与 queryRole 使用。 */
    List<SysPermission> selectAllPermissionsOrdered();

    /** 当前持有该角色的用户 id，用于权限变更后的令牌撤销（SYS-C-07）。 */
    List<Long> selectUserIdsByRoleCode(@Param("roleCode") String roleCode);

    /** 角色编码 -> 主键（写操作解析用）。 */
    List<Long> selectIdsByCodes(@Param("roleCodes") List<String> roleCodes);

    /** 过滤出其中"存在且启用"的角色编码。 */
    List<String> selectEnabledCodes(@Param("roleCodes") List<String> roleCodes);
}
