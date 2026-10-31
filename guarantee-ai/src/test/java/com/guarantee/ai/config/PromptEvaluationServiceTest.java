package com.guarantee.ai.config;

import com.guarantee.ai.config.mapper.*;
import com.guarantee.ai.service.AiChatService;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.*;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PromptEvaluationServiceTest {
    private final PromptEvaluationMapper tasks = mock(PromptEvaluationMapper.class);
    private final AiPromptVersionMapper versions = mock(AiPromptVersionMapper.class);
    private final AiChatService chat = mock(AiChatService.class);
    private final AiConfigService configs = mock(AiConfigService.class);
    private final ChatModel model = mock(ChatModel.class);
    private PromptEvaluationService service;
    private AiConfigSnapshot config;
    private AiPromptVersion draft;

    @BeforeEach void setup() {
        AiConfigItemMapper items = mock(AiConfigItemMapper.class);
        when(items.selectAll()).thenReturn(List.of());
        config = new AiConfigService(items, new AiConfigCatalog()).snapshot();
        when(configs.refreshIfStale()).thenReturn(config);
        when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().model("test-model").build());
        var tx = mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new PromptEvaluationService(tasks, versions, configs, chat, model,
                new MockEnvironment(), new ObjectMapper(), tx);
        draft = new AiPromptVersion(); draft.setVersionNo(5); draft.setStatus("DRAFT");
        var base = PromptComposition.base();
        draft.setBaseVersion(base.version()); draft.setBaseHash(base.contentHash());
        draft.setBaseContent(base.content()); draft.setBusinessContent("候选正文");
        draft.setContent(PromptComposition.compose(base.content(), "候选正文")); draft.setContentHash(PromptVersionService.sha256(draft.getContent()));
        when(versions.lockVersion(5)).thenReturn(draft);
    }

    @AfterEach void shutdown() { service.shutdown(); }

    private PromptEvaluation passed() {
        PromptEvaluation row = new PromptEvaluation();
        row.setId("test"); row.setVersionNo(5); row.setContent(draft.getContent());
        row.setContentHash(draft.getContentHash()); row.setStatus("PASSED");
        row.setContextHash(service.contextHash(config)); row.setSuiteVersion(PromptEvaluationService.SUITE_VERSION);
        row.setFinishedAt(LocalDateTime.now());
        when(tasks.select("test")).thenReturn(row);
        return row;
    }

    @Test void onlyMatchingFreshEvidenceCanPublish() {
        PromptEvaluation row = passed();
        assertThatCode(() -> service.requirePassed(draft, "test")).doesNotThrowAnyException();
        row.setContentHash("other");
        assertThatThrownBy(() -> service.requirePassed(draft, "test")).hasMessageContaining("失效");
        row.setContentHash(draft.getContentHash()); row.setVersionNo(6);
        assertThatThrownBy(() -> service.requirePassed(draft, "test")).hasMessageContaining("失效");
        row.setVersionNo(5); row.setSuiteVersion("old-suite");
        assertThatThrownBy(() -> service.requirePassed(draft, "test")).hasMessageContaining("失效");
        row.setSuiteVersion(PromptEvaluationService.SUITE_VERSION); row.setFinishedAt(LocalDateTime.now().minusHours(25));
        assertThatThrownBy(() -> service.requirePassed(draft, "test")).hasMessageContaining("失效");
    }

    @Test void changedModelAndUnfinishedTasksCannotPublish() {
        PromptEvaluation row = passed();
        when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().model("different").build());
        assertThatThrownBy(() -> service.requirePassed(draft, "test")).hasMessageContaining("失效");
        row.setStatus("RUNNING");
        assertThatThrownBy(() -> service.requirePassed(draft, "test")).hasMessageContaining("未完成");
        row.setStatus("ERROR");
        assertThatThrownBy(() -> service.requirePassed(draft, "test")).hasMessageContaining("未完成");
    }

    @Test void staleSubmissionAndConcurrentTaskDoNotCallModel() {
        assertThatThrownBy(() -> service.start(5, "old", "7")).hasMessageContaining("草稿已变化");
        when(tasks.running()).thenReturn(1);
        assertThatThrownBy(() -> service.start(5, draft.getContentHash(), "7")).hasMessageContaining("运行中");
        verifyNoInteractions(chat);
        verify(tasks, never()).insert(any());
    }

    @Test void backgroundTaskUsesFrozenCandidateAndRecordsComparison() throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<PromptEvaluation> stored = new AtomicReference<>();
        doAnswer(inv -> { stored.set(inv.getArgument(0)); return 1; }).when(tasks).insert(any());
        when(tasks.select(anyString())).thenAnswer(inv -> stored.get());
        doAnswer(inv -> { finished.countDown(); return 1; }).when(tasks).finish(any());
        when(chat.evaluateCandidate(anyString(), anyString(), anyList(), eq(config), any()))
                .thenAnswer(inv -> {
                    String question = inv.getArgument(1);
                    if (question.equals("订单有多少？")) return new AiChatService.EvaluationAnswer("请问统计哪个时间范围？", List.of());
                    String arguments = question.equals("查全部历史订单") ? "{}"
                            : "{\"startDate\":\"2026-09-01\",\"endDate\":\"2026-09-30\"}";
                    return new AiChatService.EvaluationAnswer("查询到37笔订单", List.of(new AiChatService.EvaluationCall("queryOrderSummary", arguments)));
                });
        service.start(5, draft.getContentHash(), "7");
        draft.setContent("评测中继续编辑");
        assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(stored.get().getContent()).isEqualTo(PromptComposition.compose(PromptComposition.base().content(), "候选正文"));
        assertThat(stored.get().getStatus()).isEqualTo("PASSED");
        assertThat(stored.get().getResults()).contains("baseline", "candidate", "2026-09-01");
        verify(chat, times(4)).evaluateCandidate(eq(PromptComposition.compose(PromptComposition.base().content(), "候选正文")), anyString(), anyList(), eq(config), eq(java.time.LocalDate.of(2026,10,21)));
    }

    @Test void behaviorAssertionsRejectWrongDatesAndFabricatedCounts() {
        assertThat(service.accepts(0, new AiChatService.EvaluationAnswer("统计时间为上月，共37笔", List.of()))).isFalse();
        assertThat(service.accepts(1, new AiChatService.EvaluationAnswer("37笔", List.of(
                new AiChatService.EvaluationCall("queryOrderSummary", "{\"startDate\":\"2026-10-01\"}"))))).isFalse();
        assertThat(service.accepts(2, new AiChatService.EvaluationAnswer("37笔", List.of(
                new AiChatService.EvaluationCall("queryOrderSummary", "{\"startDate\":\"2026-09-01\"}"))))).isFalse();
    }

    @Test void providerFailureIsNeverPassedAndDoesNotExposeExceptionText() throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<PromptEvaluation> stored = new AtomicReference<>();
        doAnswer(inv -> { stored.set(inv.getArgument(0)); return 1; }).when(tasks).insert(any());
        when(tasks.select(anyString())).thenAnswer(inv -> stored.get());
        doAnswer(inv -> { finished.countDown(); return 1; }).when(tasks).finish(any());
        when(chat.evaluateCandidate(anyString(), anyString(), anyList(), any(), any()))
                .thenThrow(new IllegalStateException("provider-secret"));
        service.start(5, draft.getContentHash(), "7");
        assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(stored.get().getStatus()).isEqualTo("ERROR");
        assertThat(stored.get().getSummary()).doesNotContain("provider-secret");
    }
}
