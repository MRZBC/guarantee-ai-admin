package com.guarantee.ai.tool;

import com.guarantee.ai.config.AiConfigCatalog;
import com.guarantee.ai.config.AiConfigService;
import com.guarantee.ai.config.AiConfigSnapshot;
import com.guarantee.ai.knowledge.KnowledgeProperties;
import com.guarantee.common.security.Permissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 工具注册表 + **按权限裁剪注册集**（SYS-P-12a）。
 *
 * <p><b>为什么注册裁剪是第一道安全边界</b>：模型只能调用出现在工具列表里的工具。
 * 如果无权限的工具有权限校验但依然注册，模型会尝试调用、拿到拒绝文案，
 * 结果是"用户以为系统不支持"或"模型反复重试"。更糟的情况是权限校验有 bug 时直接越权。
 * 因此：</p>
 * <ul>
 *   <li>无 {@code system:audit:view}（含 ANALYST）→ 不注册 {@code queryOperationAudit}；</li>
 *   <li>无 {@code ai:system:write} → 不注册任何 {@code propose*} 工具；</li>
 *   <li>无对应域 {@code :view} → 不注册该域查询工具。</li>
 * </ul>
 *
 * <p>第二道是工具内部与 Service 层的 {@code @PreAuthorize} / 数据范围校验（T-04）。
 * 两道都要有，缺一不可。</p>
 *
 * <p><b>知识检索工具的例外</b>（REQ-RAG-03/07）：{@code queryBusinessKnowledge} 登录即可用，
 * **不做权限裁剪**——它的可见性由 {@code KnowledgeService} 按每条的 {@code permission_code}
 * 在服务端过滤。它单独成组是为了支持降级开关：
 * {@code guarantee.ai.knowledge.enabled=false} 时**不入注册集**（而不是注册后执行报错），
 * 模型看不到工具，数字类回答完全不受影响。</p>
 *
 * <p><b>装饰链顺序</b>（自内向外）：</p>
 * <pre>
 *   业务 Tool → SanitizingToolCallback（自由文本压成单行，防间接提示注入）
 *             → BoundedToolCallback（结果字节上限，默认 16KB、可配 budget.tool-result-bytes；
 *                                  单次执行超时，默认 10s、可配 budget.tool-timeout-ms）
 *             → RecordingToolCallback（落库 + SSE，arguments/result 先脱敏）
 * </pre>
 */
