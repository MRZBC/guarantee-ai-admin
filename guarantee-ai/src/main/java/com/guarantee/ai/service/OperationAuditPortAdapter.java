package com.guarantee.ai.service;

import com.guarantee.common.exception.BizException;
import com.guarantee.common.security.CurrentUser;
import com.guarantee.common.security.OperationAuditPort;
import com.guarantee.common.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 页面直连操作审计适配器（SYS-A-07 / AC-22）。
 *
 * <p><b>身份与渠道的分工</b>（本类的核心约定）：</p>
 * <ul>
 *   <li><b>操作者身份唯一来源是 {@link CurrentUser}</b>。两条渠道的操作者都是这个登录用户——
 *       助手确认同样是一个带 JWT 的普通 HTTP 请求（{@code AiController#confirmProposal}
 *       里就从 {@code CurrentUser} 构造了 {@code ProposalExecutionContext}），
 *       因此这里不需要、也不应该接受调用方传入的身份。</li>
 *   <li><b>渠道由 {@link AuditSourceContext} 区分</b>。身份相同不代表审计相同：
 *       "用户自己点的页面"与"用户说句话让助手改的"必须能事后区分（AC-22），
 *       而且同一次变更只能有一条审计记录。本适配器只处理页面直连这条渠道
 *       （{@code source=WEB}）；助手渠道由 {@code ProposalService} 自己记录
 *       （{@code source=AI}，带提案号/会话号），{@code WebAuditor} 会据此跳过。</li>
 * </ul>
 *
 * <p><b>失败即回滚</b>：本类不吞异常。审计写不进去时，业务变更必须一起回滚——
 * "数据改了但没痕迹"是无法事后补救的，比"操作失败"严重得多（SYS-A-03）。</p>
 */
@Component
public class OperationAuditPortAdapter implements OperationAuditPort {

    private static final Logger log = LoggerFactory.getLogger(OperationAuditPortAdapter.class);

    /** 页面渠道标记。 */
    private static final String SOURCE_WEB = "WEB";

    private final OperationAuditService auditService;

    public OperationAuditPortAdapter(OperationAuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public Long record(WebAuditEntry entry) {
        CurrentUser.Principal principal = CurrentUser.get();
        if (principal == null) {
            // 正常链路不可能走到这里：页面直连必然经过 JwtAuthenticationFilter，
            // 而 requirePrincipal() 已在 Controller 层拦过一道。
            // 走到这里说明有代码绕过了 Controller 直接调用写 Service（例如测试脚手架或
            // 未来的内部任务）。此时**必须失败**，而不是写一条身份为空的审计——
            // 审计的可信度来自"每条记录都能定位到人"，写匿名记录等于污染整张表。
            throw new BizException(
                    "缺少操作者身份（CurrentUser 为空），无法记录审计，操作已取消。"
                            + "若为内部调用，请先通过 CurrentUser.set(...) 建立身份上下文");
        }

        OperationAuditService.AuditEntry auditEntry = new OperationAuditService.AuditEntry(
                SOURCE_WEB,
                entry.action(),
                entry.targetType(),
                entry.targetId(),
                entry.targetName(),
                entry.before(),
                entry.after(),
                entry.result(),
                entry.errorMessage(),
                entry.changedFields() == null ? java.util.Set.of() : entry.changedFields());

        // 机构不再是人/部门的归属属性（机构服务于订单）：审计里不再有"操作人机构"这个字段，
        // 对应的 operator_org_id 列已删除（ddl 见 V5__drop_operator_org_id.sql）
        OperationAuditService.OperatorContext operator = new OperationAuditService.OperatorContext(
                principal.userId(), principal.username(), principal.realName());

        Long auditId = auditService.record(auditEntry, operator, null, null,
                TraceContext.currentTraceId());
        log.debug("页面操作审计已写入 id={} action={} target={}:{}",
                auditId, entry.action(), entry.targetType(), entry.targetId());
        return auditId;
    }
}
