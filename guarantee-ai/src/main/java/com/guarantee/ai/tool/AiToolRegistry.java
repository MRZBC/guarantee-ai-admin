package com.guarantee.ai.tool;

import com.guarantee.common.security.Permissions;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

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
 * <p><b>装饰链顺序</b>（自内向外）：</p>
 * <pre>
 *   业务 Tool → BoundedToolCallback（16KB 上限，SYS-Q-10）
 *             → RecordingToolCallback（落库 + SSE，arguments/result 先脱敏）
 * </pre>
 */
@Component
public class AiToolRegistry {

    /** 一个工具的注册描述：实例 + 需要的权限码（空表示登录即可）。 */
    private record ToolDescriptor(Object tool, String... requiredPermissions) {
    }

    private final List<ToolDescriptor> readTools = new ArrayList<>();
    private final List<ToolDescriptor> writeTools = new ArrayList<>();

    private final AiToolCallRecorder recorder;

    public AiToolRegistry(OrderSummaryTool orderSummaryTool,
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
                          AiToolCallRecorder recorder) {
        this.recorder = recorder;

        // ---------------- READ 工具 ----------------
        // 业务域：沿用既有行为（SYS-NF-09 要求 queryOrderSummary 行为不变）
        readTools.add(new ToolDescriptor(orderSummaryTool));
        readTools.add(new ToolDescriptor(orgQueryTool, Permissions.ORG_VIEW));
        readTools.add(new ToolDescriptor(departmentQueryTool, Permissions.DEPT_VIEW));
        readTools.add(new ToolDescriptor(userQueryTool, Permissions.USER_VIEW));
        readTools.add(new ToolDescriptor(roleQueryTool, Permissions.ROLE_VIEW));
        readTools.add(new ToolDescriptor(insuranceTypeQueryTool, Permissions.INSURANCE_VIEW));
        // 全局操作审计：仅 ADMIN（D-1a）；ANALYST / VIEWER 该工具不注册
        readTools.add(new ToolDescriptor(operationAuditQueryTool, Permissions.AUDIT_VIEW));
        // 自查工具：不依赖 system:audit:view，是 D-1a 的替代能力
        readTools.add(new ToolDescriptor(myToolCallsQueryTool, Permissions.AI_SYSTEM_QUERY));
        // 自查工具：本会话的待确认提案。存在的意义是让"有没有待确认提案"从"靠猜"变成"可查"，
        // 从而消除"正文引用一个库里不存在的提案编号"这类编造（SYS-Q-06b）。
        // 与 queryMyToolCalls 同权限码，不新增权限码、不改权限矩阵。
        readTools.add(new ToolDescriptor(myProposalsQueryTool, Permissions.AI_SYSTEM_QUERY));

        // ---------------- WRITE 工具（只产出提案，绝不落库，SYS-W-08） ----------------
        // 除各自的域权限外，统一要求 ai:system:write 能力开关（5.5.2 的"与"关系）
        // 每个域都必须把 :delete 一并列入：否则"只有删除权限、没有新增/修改/停用权限"的用户
        // 会连工具都注册不上，助手侧就发不出删除提案（设计 §10.4 明确要求确认这一点）。
        // 注意 grants() 对动作权限是"任一满足即注册"，具体动作的权限仍由工具内部二次判定。
        writeTools.add(new ToolDescriptor(orgProposalTool,
                Permissions.AI_SYSTEM_WRITE, Permissions.ORG_CREATE, Permissions.ORG_UPDATE,
                Permissions.ORG_DISABLE, Permissions.ORG_DELETE));
        writeTools.add(new ToolDescriptor(departmentProposalTool,
                Permissions.AI_SYSTEM_WRITE, Permissions.DEPT_CREATE, Permissions.DEPT_UPDATE,
                Permissions.DEPT_DISABLE, Permissions.DEPT_DELETE));
        writeTools.add(new ToolDescriptor(userProposalTool,
                Permissions.AI_SYSTEM_WRITE, Permissions.USER_UPDATE, Permissions.USER_DISABLE,
                Permissions.USER_ASSIGN_ROLE, Permissions.USER_DELETE));
        writeTools.add(new ToolDescriptor(roleProposalTool,
                Permissions.AI_SYSTEM_WRITE, Permissions.ROLE_CREATE, Permissions.ROLE_UPDATE,
                Permissions.ROLE_ASSIGN_PERMISSION, Permissions.ROLE_DELETE));
        writeTools.add(new ToolDescriptor(insuranceTypeProposalTool,
                Permissions.AI_SYSTEM_WRITE, Permissions.INSURANCE_CREATE, Permissions.INSURANCE_UPDATE,
                Permissions.INSURANCE_DISABLE, Permissions.INSURANCE_DELETE));
    }