@Component
public class AiToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(AiToolRegistry.class);

    /**
     * 工具组：对应 AI 配置里的能力开关（T4-01 接线，REQ-CFG-04）。
     *
     * <p>分组的意义是"关一个开关只关一组工具"，而不是让每个工具各自读配置——
     * 后者的必然结果是"新加一个工具忘了读开关"。</p>
     */
    private enum ToolGroup {
        /** {@code tools.order.enabled}。 */
        ORDER,
        /** {@code tools.analysis.enabled}。 */
        ANALYSIS,
        /** {@code tools.system.enabled}。 */
        SYSTEM,
        /** {@code tools.audit.enabled}。 */
        AUDIT,
        /** {@code tools.proposal.enabled}（与 {@code ai:system:write} 取「与」）。 */
        PROPOSAL,
        /** {@code knowledge.enabled}（与 {@code guarantee.ai.knowledge.enabled} 取「与」）。 */
        KNOWLEDGE
    }

    /** 一个工具的注册描述：所属组 + 实例 + 需要的权限码（空表示登录即可）。 */
    private record ToolDescriptor(ToolGroup group, Object tool, String... requiredPermissions) {
    }

    private final List<ToolDescriptor> readTools = new ArrayList<>();
    private final List<ToolDescriptor> writeTools = new ArrayList<>();

    /**
     * 知识检索工具（独立成组）。
     *
     * <p>它不参与权限裁剪（登录即可用），但要参与**降级开关**：关闭知识层时
     * 整个组不注册。放在 readTools 里就无法单独摘除，因此单列。</p>
     */
    private final List<ToolDescriptor> knowledgeTools = new ArrayList<>();

    /** 信息源：预算与开关的运行期唯一来源（请求内用同一份快照，见 callbacks(perms, snapshot)）。 */
    private final AiConfigService configService;

    /**
     * 知识层的**运维级**开关（{@code guarantee.ai.knowledge.enabled}）。
     *
     * <p>与配置项 {@code knowledge.enabled} 的分工：yml 是"故障应急/演练"用的进程级开关
     * （改配置需重启，但 DB 挂了也有效），DB 是配置级开关（页面可改、立即生效）。
     * 两者取 **与**：任一为 false 即不注册——应急开关不应被配置页面"改回来"。</p>
     */
    private final boolean knowledgeEmergencySwitch;

    private final AiToolCallRecorder recorder;
    private final ObjectMapper objectMapper;

    public AiToolRegistry(OrderSummaryTool orderSummaryTool,
                          OrderDistributionTool orderDistributionTool,
                          OrderTrendTool orderTrendTool,
                          EnterpriseAnalysisTool enterpriseAnalysisTool,
                          ProjectAnalysisTool projectAnalysisTool,
                          OrgQueryTool orgQueryTool,
                          DepartmentQueryTool departmentQueryTool,
                          UserQueryTool userQueryTool,
                          RoleQueryTool roleQueryTool,
                          InsuranceTypeQueryTool insuranceTypeQueryTool,
                          OperationAuditQueryTool operationAuditQueryTool,
                          MyToolCallsQueryTool myToolCallsQueryTool,
                          MyProposalsQueryTool myProposalsQueryTool,
                          com.guarantee.ai.tool.write.OrgProposalTool orgProposalTool,
                          com.guarantee.ai.tool.write.DepartmentProposalTool departmentProposalTool,
                          com.guarantee.ai.tool.write.UserProposalTool userProposalTool,
                          com.guarantee.ai.tool.write.RoleProposalTool roleProposalTool,
                          com.guarantee.ai.tool.write.InsuranceTypeProposalTool insuranceTypeProposalTool,
                          QueryBusinessKnowledgeTool queryBusinessKnowledgeTool,
                          AiToolCallRecorder recorder,
                          ObjectMapper objectMapper,
                          KnowledgeProperties knowledgeProperties,
                          AiConfigService configService) {
        this.recorder = recorder;
        this.objectMapper = objectMapper;
        this.configService = configService;
        this.knowledgeEmergencySwitch = knowledgeProperties.isEnabled();
        if (!knowledgeEmergencySwitch) {
            log.warn("guarantee.ai.knowledge.enabled=false（运维级应急开关）：知识检索工具不注册"
                    + "（数字类问答不受影响）");
        }

        // ---------------- READ 工具 ----------------
        // 业务域：沿用既有行为（SYS-NF-09 要求 queryOrderSummary 行为不变）
        readTools.add(new ToolDescriptor(ToolGroup.ORDER, orderSummaryTool));
        // 维度分布（区域/机构/险种）。与 queryOrderSummary **同一域、同一权限口径**：
        // 后者本来就对所有 ai:chat 用户开放订单汇总值，这里只是把同一批数据按维度切开。
        // 页面侧对应的数据概览接口同样只要求登录（无 @PreAuthorize），因此不新增权限码、
        // 不改权限矩阵——"要不要在系统域加权限"是既有未决项，不在这里单方面收紧。
        readTools.add(new ToolDescriptor(ToolGroup.ANALYSIS, orderDistributionTool));
        // 时间趋势（逐日/逐月/逐年序列）。与 queryOrderSummary / queryOrderDistribution
        // 同属订单只读域：页面侧的数据概览趋势接口同样只要求登录，因此同权限口径、
        // 不新增权限码（§5.1.6）。
        readTools.add(new ToolDescriptor(ToolGroup.ANALYSIS, orderTrendTool));
        // 主体维度：企业（REQ-BA-03）与项目（REQ-BA-04）。
        // 与订单分布/趋势**同域、同权限口径**：它们都是从同一批订单数据（orderSource 片段）
        // 聚合出来的只读视角，页面侧对应的企业/项目接口只要求登录；
        // 因此不新增权限码（REQ §5.1.6 的 Q-BA-03 仍待定，不在这里单方面收紧）。
        readTools.add(new ToolDescriptor(ToolGroup.ANALYSIS, enterpriseAnalysisTool));
        readTools.add(new ToolDescriptor(ToolGroup.ANALYSIS, projectAnalysisTool));
        readTools.add(new ToolDescriptor(ToolGroup.SYSTEM, orgQueryTool, Permissions.ORG_VIEW));
        readTools.add(new ToolDescriptor(ToolGroup.SYSTEM, departmentQueryTool, Permissions.DEPT_VIEW));
        readTools.add(new ToolDescriptor(ToolGroup.SYSTEM, userQueryTool, Permissions.USER_VIEW));
        readTools.add(new ToolDescriptor(ToolGroup.SYSTEM, roleQueryTool, Permissions.ROLE_VIEW));
        readTools.add(new ToolDescriptor(ToolGroup.SYSTEM, insuranceTypeQueryTool, Permissions.INSURANCE_VIEW));
        // 全局操作审计：仅 ADMIN（D-1a）；ANALYST / VIEWER 该工具不注册
        readTools.add(new ToolDescriptor(ToolGroup.AUDIT, operationAuditQueryTool, Permissions.AUDIT_VIEW));
        // 自查工具：不依赖 system:audit:view，是 D-1a 的替代能力
        readTools.add(new ToolDescriptor(ToolGroup.SYSTEM, myToolCallsQueryTool, Permissions.AI_SYSTEM_QUERY));
        // 自查工具：本会话的待确认提案。存在的意义是让"有没有待确认提案"从"靠猜"变成"可查"，
        // 从而消除"正文引用一个库里不存在的提案编号"这类编造（SYS-Q-06b）。
        // 与 queryMyToolCalls 同权限码，不新增权限码、不改权限矩阵。
        readTools.add(new ToolDescriptor(ToolGroup.SYSTEM, myProposalsQueryTool, Permissions.AI_SYSTEM_QUERY));

        // ---------------- 知识检索工具（登录即可，服务端按 permission_code 裁剪） ----------------
        // 描述里写明"定义/口径/概念/制度类用它；数字必须用业务工具"（REQ-RAG-06 的边界规则）
        knowledgeTools.add(new ToolDescriptor(ToolGroup.KNOWLEDGE, queryBusinessKnowledgeTool));

        // ---------------- WRITE 工具（只产出提案，绝不落库，SYS-W-08） ----------------
        // 除各自的域权限外，统一要求 ai:system:write 能力开关（5.5.2 的"与"关系）
        // 每个域都必须把 :delete 一并列入：否则"只有删除权限、没有新增/修改/停用权限"的用户
        // 会连工具都注册不上，助手侧就发不出删除提案（设计 §10.4 明确要求确认这一点）。
        // 注意 grants() 对动作权限是"任一满足即注册"，具体动作的权限仍由工具内部二次判定。
        writeTools.add(new ToolDescriptor(ToolGroup.PROPOSAL, orgProposalTool,
                Permissions.AI_SYSTEM_WRITE, Permissions.ORG_CREATE, Permissions.ORG_UPDATE,
                Permissions.ORG_DISABLE, Permissions.ORG_DELETE));
        writeTools.add(new ToolDescriptor(ToolGroup.PROPOSAL, departmentProposalTool,
                Permissions.AI_SYSTEM_WRITE, Permissions.DEPT_CREATE, Permissions.DEPT_UPDATE,
                Permissions.DEPT_DISABLE, Permissions.DEPT_DELETE));
        writeTools.add(new ToolDescriptor(ToolGroup.PROPOSAL, userProposalTool,
                Permissions.AI_SYSTEM_WRITE, Permissions.USER_UPDATE, Permissions.USER_DISABLE,
                Permissions.USER_ASSIGN_ROLE, Permissions.USER_DELETE));
        writeTools.add(new ToolDescriptor(ToolGroup.PROPOSAL, roleProposalTool,
                Permissions.AI_SYSTEM_WRITE, Permissions.ROLE_CREATE, Permissions.ROLE_UPDATE,
                Permissions.ROLE_ASSIGN_PERMISSION, Permissions.ROLE_DELETE));
        writeTools.add(new ToolDescriptor(ToolGroup.PROPOSAL, insuranceTypeProposalTool,
                Permissions.AI_SYSTEM_WRITE, Permissions.INSURANCE_CREATE, Permissions.INSURANCE_UPDATE,
                Permissions.INSURANCE_DISABLE, Permissions.INSURANCE_DELETE));
    }

    /**
     * 当前用户可用的全部工具（READ + WRITE），使用**当前快照**。
     *
     * <p>生产链路（{@code AiChatService}）请用
     * {@link #callbacks(List, AiConfigSnapshot)} 显式传入本轮快照，
     * 以保证"一轮对话内工具集与预算参数来自同一份配置"（REQ-CFG-06）。</p>
     */
    public ToolCallback[] callbacks(List<String> permissions) {
        return callbacks(permissions, configService.snapshot());
    }

    /**
     * 当前用户可用的全部工具（READ + WRITE），显式指定配置快照。
     *
     * <p>开关裁剪在**注册期**完成：关掉</p>
     * <ul>
     *   <li>{@code tools.order.enabled} → 不注册 {@code queryOrderSummary}；</li>
     *   <li>{@code tools.analysis.enabled} → 不注册分布/趋势；</li>
     *   <li>{@code tools.system.enabled} → 不注册机构/部门/用户/角色/险种与两个自查工具；</li>
     *   <li>{@code tools.audit.enabled} → 不注册全局审计；</li>
     *   <li>{@code tools.proposal.enabled} → 不注册任何 {@code propose*}（与权限码取「与」）；</li>
     *   <li>{@code knowledge.enabled}（与 yml 应急开关取「与」）→ 不注册知识检索。</li>
     * </ul>
     * <p>缺省（全 true）时工具集合与改造前**完全一致**（AC-CFG-08）。</p>
     */
    public ToolCallback[] callbacks(List<String> permissions, AiConfigSnapshot config) {
        List<ToolCallback> result = new ArrayList<>();
        result.addAll(select(readTools, permissions, ToolKind.READ, config));
        result.addAll(knowledgeCallbacks(permissions, config));
        result.addAll(select(writeTools, permissions, ToolKind.WRITE, config));
        return result.toArray(ToolCallback[]::new);
    }

    /** 当前用户可用的工具名（供测试断言与日志排查使用，TEST-16）。 */
    public List<String> availableToolNames(List<String> permissions) {
        return availableToolNames(permissions, configService.snapshot());
    }

    /** 当前用户可用的工具名（显式指定配置快照；用于验证开关效果）。 */
    public List<String> availableToolNames(List<String> permissions, AiConfigSnapshot config) {
        List<String> names = new ArrayList<>();
        for (ToolCallback callback : callbacks(permissions, config)) {
            names.add(callback.getToolDefinition().name());
        }
        return names;
    }

    /**
     * 兼容旧调用路径：只注册 READ 工具且不做权限裁剪。
     *
     * <p>保留它是为了让不携带权限上下文的调用方（例如单元测试直接拿 READ 工具）
     * 仍然可用；生产链路一律走 {@link #callbacks(List, AiConfigSnapshot)}，由
     * {@code AiChatService} 从 {@code ToolContext} 取权限并显式传入本轮快照。</p>
     */
    public ToolCallback[] readToolCallbacks() {
        return readToolCallbacks(configService.snapshot());
    }

    /** 兼容旧调用路径（显式指定配置快照）。 */
    public ToolCallback[] readToolCallbacks(AiConfigSnapshot config) {
        List<ToolCallback> result = new ArrayList<>(select(readTools, List.of(), ToolKind.READ, config));
        result.addAll(knowledgeCallbacks(List.of(), config));
        return result.toArray(ToolCallback[]::new);
    }

    /**
     * 知识检索工具是否注册。
     *
     * <p>两个开关取「与」：yml 的 {@code guarantee.ai.knowledge.enabled} 是**运维级应急开关**
     * （进程级、DB 挂了也有效），配置项 {@code knowledge.enabled} 是**配置级开关**
     * （页面可改、立即生效）。关闭时**不入注册集**：模型看不到这个工具，
     * 就不会尝试调用、不会拿到拒绝文案，数字类问答完全不受影响（REQ-RAG-07 / AC-RAG-07）。</p>
     */
    private List<ToolCallback> knowledgeCallbacks(List<String> permissions, AiConfigSnapshot config) {
        return knowledgeEmergencySwitch && config.getBoolean(AiConfigCatalog.KNOWLEDGE_ENABLED)
                ? select(knowledgeTools, permissions, ToolKind.READ, config)
                : List.of();
    }

    /** 写工具是否可用（无 {@code ai:system:write} 时恒为空）。 */
    public ToolCallback[] writeToolCallbacks(List<String> permissions) {
        return writeToolCallbacks(permissions, configService.snapshot());
    }

    /** 写工具是否可用（显式指定配置快照：{@code tools.proposal.enabled} 关闭时恒为空）。 */
    public ToolCallback[] writeToolCallbacks(List<String> permissions, AiConfigSnapshot config) {
        return select(writeTools, permissions, ToolKind.WRITE, config).toArray(ToolCallback[]::new);
    }

    /**
     * 从 {@code ToolContext} 读权限并构造工具集。
     *
     * <p>这是生产链路唯一入口：权限必须来自随请求写入的快照，而不是当前线程的
     * 安全上下文（工具线程没有安全上下文）。</p>
     */
    public ToolCallback[] callbacksFor(ToolContext toolContext) {
        List<String> permissions = AiPermissionGuard.permissions(toolContext);
        ToolCallback[] all = callbacks(permissions, configService.snapshot());
        // 权限为空说明 ToolContext 未正确填充：此时只保留"无需权限"的工具，
        // 绝不放行 system:* 工具（fail-closed）。
        return all;
    }

    private List<ToolCallback> select(List<ToolDescriptor> descriptors, List<String> permissions,
                                      ToolKind kind, AiConfigSnapshot config) {
        List<ToolCallback> result = new ArrayList<>();
        for (ToolDescriptor descriptor : descriptors) {
            if (!enabled(descriptor.group(), config)) {
                continue;
            }
            if (!grants(permissions, descriptor.requiredPermissions())) {
                continue;
            }
            result.addAll(wrap(descriptor.tool(), kind, config));
        }
        return result;
    }

    /**
     * 工具组开关判定（缺省全 true ⇒ 与改造前一致，AC-CFG-08）。
     *
     * <p>未知组一律**不放行**（fail-closed）：新增工具时忘记登记组，宁可它不注册，
     * 也不要在"关掉某组"之后它仍然可调用。</p>
     */
    private boolean enabled(ToolGroup group, AiConfigSnapshot config) {
        if (group == null) {
            return false;
        }
        return switch (group) {
            case ORDER -> config.getBoolean(AiConfigCatalog.TOOLS_ORDER_ENABLED);
            case ANALYSIS -> config.getBoolean(AiConfigCatalog.TOOLS_ANALYSIS_ENABLED);
            case SYSTEM -> config.getBoolean(AiConfigCatalog.TOOLS_SYSTEM_ENABLED);
            case AUDIT -> config.getBoolean(AiConfigCatalog.TOOLS_AUDIT_ENABLED);
            case PROPOSAL -> config.getBoolean(AiConfigCatalog.TOOLS_PROPOSAL_ENABLED);
            case KNOWLEDGE -> knowledgeEmergencySwitch
                    && config.getBoolean(AiConfigCatalog.KNOWLEDGE_ENABLED);
        };
    }

    /**
     * 权限判定。
     *
     * <p>空权限列表意味着"没有提供权限快照"而不是"不需要权限"：
     * 只要描述符声明了权限要求，就不放行（fail-closed）。
     * 未声明权限要求的工具（业务域只读工具）不在此列。</p>
     */
    private static boolean grants(List<String> permissions, String[] required) {
        if (required == null || required.length == 0) {
            return true;
        }
        if (permissions == null || permissions.isEmpty()) {
            return false;
        }
        // 声明的多个权限是"任一满足即可注册"：具体动作的权限在执行期由工具内部再判定，
        // 例如 proposeOrgChange 需要 create/update/disable 之一。但 ai:system:write
        // 这类"能力开关"必须**全部**满足，因此这里把非开关类权限按"任一"处理、
        // 开关类权限按"必须"处理。
        List<String> switches = required.length == 0 ? List.of()
                : java.util.Arrays.stream(required)
                .filter(Permissions.AI_SYSTEM_WRITE::equals)
                .toList();
        if (!permissions.containsAll(switches)) {
            return false;
        }
        List<String> actions = java.util.Arrays.stream(required)
                .filter(code -> !Permissions.AI_SYSTEM_WRITE.equals(code))
                .toList();
        return actions.isEmpty() || actions.stream().anyMatch(permissions::contains);
    }

    /**
     * 包装一个 Tool 实例的**全部** {@code @Tool} 方法。
     *
     * <p>注意必须返回全部而不是第一个：{@code OrderSummaryTool} 上同时有
     * {@code queryOrderSummary} 与 {@code getCurrentDate} 两个 {@code @Tool} 方法，
     * 只取 {@code [0]} 会把 {@code getCurrentDate} 静默丢掉——而"相对时间必须先拿到
     * 基准日期"正是提示词第 6 条依赖的能力。</p>
     *
     * <p>装饰顺序（自内向外）：{@code Sanitizing}（压掉自由文本里的换行/控制字符，
     * 间接提示注入的传输层处理）→ {@code Bounded}（结果字节上限 + 单次执行超时，
     * 两者都来自本轮配置快照）→ {@code Recording}（脱敏落库 + SSE）。
     * 规范化必须在截断之前：先保住合法 JSON，再谈字节上限。</p>
     */
    private List<ToolCallback> wrap(Object tool, ToolKind kind, AiConfigSnapshot config) {
        int maxResultBytes = config.getInt(AiConfigCatalog.BUDGET_TOOL_RESULT_BYTES);
        long timeoutMs = config.getLong(AiConfigCatalog.BUDGET_TOOL_TIMEOUT_MS);
        return java.util.Arrays.stream(ToolCallbacks.from(tool))
                .map(cb -> (ToolCallback) new RecordingToolCallback(
                        new BoundedToolCallback(new SanitizingToolCallback(cb, objectMapper),
                                maxResultBytes, timeoutMs),
                        kind, recorder))
                .toList();
    }

    /** 工具定义（调试用）。 */
    static ToolDefinition definitionOf(ToolCallback callback) {
        return callback.getToolDefinition();
    }
}
