package com.guarantee.ai.tool;

import com.guarantee.ai.knowledge.KnowledgeDomain;
import com.guarantee.ai.knowledge.KnowledgeHit;
import com.guarantee.ai.knowledge.KnowledgeProperties;
import com.guarantee.ai.knowledge.KnowledgeSearchResult;
import com.guarantee.ai.knowledge.KnowledgeService;
import com.guarantee.ai.knowledge.KnowledgeStatus;
import com.guarantee.ai.knowledge.entity.AiKnowledgeItem;
import com.guarantee.ai.knowledge.mapper.AiKnowledgeItemMapper;
import com.guarantee.ai.service.KnowledgeClaimGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code queryBusinessKnowledge} 工具的行为（REQ-RAG-03 / 06）。
 *
 * <p>两个层次都测：</p>
 * <ul>
 *   <li>**委托与出参形状**：工具不自己归一 limit，也不自己打分——它把参数原样交给
 *       {@link KnowledgeService} 并如实转交 {@code meta}；</li>
 *   <li>**端到端到 Service**：用真实的 {@code KnowledgeService} + 内存 Mapper 跑一遍，
 *       证明"工具 → Service → Mapper"这条链（含 limit 归一与未收录口径）真的成立。</li>
 * </ul>
 */
class QueryBusinessKnowledgeToolTest {

    @Test
    @DisplayName("命中：出参形状为 {items, meta:{dataSource,truncated}}，参数原样交给 Service")
    void returnsServiceResultWithMeta() {
        KnowledgeService service = mock(KnowledgeService.class);
        KnowledgeHit hit = hit("KB-ORDER-0001", "保额区间的口径", 1);
        when(service.search(any(), any(), any(), any())).thenReturn(
                KnowledgeSearchResult.of(List.of(hit), false, 1, KnowledgeDomain.ORDER));

        QueryBusinessKnowledgeToolResult result = new QueryBusinessKnowledgeTool(
                service).queryBusinessKnowledge("保额区间", "ORDER", 9, toolContext());

        assertThat(result.items()).containsExactly(hit);
        assertThat(result.meta().dataSource()).isEqualTo("知识库：命中 1 条（域：ORDER）");
        assertThat(result.meta().truncated()).isFalse();
        assertThat(result.meta().denied()).isFalse();
        // limit 的归一（默认 3 / 上限 5）在 Service 层，工具只负责原样传递
        verify(service).search(List.of(com.guarantee.common.security.Permissions.AI_CHAT),
                "保额区间", "ORDER", 9);
    }

    @Test
    @DisplayName("limit 归一由 Service 承担：要 99 条只给 5 条，不传给默认 3 条")
    void limitIsNormalizedByService() {
        QueryBusinessKnowledgeTool tool = realTool(fiveMatchingItems());

        assertThat(tool.queryBusinessKnowledge("保额区间", null, 99, toolContext()).items())
                .as("需求硬上限 5").hasSize(5);
        assertThat(tool.queryBusinessKnowledge("保额区间", null, null, toolContext()).items())
                .as("默认 3 条").hasSize(3);
        assertThat(tool.queryBusinessKnowledge("保额区间", null, 0, toolContext()).items())
                .as("非法 limit 回落默认").hasSize(3);
    }

    @Test
    @DisplayName("未收录：items 为空、dataSource 明说未收录，工具不回填任何内容")
    void emptyResultIsHonest() {
        QueryBusinessKnowledgeTool tool = realTool(List.of());

        QueryBusinessKnowledgeToolResult result =
                tool.queryBusinessKnowledge("保证金退还流程怎么走", null, 3, toolContext());

        assertThat(result.items()).isEmpty();
        assertThat(result.meta().dataSource()).isEqualTo("知识库：未收录");
        assertThat(result.meta().truncated()).isFalse();
    }

