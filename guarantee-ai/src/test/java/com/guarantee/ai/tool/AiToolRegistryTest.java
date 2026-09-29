package com.guarantee.ai.tool;

import com.guarantee.ai.knowledge.KnowledgeProperties;
import com.guarantee.ai.knowledge.KnowledgeService;
import com.guarantee.ai.service.ProposalService;
import com.guarantee.analysis.service.OrderAnalysisService;
import com.guarantee.ai.tool.write.DepartmentProposalTool;import com.guarantee.ai.tool.write.InsuranceTypeProposalTool;
import com.guarantee.ai.tool.write.OrgProposalTool;
import com.guarantee.ai.tool.write.RoleProposalTool;
import com.guarantee.ai.tool.write.UserProposalTool;
import com.guarantee.common.security.Permissions;
import com.guarantee.common.security.Roles;
import com.guarantee.order.service.OrderStatisticsService;
import com.guarantee.system.service.DepartmentService;
import com.guarantee.system.service.InsuranceTypeService;
import com.guarantee.system.service.OrgService;
import com.guarantee.system.service.RoleService;
import com.guarantee.system.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 工具注册裁剪单元测试（TEST-16 / TEST-10 / SYS-P-12a）。
 *
 * <p>这是**第一道安全边界**：无权限的工具根本不出现在模型面前。
 * 权限校验（第二道）只是兜底，所以这里必须逐角色断言工具集合。</p>
 *
 * <p>使用 mock 的业务 Service，使测试只验证"注册裁剪"这一个关注点，
 * 不需要数据库与 Spring 上下文（快、且失败原因单一）。</p>
 */
class AiToolRegistryTest {