    /**
     * 当前用户可用的全部工具（READ + WRITE）。
     *
     * @param permissions 当前用户权限快照（来自 {@code ToolContext}）
     */
    public ToolCallback[] callbacks(List<String> permissions) {
        List<ToolCallback> result = new ArrayList<>();
        result.addAll(select(readTools, permissions, ToolKind.READ));
        result.addAll(select(writeTools, permissions, ToolKind.WRITE));
        return result.toArray(ToolCallback[]::new);
    }

    /** 当前用户可用的工具名（供测试断言与日志排查使用，TEST-16）。 */
    public List<String> availableToolNames(List<String> permissions) {
        List<String> names = new ArrayList<>();
        for (ToolCallback callback : callbacks(permissions)) {
            names.add(callback.getToolDefinition().name());
        }
        return names;
    }

    /**
     * 兼容旧调用路径：只注册 READ 工具且不做权限裁剪。
     *
     * <p>保留它是为了让不携带权限上下文的调用方（例如单元测试直接拿 READ 工具）
     * 仍然可用；生产链路一律走 {@link #callbacks(List)}，由 {@code AiChatService}
     * 从 {@code ToolContext} 取权限。</p>
     */
    public ToolCallback[] readToolCallbacks() {
        return select(readTools, List.of(), ToolKind.READ).toArray(ToolCallback[]::new);
    }

    /** 写工具是否可用（无 {@code ai:system:write} 时恒为空）。 */
    public ToolCallback[] writeToolCallbacks(List<String> permissions) {
        return select(writeTools, permissions, ToolKind.WRITE).toArray(ToolCallback[]::new);
    }

    /**
     * 从 {@code ToolContext} 读权限并构造工具集。
     *
     * <p>这是生产链路唯一入口：权限必须来自随请求写入的快照，而不是当前线程的
     * 安全上下文（工具线程没有安全上下文）。</p>
     */
    public ToolCallback[] callbacksFor(ToolContext toolContext) {
        List<String> permissions = AiPermissionGuard.permissions(toolContext);
        ToolCallback[] all = callbacks(permissions);
        // 权限为空说明 ToolContext 未正确填充：此时只保留"无需权限"的工具，
        // 绝不放行 system:* 工具（fail-closed）。
        return all;
    }

    private List<ToolCallback> select(List<ToolDescriptor> descriptors, List<String> permissions,
                                      ToolKind kind) {
        List<ToolCallback> result = new ArrayList<>();
        for (ToolDescriptor descriptor : descriptors) {
            if (!grants(permissions, descriptor.requiredPermissions())) {
                continue;
            }
            result.addAll(wrap(descriptor.tool(), kind));
        }
        return result;
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
     */
    private List<ToolCallback> wrap(Object tool, ToolKind kind) {
        return java.util.Arrays.stream(ToolCallbacks.from(tool))
                .map(cb -> (ToolCallback) new RecordingToolCallback(
                        new BoundedToolCallback(cb), kind, recorder))
                .toList();
    }

    /** 工具定义（调试用）。 */
    static ToolDefinition definitionOf(ToolCallback callback) {
        return callback.getToolDefinition();
    }
}
