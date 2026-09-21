package com.guarantee.auth.vo;

import java.util.List;

/**
 * 当前登录用户信息。字段与前端 `user` 对象严格对应。
 */
public record CurrentUserVO(
        Long id,
        String username,
        String realName,
        Long orgId,
        String orgName,
        Long deptId,
        String deptName,
        List<String> roles,
        List<String> permissions) {
}
