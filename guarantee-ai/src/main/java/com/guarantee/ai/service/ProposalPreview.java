package com.guarantee.ai.service;

import java.util.List;
import java.util.Map;

/**
 * 提案的预览载荷（落库于 {@code ai_operation_proposal.preview_payload}）。
 *
 * <p>与 {@link ProposalPayload} 的区别：本类只承载"变更内容与影响面"，
 * 不含提案状态、时间戳等运行期字段，因此可以原样持久化并在任意时刻还原确认卡
 * （SYS-C-14：刷新页面后必须能恢复渲染）。</p>
 */
public record ProposalPreview(
        String summary,
        List<ChangeItem> changes,
        List<String> impact,
        List<String> warnings,
        boolean dangerous,
        /** 恢复确认卡所需的附加上下文（例如目标展示名）。 */
        Map<String, Object> extra) {

    /** 单个字段的变更明细。 */
    public record ChangeItem(String field, String label, String before, String after) {

        /** 新增动作：只有新值。 */
        public static ChangeItem created(String field, String label, String after) {
            return new ChangeItem(field, label, null, after);
        }

        /** 删除/清空动作：只有原值。 */
        public static ChangeItem removed(String field, String label, String before) {
            return new ChangeItem(field, label, before, null);
        }
    }

    public static ProposalPreview of(String summary, List<ChangeItem> changes,
                                     List<String> impact, List<String> warnings, boolean dangerous) {
        return new ProposalPreview(summary, changes, impact, warnings, dangerous, Map.of());
    }
}