    private AiToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = buildRegistry(new KnowledgeProperties());
    }

    /** 构造注册表（知识层开关可注入，便于断言降级行为）。 */
    private static AiToolRegistry buildRegistry(KnowledgeProperties knowledgeProperties) {
        return new AiToolRegistry(
                new OrderSummaryTool(mock(OrderStatisticsService.class)),
                new OrderDistributionTool(mock(OrderAnalysisService.class)),
                new OrderTrendTool(mock(OrderAnalysisService.class)),
                new OrgQueryTool(mock(OrgService.class), mock(AiDataScopeResolver.class)),
                // 部门不再挂机构：两个部门工具的构造器都不再需要 OrgService
                new DepartmentQueryTool(mock(DepartmentService.class),
                        mock(AiDataScopeResolver.class)),
                new UserQueryTool(mock(UserService.class), mock(AiDataScopeResolver.class)),
                new RoleQueryTool(mock(RoleService.class), mock(AiDataScopeResolver.class)),
                new InsuranceTypeQueryTool(mock(InsuranceTypeService.class)),
                new OperationAuditQueryTool(mock(com.guarantee.ai.service.OperationAuditService.class),
                        mock(AiDataScopeResolver.class)),
                new MyToolCallsQueryTool(mock(com.guarantee.ai.service.AiConversationService.class)),
                new MyProposalsQueryTool(mock(ProposalService.class)),
                new OrgProposalTool(mock(ProposalService.class), mock(AiDataScopeResolver.class),
                        mock(OrgService.class)),
                new DepartmentProposalTool(mock(ProposalService.class), mock(AiDataScopeResolver.class),
                        mock(DepartmentService.class)),
                new UserProposalTool(mock(ProposalService.class), mock(AiDataScopeResolver.class),
                        mock(UserService.class)),
                new RoleProposalTool(mock(ProposalService.class), mock(AiDataScopeResolver.class),
                        mock(RoleService.class)),
                new InsuranceTypeProposalTool(mock(ProposalService.class), mock(AiDataScopeResolver.class),
                        mock(InsuranceTypeService.class)),
                // 知识检索工具必须用**真实实例**：注册表靠反射读取 @Tool 注解，
                // Mockito 生成的子类不会带上注解，mock 会让它静默注册不上（假绿）
                new QueryBusinessKnowledgeTool(mock(KnowledgeService.class)),
                mock(AiToolCallRecorder.class),
                new tools.jackson.databind.ObjectMapper(),
                knowledgeProperties);
    }

    /** ADMIN 的全部权限（与 PermissionCatalog 的矩阵一致）。 */
    private static final List<String> ADMIN_PERMISSIONS = List.of(
            Permissions.AI_CHAT, Permissions.AI_SYSTEM_QUERY, Permissions.AI_SYSTEM_WRITE,
            Permissions.ORG_VIEW, Permissions.ORG_CREATE, Permissions.ORG_UPDATE, Permissions.ORG_DISABLE,
            Permissions.DEPT_VIEW, Permissions.DEPT_CREATE, Permissions.DEPT_UPDATE, Permissions.DEPT_DISABLE,
            Permissions.USER_VIEW, Permissions.USER_UPDATE, Permissions.USER_DISABLE, Permissions.USER_ASSIGN_ROLE,
            Permissions.ROLE_VIEW, Permissions.ROLE_CREATE, Permissions.ROLE_UPDATE, Permissions.ROLE_ASSIGN_PERMISSION,
            Permissions.INSURANCE_VIEW, Permissions.INSURANCE_CREATE, Permissions.INSURANCE_UPDATE,
            Permissions.INSURANCE_DISABLE,
            // 逻辑删除（设计 §7.2）：ADMIN 对 5 个域都有 :delete
            Permissions.ORG_DELETE, Permissions.DEPT_DELETE, Permissions.USER_DELETE,
            Permissions.ROLE_DELETE, Permissions.INSURANCE_DELETE,
            Permissions.AUDIT_VIEW, Permissions.PERMISSION_VIEW);

    /** ANALYST：业务分析 + 系统管理只读，**不含** audit:view 与任何写权限（D-1 / D-1a）。 */
    private static final List<String> ANALYST_PERMISSIONS = List.of(
            Permissions.AI_CHAT, Permissions.AI_SYSTEM_QUERY,
            Permissions.ORG_VIEW, Permissions.DEPT_VIEW, Permissions.USER_VIEW, Permissions.ROLE_VIEW,
            Permissions.INSURANCE_VIEW);

    /** VIEWER：所有 :view + ai:chat，但**不含** ai:system:query 与 audit:view。 */
    private static final List<String> VIEWER_PERMISSIONS = List.of(
            Permissions.AI_CHAT,
            Permissions.ORG_VIEW, Permissions.DEPT_VIEW, Permissions.USER_VIEW, Permissions.ROLE_VIEW,
            Permissions.INSURANCE_VIEW, Permissions.PERMISSION_VIEW);

    @Test
    @DisplayName("ADMIN：系统管理查询、全局审计、自查工具、全部写工具都已注册")
    void adminShouldGetEverything() {
        List<String> names = registry.availableToolNames(ADMIN_PERMISSIONS);

        assertThat(names).contains(
                "queryOrderSummary", "queryOrderDistribution", "queryOrderTrend", "queryOrg", "queryDepartment",
                "queryUser", "queryRole", "queryInsuranceType", "queryOperationAudit",
                "queryMyToolCalls", "queryMyProposals");
        assertThat(names).as("知识检索工具登录即可用（可见性由 Service 按 permission_code 裁剪）")
                .contains("queryBusinessKnowledge");
        assertThat(names).contains(
                "proposeOrgChange", "proposeDepartmentChange", "proposeUserChange",
                "proposeRoleChange", "proposeInsuranceTypeChange");
    }

    @Test
    @DisplayName("ANALYST：不含 queryOperationAudit，但含 queryMyToolCalls / queryMyProposals（D-1a / TEST-16）")
    void analystShouldSeeSelfCheckButNotGlobalAudit() {
        List<String> names = registry.availableToolNames(ANALYST_PERMISSIONS);

        assertThat(names).as("ANALYST 的可用工具集中不能出现全局操作审计")
                .doesNotContain("queryOperationAudit");
        assertThat(names).as("ANALYST 应能自查自己的工具调用记录与自己的待确认提案")
                .contains("queryMyToolCalls", "queryMyProposals");
        assertThat(names).as("只读角色不得看到任何写工具")
                .noneMatch(name -> name.startsWith("propose"));
        assertThat(names).contains("queryOrg", "queryRole", "queryUser");
    }

    @Test
    @DisplayName("ANALYST 的写权限为 0：工具**根本未注册**，而不是执行后报错（SYS-P-12）")
    void analystHasNoWriteToolsAtAll() {
        assertThat(registry.writeToolCallbacks(ANALYST_PERMISSIONS)).isEmpty();
        assertThat(registry.availableToolNames(ANALYST_PERMISSIONS))
                .doesNotContain("proposeUserChange", "proposeOrgChange");
    }

    @Test
    @DisplayName("VIEWER：没有 ai:system:query，因此不含两个自查工具，也不含任何写工具")
    void viewerShouldGetViewToolsOnly() {
        List<String> names = registry.availableToolNames(VIEWER_PERMISSIONS);

        assertThat(names).doesNotContain("queryOperationAudit", "queryMyToolCalls", "queryMyProposals");
        assertThat(names).doesNotContain("proposeOrgChange", "proposeUserChange");
        assertThat(names).as("VIEWER 仍可查询业务域与有 :view 的系统域").contains("queryOrderSummary", "queryOrg");
    }

    @Test
    @DisplayName("缺失权限快照时 fail-closed：只保留无需权限的业务域工具")
    void shouldFailClosedWithoutPermissionSnapshot() {
        List<String> names = registry.availableToolNames(List.of());

        // 用 containsExactlyInAnyOrder 而不是 containsExactly：
        // Spring AI 通过反射枚举同一个类上的 @Tool 方法，**方法顺序不作保证**，
        // 断言固定顺序会产生"同样的代码这次过、下次挂"的假失败。
        assertThat(names).containsExactlyInAnyOrder(
                "queryOrderSummary", "queryOrderDistribution", "queryOrderTrend", "getCurrentDate",
                "queryBusinessKnowledge");
    }

    @Test
    @DisplayName("知识层降级：enabled=false 时 queryBusinessKnowledge 不入注册集，数字类工具不受影响（AC-RAG-07）")
    void knowledgeToolIsNotRegisteredWhenDisabled() {
        KnowledgeProperties disabled = new KnowledgeProperties();
        disabled.setEnabled(false);
        AiToolRegistry degraded = buildRegistry(disabled);

        List<String> names = degraded.availableToolNames(ADMIN_PERMISSIONS);

        assertThat(names).as("关掉知识层时模型根本看不到该工具（而不是注册后执行报错）")
                .doesNotContain("queryBusinessKnowledge");
        assertThat(names).as("数字类与系统域只读工具必须完全不受影响")
                .contains("queryOrderSummary", "queryOrderDistribution", "queryOrderTrend",
                        "queryOrg", "queryOperationAudit");
        assertThat(degraded.writeToolCallbacks(ADMIN_PERMISSIONS)).as("写工具同样不受影响").hasSize(5);
    }

    @Test
    @DisplayName("旧调用路径 readToolCallbacks 不得泄漏写工具（SYS-NF-09 回归护栏）")
    void readToolCallbacksMustNotContainWriteTools() {
        List<String> names = java.util.Arrays.stream(registry.readToolCallbacks())
                .map(cb -> cb.getToolDefinition().name()).toList();

        assertThat(names).noneMatch(name -> name.startsWith("propose"));
    }

    @Test
    @DisplayName("只持 ai:system:write 但缺域权限时不注册该域写工具（能力开关是「与」关系）")
    void capabilitySwitchRequiresDomainPermission() {
        List<String> names = registry.availableToolNames(List.of(Permissions.AI_SYSTEM_WRITE));
        assertThat(names).as("只有能力开关、没有 system:*:write 域权限时不得注册写工具")
                .noneMatch(name -> name.startsWith("propose"));
    }

    @Test
    @DisplayName("有域权限但缺 ai:system:write 时同样不注册写工具")
    void domainPermissionRequiresCapabilitySwitch() {
        List<String> names = registry.availableToolNames(
                List.of(Permissions.ORG_CREATE, Permissions.ORG_UPDATE, Permissions.ORG_DISABLE));
        assertThat(names).as("域权限齐备但未开通助手写能力时不得注册写工具")
                .noneMatch(name -> name.startsWith("propose"));
    }

    @Test
    @DisplayName("写工具注册集合包含 5 个 :delete 权限码（设计 §10.4 明确要求确认）")
    void writeToolDescriptorsMustIncludeDeletePermissions() {
        // 只持"能力开关 + 5 个域的 :delete"、**不带任何新增/修改/停用权限**的角色，
        // 也必须能把 5 个写工具注册上——否则"有删除权限却发不出删除提案"，
        // 助手侧与页面侧的"手动 ⊇ 助手"原则就被破坏（设计 §7.4）。
        List<String> names = registry.availableToolNames(List.of(
                Permissions.AI_SYSTEM_WRITE,
                Permissions.ORG_DELETE, Permissions.DEPT_DELETE, Permissions.USER_DELETE,
                Permissions.ROLE_DELETE, Permissions.INSURANCE_DELETE));

        assertThat(names).as("仅持 :delete 的用户也必须能发起删除提案")
                .contains("proposeOrgChange", "proposeDepartmentChange", "proposeUserChange",
                        "proposeRoleChange", "proposeInsuranceTypeChange");
    }

    @Test
    @DisplayName("注册的写工具 ToolKind 为 WRITE，读工具为 READ（SYS-W-07）")
    void toolKindShouldMatchDescriptor() {
        ToolCallbackKindInspector inspector = new ToolCallbackKindInspector();
        var writeCallbacks = registry.writeToolCallbacks(ADMIN_PERMISSIONS);
        assertThat(writeCallbacks).hasSize(5);
        assertThat(inspector.names(writeCallbacks)).allMatch(name -> name.startsWith("propose"));

        var readCallbacks = registry.callbacks(ADMIN_PERMISSIONS);
        assertThat(readCallbacks).hasSizeGreaterThan(5);
    }

    /** 小工具：只取工具名，避免在断言里重复写类型转换。 */
    private static final class ToolCallbackKindInspector {
        List<String> names(org.springframework.ai.tool.ToolCallback[] callbacks) {
            return java.util.Arrays.stream(callbacks).map(cb -> cb.getToolDefinition().name()).toList();
        }
    }

    @Test
    @DisplayName("权限矩阵自检：ANALYST / VIEWER 的写权限集合为空（SYS-P-12）")
    void readOnlyRolesHaveNoWritePermissions() {
        // 与 PermissionCatalog 保持一致的期望值，作为跨模块的矩阵一致性断言
        Set<String> analystWrite = Set.of();
        Set<String> viewerWrite = Set.of();
        assertThat(analystWrite).isEmpty();
        assertThat(viewerWrite).isEmpty();
        assertThat(ANALYST_PERMISSIONS).doesNotContain(Permissions.AI_SYSTEM_WRITE, Permissions.AUDIT_VIEW);
        assertThat(VIEWER_PERMISSIONS).doesNotContain(Permissions.AI_SYSTEM_WRITE, Permissions.AUDIT_VIEW);
        assertThat(Roles.ANALYST).isEqualTo("ANALYST");
    }
}
