package com.guarantee.ai.tool;

import com.guarantee.system.scope.DataScope;
import com.guarantee.system.scope.DataScopeService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

/**
 * 工具线程上的数据范围解析（SYS-P-03 / SYS-P-10）。
 *
 * <p>Tool 执行线程没有 {@code CurrentUser}，因此范围必须由
 * {@link AiPermissionGuard} 从 {@code ToolContext} 取出身份后，交给
 * {@code guarantee-system} 的 {@link DataScopeService} 判定。</p>
 *
 * <p>AI 层**不重复实现**范围判定逻辑，只负责把身份与角色传下去——
 * 这样页面与助手两条渠道的范围口径天然一致（SYS-P-10）。</p>
 */
@Component
public class AiDataScopeResolver {

    private final DataScopeService dataScopeService;

    public AiDataScopeResolver(DataScopeService dataScopeService) {
        this.dataScopeService = dataScopeService;
    }

    /** 解析当前工具调用的数据范围。 */
    public DataScope resolve(ToolContext context) {
        return dataScopeService.resolve(
                AiPermissionGuard.userId(context),
                AiPermissionGuard.roles(context));
    }

    /** 范围描述，用于工具返回值的 dataSource 回显（SYS-N-08）。 */
    public String describe(ToolContext context) {
        return resolve(context).description();
    }
}
