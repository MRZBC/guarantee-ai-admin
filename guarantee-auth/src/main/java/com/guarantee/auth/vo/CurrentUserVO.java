package com.guarantee.auth.vo;

import java.util.List;

/**
 * 当前登录用户信息。字段与前端 `user` 对象严格对应。
 *
 * <p>不含机构字段：机构是外部出函机构，服务于订单，不是人的归属属性。</p>
 */
public record CurrentUserVO(
        Long id,
        String username,
        String realName,
        Long deptId,
        String deptName,
        List<String> roles,
        List<String> permissions) {
}