    @Test
    @DisplayName("截断：meta.truncated 为真并给出可读提示（要求如实说明只看了前 N 条）")
    void truncatedIsReported() {
        KnowledgeService service = mock(KnowledgeService.class);
        when(service.search(any(), any(), any(), any())).thenReturn(
                KnowledgeSearchResult.of(List.of(hit("KB-ORDER-0001", "保额区间的口径", 1)),
                        true, 7, KnowledgeDomain.ORDER));

        QueryBusinessKnowledgeToolResult result = new QueryBusinessKnowledgeTool(
                service).queryBusinessKnowledge("保额区间", null, 1, toolContext());

        assertThat(result.meta().truncated()).isTrue();
        assertThat(result.meta().truncatedHint()).contains("只看了前 1 条");
    }

    @Test
    @DisplayName("本轮事实：真实检索结果写入 ToolContext 里的收集器（服务端来源行的唯一来源）")
    void recordsTurnKnowledge() {
        KnowledgeClaimGuard.TurnKnowledge turn = new KnowledgeClaimGuard.TurnKnowledge();
        QueryBusinessKnowledgeTool tool = realTool(List.of(matchingItem("KB-ORDER-0001", "保额区间的口径", 2)));

        tool.queryBusinessKnowledge("保额区间", null, 3, toolContext(turn));

        assertThat(turn.retrieved()).isTrue();
        assertThat(turn.sourceFragments()).containsExactly("KB-ORDER-0001《保额区间的口径》v2");
        assertThat(turn.dataSources()).hasSize(1);
    }

    @Test
    @DisplayName("收集器未挂载（单测/内部调用直连工具）时不得抛错")
    void worksWithoutTurnCollector() {
        QueryBusinessKnowledgeTool tool = realTool(List.of(matchingItem("KB-ORDER-0001", "保额区间的口径", 1)));

        QueryBusinessKnowledgeToolResult result =
                tool.queryBusinessKnowledge("保额区间", null, 3, toolContext());

        assertThat(result.items()).hasSize(1);
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private static ToolContext toolContext() {
        return toolContext(null);
    }

    /** 权限快照固定为 ai:chat（工具不按权限裁剪，可见性由 Service 按 permission_code 过滤）。 */
    private static ToolContext toolContext(KnowledgeClaimGuard.TurnKnowledge turn) {
        Map<String, Object> context = new HashMap<>();
        context.put(AiToolContextKeys.PERMISSIONS, List.of(com.guarantee.common.security.Permissions.AI_CHAT));
        if (turn != null) {
            context.put(KnowledgeClaimGuard.CONTEXT_KEY, turn);
        }
        return new ToolContext(context);
    }

    /** 真实的 KnowledgeService + 内存 Mapper：验证"工具 → Service → Mapper"链路。 */
    private static QueryBusinessKnowledgeTool realTool(List<AiKnowledgeItem> items) {
        AiKnowledgeItemMapper mapper = mock(AiKnowledgeItemMapper.class);
        when(mapper.selectSearchCandidates(any(), eq(KnowledgeStatus.PUBLISHED.name()))).thenReturn(items);
        return new QueryBusinessKnowledgeTool(new KnowledgeService(mapper, new KnowledgeProperties()));
    }

    private static List<AiKnowledgeItem> fiveMatchingItems() {
        List<AiKnowledgeItem> items = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            items.add(matchingItem("KB-ORDER-000" + i, "保额区间的口径" + i, 1));
        }
        return items;
    }

    private static AiKnowledgeItem matchingItem(String no, String title, int version) {
        AiKnowledgeItem item = new AiKnowledgeItem();
        item.setKnowledgeNo(no);
        item.setDomain(KnowledgeDomain.ORDER);
        item.setTitle(title);
        item.setContent("保额区间是险种配置的一部分。");
        item.setKeywords("保额区间,保额,上下限,不限,险种");
        item.setVersion(version);
        item.setStatus(KnowledgeStatus.PUBLISHED);
        return item;
    }

    private static KnowledgeHit hit(String no, String title, int version) {
        return new KnowledgeHit(no, title, "正文。", version, "长期有效", null, KnowledgeDomain.ORDER);
    }
}
