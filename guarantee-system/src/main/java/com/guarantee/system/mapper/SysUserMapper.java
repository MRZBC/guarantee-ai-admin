package com.guarantee.system.mapper;

import com.guarantee.system.dto.UserDto;
import com.guarantee.system.dto.UserRoleRef;
import com.guarantee.system.entity.SysUser;
import com.guarantee.system.vo.UserVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户配置 Mapper。SQL 统一维护在 mapper/system/SysUserMapper.xml。
 *
 * <p>除登录专用的 {@link #selectByUsername(String)} 与写操作执行链路使用的实体查询外，
 * 任何查询都不读取 password 列。</p>
 */
@Mapper
public interface SysUserMapper {

    // ---------------- 读 ----------------

    List<UserVO> selectPage(@Param("q") UserDto.Query query,
                            @Param("offset") int offset,
                            @Param("limit") int limit);

    long countByQuery(@Param("q") UserDto.Query query);

    /** 明细（带数据范围，SYS-P-14）。 */
    UserVO selectVoScoped(@Param("q") UserDto.Query query);

    /** 按 id 取（执行结果回读），不做范围过滤：调用方须已完成范围校验。 */
    List<UserVO> selectVoByIds(@Param("ids") List<Long> ids);

    /** 仅登录链路使用：包含 password 散列。 */
    SysUser selectByUsername(@Param("username") String username);

    /** 写操作/危险保护使用：按主键读取实体（含 status）。 */
    SysUser selectEntityById(@Param("id") Long id);

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
    SysUser selectEntityByIdIncludingDeleted(@Param("id") Long id);

    List<SysUser> selectEntitiesByIds(@Param("ids") List<Long> ids);

    /** 批量回填用户角色，避免 N+1。 */
    List<UserRoleRef> selectRoleRefsByUserIds(@Param("userIds") List<Long> userIds);

    /** 当前用户的启用角色编码。 */
    List<String> listRoleCodesByUserId(@Param("userId") Long userId);

    /** 当前用户的启用权限编码（经角色去重）。 */
    List<String> listPermissionCodesByUserId(@Param("userId") Long userId);

    List<Long> listRoleIdsByUserId(@Param("userId") Long userId);

    // ---------------- 写 ----------------

    /** 登录成功后记录最近登录时间。 */
    int updateLastLoginAt(@Param("id") Long id);

    int updateProfile(SysUser entity);

    /** 条件更新状态（T-08）。 */
    int updateStatus(@Param("id") Long id,
                     @Param("status") Integer status,
                     @Param("expectedStatus") Integer expectedStatus);

    /** 把不在目标集合中的用户-角色绑定置为已删除（设计 §4.2 的 UPSERT 前半段）。 */
    int softDeleteUserRolesNotIn(@Param("userId") Long userId,
                                 @Param("roleIds") List<Long> roleIds,
                                 @Param("operatorId") String operatorId);

    /** 命中唯一键则复活为有效绑定，否则新建（设计 §4.2 的 UPSERT 后半段）。 */
    int upsertUserRoles(@Param("userId") Long userId, @Param("roleIds") List<Long> roleIds);

    /** 按角色编码解绑（软删除）。 */
    int softDeleteUserRoleByCode(@Param("userId") Long userId,
                                 @Param("roleCode") String roleCode,
                                 @Param("operatorId") String operatorId);

    // ---------------- 危险动作保护用统计 ----------------

    /** 持有 ADMIN 角色且启用状态的用户数，排除指定用户（禁止停用/降级最后一个 ADMIN）。 */
    long countOtherEnabledAdmins(@Param("excludeUserId") Long excludeUserId);

    long countEnabledUsersByRoleCode(@Param("roleCode") String roleCode);
}
