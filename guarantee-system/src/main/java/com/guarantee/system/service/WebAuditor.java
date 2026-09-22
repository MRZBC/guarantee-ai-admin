package com.guarantee.system.service;

import com.guarantee.common.security.AuditSourceContext;
import com.guarantee.common.security.OperationAuditPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 页面写操作的审计助手（SYS-A-07 / AC-22）。
 *
 * <p>把 {@link OperationAuditPort} 的调用收口到一处，让各 Service 只写一行：</p>
 * <pre>
 *   webAuditor.success("UPDATE", "ORG", id, name, before, after);
 * </pre>
 *
 * <p><b>为什么用 {@link ObjectProvider} 而不是直接注入</b>：端口实现位于
 * {@code guarantee-ai}，只有完整应用（guarantee-web）才装配。单元测试或只加载
 * guarantee-system 的场景下该 Bean 不存在——用 {@code getIfAvailable()} 让这些场景
 * 正常工作，同时在生产链路中**必走审计**。</p>
 *
 * <p><b>审计失败必须让业务回滚</b>（SYS-A-03）：本类不捕获实现抛出的异常。
 * 绝不在这里写 try-catch 把异常吞掉——那会造成"数据改了但没痕迹"，
 * 而这是无法事后补救的。</p>
 */
@Component
public class WebAuditor {

    private static final Logger log = LoggerFactory.getLogger(WebAuditor.class);

    private final ObjectProvider<OperationAuditPort> portProvider;

    public WebAuditor(ObjectProvider<OperationAuditPort> portProvider) {
        this.portProvider = portProvider;
    }

    /**
     * 记录一次成功的页面写操作。
     *
     * @param action     CREATE / UPDATE / ENABLE / DISABLE / ASSIGN_ROLES / ASSIGN_PERMISSIONS
     * @param targetType USER / ORG / DEPT / ROLE / INSURANCE_TYPE
     * @param targetId   目标主键（新增时传落库后的 id）
     * @param targetName 目标展示名
     * @param before     变更前快照（新增传 null）；敏感字段传**原值**，由审计层统一脱敏
     * @param after      变更后快照（停用/删除传 null）
     */
    public void success(String action, String targetType, Long targetId, String targetName,
                        Map<String, Object> before, Map<String, Object> after) {
        record(OperationAuditPort.WebAuditEntry.success(
                action, targetType, targetId, targetName, before, after));
    }

    /** 带显式变更字段声明的成功记录（用于"值相同但语义上仍需标明"的场景）。 */
    public void success(String action, String targetType, Long targetId, String targetName,
                        Map<String, Object> before, Map<String, Object> after,
                        Set<String> changedFields) {
        record(new OperationAuditPort.WebAuditEntry(action, targetType, targetId, targetName,
                before, after, "SUCCESS", null, changedFields));
    }

    /**
     * 记录一次失败的页面写操作。
     *
     * <p>注意：本方法用于"业务主动拒绝但希望留痕"的场景（例如停用被前置检查拦下）。
     * 事务内的异常回滚路径不需要它——异常回滚时业务与审计一起消失，属于预期行为。</p>
     */
    public void failed(String action, String targetType, Long targetId, String targetName,
                       String errorMessage) {
        record(new OperationAuditPort.WebAuditEntry(action, targetType, targetId, targetName,
                null, null, "FAILED", errorMessage, Set.of()));
    }

    private void record(OperationAuditPort.WebAuditEntry entry) {
        // 助手确认路径已经由 ProposalService 写了一条 source=AI 的审计（且携带
        // 提案号、会话号、traceId）。这里必须跳过，否则同一次变更会产生两条记录，
        // 并且会把助手渠道的变错误地标成 WEB（AC-22 的来源区分就失效了）。
        if (AuditSourceContext.isAiDriven()) {
            log.debug("当前为助手确认路径，跳过页面审计（由 ProposalService 统一记录）：action={} target={}:{}",
                    entry.action(), entry.targetType(), entry.targetId());
            return;
        }

        OperationAuditPort port = portProvider.getIfAvailable();
        if (port == null) {
            // 只可能出现在"未装配 guarantee-ai"的场景（单元测试、模块级集成测试）。
            // 生产链路中 guarantee-web 会装配适配器，因此这里不是安全缺口。
            log.warn("未装配 OperationAuditPort，跳过页面操作审计：action={} target={}:{}",
                    entry.action(), entry.targetType(), entry.targetId());
            return;
        }
        port.record(entry);
    }
}
