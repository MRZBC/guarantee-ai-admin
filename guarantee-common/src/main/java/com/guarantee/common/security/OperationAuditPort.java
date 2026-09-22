package com.guarantee.common.security;

import java.util.Map;
import java.util.Set;

/**
 * 操作审计写入端口（SYS-A-07 / AC-22）。
 *
 * <p><b>为什么需要这个端口</b>：审计的写入实现（{@code OperationAuditService}）位于
 * {@code guarantee-ai}，而触发审计的业务写操作位于 {@code guarantee-system}。
 * 若由 system 直接依赖 ai，会形成"底层模块依赖上层模块"的反向依赖。
 * 这里用一个极小的端口把方向摆正——与 {@link UserTokenRevoker} 是同一手法。</p>
 *
 * <p><b>同事务要求（SYS-A-03）</b>：实现必须与业务执行在**同一事务**内，
 * 且审计写入失败要抛出异常让业务回滚。本项目刻意**不做**"审计失败只告警"的降级：
 * "数据改了但没痕迹"比"操作失败"更严重（前者无法事后补救）。</p>
 *
 * <p><b>来源区分</b>：本端口固定写 {@code source=WEB}（页面直连）。
 * 助手确认那条路径由 {@code ProposalService} 直接调用审计服务，写 {@code source=AI}。
 * 两条渠道写同一张表，这正是 AC-22 的要求。</p>
 */
public interface OperationAuditPort {

    /**
     * 记录一次操作审计。
     *
     * @param entry 审计内容（敏感字段传**原值**，脱敏由实现统一完成，见 D-4）
     * @return 审计记录主键
     * @throws RuntimeException 审计写入失败时抛出，调用方的事务应因此回滚
     */
    Long record(WebAuditEntry entry);

    /**
     * 页面直连操作的审计内容。
     *
     * @param action        CREATE / UPDATE / ENABLE / DISABLE / ASSIGN_ROLES / ASSIGN_PERMISSIONS
     * @param targetType    USER / ORG / DEPT / ROLE / INSURANCE_TYPE
     * @param targetId      目标主键；新增时为 null
     * @param targetName    目标展示名（写入前会被截断到 128）
     * @param before        变更前结构化快照；新增为 null
     * @param after         变更后结构化快照；停用/删除为 null
     * @param result        SUCCESS / FAILED
     * @param errorMessage  失败原因；成功为 null
     * @param changedFields 变更字段列表；为 null 时由实现按 before/after 自动推导
     */
    record WebAuditEntry(
            String action,
            String targetType,
            Long targetId,
            String targetName,
            Map<String, Object> before,
            Map<String, Object> after,
            String result,
            String errorMessage,
            Set<String> changedFields) {

        /** 成功记录的便捷构造。 */
        public static WebAuditEntry success(String action, String targetType, Long targetId,
                                            String targetName,
                                            Map<String, Object> before, Map<String, Object> after) {
            return new WebAuditEntry(action, targetType, targetId, targetName,
                    before, after, "SUCCESS", null, null);
        }
    }
}
