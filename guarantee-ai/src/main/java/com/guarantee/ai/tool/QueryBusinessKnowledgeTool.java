package com.guarantee.ai.tool;

import com.guarantee.ai.knowledge.KnowledgeSearchResult;
import com.guarantee.ai.knowledge.KnowledgeService;
import com.guarantee.ai.service.KnowledgeClaimGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 业务知识检索工具 {@code queryBusinessKnowledge}（REQ-RAG-03，唯一的知识入口）。
 *
 * <p><b>分层</b>：Tool → {@link KnowledgeService} → Mapper → DB。本类**只注入 Service**，
 * 不注入 Mapper、不生成 SQL（§2.3-2）。</p>
 *
 * <p><b>权限</b>：登录即可调用，**不在提示词层做裁剪**——服务端按每条的
 * {@code permission_code} 过滤（REQ-RAG-07）。无权限的条目既不返回、也不提示存在。</p>
 *
 * <p><b>条数与预算</b>：{@code limit} 由 Service 归一（默认 3 / 上限 5，且受配置项收紧），
 * 单条正文 ≤2KB、合计 ≤配置的 {@code max-bytes}。截断通过 {@code meta.truncated} 如实上报。</p>
 *
 * <p><b>边界（写进工具描述，模型看得见）</b>：本工具只回答定义 / 口径 / 概念 / 制度类问题；
 * 任何业务数字必须走业务统计工具。知识条目里的数字只是定义或示例，绝不能当作统计结果
 * （REQ-RAG-06 / 红线 §2.3-1）。</p>
 *
 * <p><b>本轮事实</b>：每次调用都把真实的 {@link KnowledgeSearchResult} 记进
 * {@link KnowledgeClaimGuard.TurnKnowledge}（随 {@code ToolContext} 下传）。
 * 收尾时服务端据此生成「知识来源：…」行——这是溯源行不被模型编造的结构性保证。</p>
 */
@Component
public class QueryBusinessKnowledgeTool {

    private static final Logger log = LoggerFactory.getLogger(QueryBusinessKnowledgeTool.class);

    private final KnowledgeService knowledgeService;

    public QueryBusinessKnowledgeTool(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @Tool(name = "queryBusinessKnowledge",
            description = """
                    检索平台的业务知识库：定义、口径、概念、制度类问题的唯一入口。
                    返回命中的知识条目（编号 knowledgeNo、标题 title、正文 content、版本 version、
                    生效期 effectiveRange、来源说明 sourceRef），以及 meta.dataSource
                    （形如「知识库：命中 N 条（域：SYSTEM）」或「知识库：未收录」）与 meta.truncated。
                    什么时候用它：
                      - 用户问「XX 是什么意思」「XX 和 YY 有什么区别」「XX 的口径/规则是怎么规定的」
                        「制度里怎么写的」这类**定义、口径、概念、制度**问题；
                      - 用户问「知识库里有这条吗」。
                    什么时候**不要**用它：
                      - 任何**业务数字**（订单量、保额合计、保费、企业数、项目数、趋势、排名）必须用
                        业务统计工具查询；知识条目里的数字只是定义或示例，**绝不能当作统计结果**；
                      - 本工具不查业务数据，也不返回任何统计数字。
                    引用纪律：
                      - 条目编号、标题、版本只能**逐字**取自本工具本次返回值，禁止改写、跨条拼接，
                        也禁止引用本次没有返回的条目；
                      - 返回 0 条（dataSource 为「知识库：未收录」）时必须如实说「知识库未收录」，
                        **不得**凭记忆、训练知识或推测编一段依据；
                      - 不要自己在正文里写「知识来源：」行——本轮真实引用由系统在回答末尾统一追加。""")
    public QueryBusinessKnowledgeToolResult queryBusinessKnowledge(
            @ToolParam(description = "用户问题里的关键词或原句，例如「保额区间的口径」「停用和删除的区别」")
            String query,
            @ToolParam(description = "知识域：ORDER=订单与统计口径，SYSTEM=系统管理域，"
                    + "CONCEPT=领域概念，POLICY=制度。不传表示全部域", required = false)
            String domain,
            @ToolParam(description = "返回条数，默认 3，最大 5", required = false)
            Integer limit,
            ToolContext toolContext) {

        // 权限不在这里判定：登录即可调用，可见性由 Service 按条目的 permission_code 裁剪
        List<String> permissions = AiPermissionGuard.permissions(toolContext);
        KnowledgeSearchResult result = knowledgeService.search(permissions, query, domain, limit);

        // 记录本轮真实检索事实：服务端来源行的唯一真值来源（模型看不到这行是"服务端追加"的细节）
        KnowledgeClaimGuard.TurnKnowledge turnKnowledge = KnowledgeClaimGuard.turnKnowledge(toolContext);
        if (turnKnowledge != null) {
            turnKnowledge.record(result);
        }

        log.info("Tool queryBusinessKnowledge 执行完成 domain={} limit={} 命中={} 返回={} truncated={}",
                domain, limit, result.totalHits(), result.items().size(), result.truncated());
        return QueryBusinessKnowledgeToolResult.of(result);
    }
}
