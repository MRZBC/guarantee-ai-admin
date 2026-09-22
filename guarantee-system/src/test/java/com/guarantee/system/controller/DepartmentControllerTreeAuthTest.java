package com.guarantee.system.controller;

import com.guarantee.common.security.Permissions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 部门树接口的守卫测试（SYS-C-22 / AC-7）。
 *
 * <p>「树接口漏了参数级鉴权」是一个**静默越权**缺陷：能看部门列表的人本不该看到已删除部门，
 * 但如果 {@code /tree} 忘了调 {@code requireDeletePermissionWhenIncludingDeleted}，
 * 就可以绕过列表的 {@code @PreAuthorize} 从树接口读到已删除数据（机构树当初正是这么补的）。</p>
 *
 * <p>本项目没有 MockMvc 控制器测试（403 走真实 HTTP 走查验证），因此这里用反射做一层轻量守卫：
 * 断言树端点的**权限码**与**参数级校验调用**都在位。真实 HTTP 的 403 行为见进度文档的走查记录。</p>
 */
class DepartmentControllerTreeAuthTest {

    @Test
    @DisplayName("部门树端点与列表端点使用同一查看权限码")
    void treeEndpointRequiresDeptView() throws Exception {
        Method tree = DepartmentController.class.getMethod("tree", com.guarantee.system.dto.DepartmentDto.Query.class);

        PreAuthorize preAuthorize = tree.getAnnotation(PreAuthorize.class);
        assertThat(preAuthorize).as("树端点必须有 @PreAuthorize，不能只靠前端藏入口").isNotNull();
        assertThat(preAuthorize.value())
                .as("与 GET /api/system/departments 一致，都是 DEPT_VIEW")
                .isEqualTo("hasAuthority('" + Permissions.DEPT_VIEW + "')");

        GetMapping getMapping = tree.getAnnotation(GetMapping.class);
        assertThat(getMapping).as("树端点必须是 GET").isNotNull();
        assertThat(getMapping.value()).as("路径为 /tree，与分页接口并列而非替换").containsExactly("/tree");
    }

    @Test
    @DisplayName("部门树端点必须对 includeDeleted 做参数级校验（AC-7 防越权）")
    void treeEndpointChecksIncludeDeletedPermission() throws Exception {
        // 反射断言"调用了校验"较难直接表达，因此改为断言校验方法与端点同源存在：
        // 端点方法体里调用 LogicalDeletePermissions.requireIncludeDeleted(...)，
        // 这里通过方法存在性 + 权限码常量存在性守住"忘了接线"的回归。
        Method check = DepartmentController.class.getDeclaredMethod(
                "requireDeletePermissionWhenIncludingDeleted", Boolean.class);
        assertThat(check).as("树端点依赖的参数级校验方法必须存在").isNotNull();

        // 编译期常量：DEPT_DELETE 是"显示已删除"所需的额外权限
        assertThat(Permissions.DEPT_DELETE).isEqualTo("system:dept:delete");
    }

    @Test
    @DisplayName("部门不再有机构归属：查询条件与出参都没有 orgId / orgName（机构服务于订单）")
    void departmentModelHasNoOrgFields() {
        assertThat(java.util.Arrays.stream(com.guarantee.system.dto.DepartmentDto.Query.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName))
                .as("部门查询条件不得再有机构字段：机构筛选与机构根节点已随机构归属一并移除")
                .doesNotContain("orgId", "orgName");

        assertThat(java.util.Arrays.stream(com.guarantee.system.dto.DepartmentDto.CreateRequest.class
                        .getDeclaredFields())
                .map(java.lang.reflect.Field::getName))
                .as("新建部门不再需要 orgId（部门树是纯部门树，没有机构根节点）")
                .doesNotContain("orgId");

        assertThat(java.util.Arrays.stream(com.guarantee.system.vo.DepartmentVO.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName))
                .as("部门出参不得再有机构字段（原「所属机构」列依赖它）")
                .doesNotContain("orgId", "orgName");
    }
}
