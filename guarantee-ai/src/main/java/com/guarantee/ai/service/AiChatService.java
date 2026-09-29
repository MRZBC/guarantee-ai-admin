package com.guarantee.ai.service;

import com.guarantee.ai.config.AiConfigCatalog;
import com.guarantee.ai.config.AiConfigService;
import com.guarantee.ai.config.AiConfigSnapshot;
import com.guarantee.ai.dto.AiChatRequest;
import com.guarantee.ai.entity.AiConversation;
import com.guarantee.ai.entity.AiMessage;
import com.guarantee.ai.mapper.AiConversationMapper;
import com.guarantee.ai.metrics.AiChatMetrics;
import com.guarantee.ai.metrics.AiTurnMetric;
import com.guarantee.ai.metrics.TurnMetricService;
import com.guarantee.ai.time.TimeRange;
import com.guarantee.ai.time.TimeSemanticParser;
import com.guarantee.ai.tool.AiToolContextKeys;
import com.guarantee.ai.tool.AiToolRegistry;
import com.guarantee.ai.tool.DataMetrics;
import com.guarantee.ai.tool.ToolCallEvent;
import com.guarantee.ai.tool.ToolCallEventSink;
import com.guarantee.ai.tool.TurnFacts;
import com.guarantee.ai.vo.ChatStreamEvents;
import com.guarantee.common.security.CurrentUser;
import com.guarantee.common.security.Permissions;
import com.guarantee.common.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * AI 聊天核心服务：SSE 流式输出 + Tool 调用循环 + 会话落库。
 *
 * <p><b>为什么自己驱动工具循环</b>：Spring AI 2.0 的 {@code ToolCallingAdvisor}
 * 由 {@code DefaultChatClient} 按请求自动装配，但在本项目的装配方式下并不会进入顾问链
 * （已实测：自定义顾问会被调用，{@code ToolCallingAdvisor} 不会）。
 * 这里改为直接使用框架提供的 {@link ToolCallingManager} 显式驱动循环，好处是：</p>
 * <ul>
 *   <li>行为完全可控、可测试，不依赖隐式自动装配；</li>
 *   <li>每一轮工具执行都能被 {@code RecordingToolCallback} 精确计时并落库；</li>
 *   <li>工具调用轮次本身不产生正文（assistant 只返回 tool_calls），
 *       因此可以把每轮流式内容直接转发给前端，最终答案依然是**真流式**。</li>
 * </ul>
 *
 * <p>一次请求的完整链路：</p>
 * <ol>
 *   <li>解析/新建会话，写入用户消息；</li>
 *   <li>用 {@link TimeSemanticParser} 把「2026年第三季度」这类语义预解析成明确日期注入 System Prompt；</li>
 *   <li>流式调用模型；若返回 tool_calls，则交给 {@link ToolCallingManager} 执行，
 *       执行过程通过 {@link ToolCallEventSink} 实时推给前端并落库；</li>
 *   <li>把工具结果回灌会话继续下一轮，直到模型给出最终答案；</li>
 *   <li>落库助手消息并关联本轮全部 Tool Call。</li>
 * </ol>
 */
@Service
public class AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);

    /*
      本类原先的四个硬编码常量（HISTORY_LIMIT=20 / MAX_TOOL_ROUNDS=4 /
      MAX_TOOL_CALLS_PER_ROUND=12 / SOFT_TIMEOUT_MS=60000）已迁到 AI 配置（T4-01 接线，REQ-CFG-06）：
      键名见 {@link AiConfigCatalog}，默认值与改造前逐项一致（AC-CFG-08）。
      运行期的唯一来源是**每轮请求开始时取的配置快照**（见 stream()），
      因此"改配置不重启、下一轮生效"与"一轮对话用同一份配置"同时成立。
      这里刻意不再保留同名常量：同值常量与配置默认值两处定义必然漂移（本项目已有先例）。
    */

    /**
     * 收尾轮（工具轮次用尽后那一轮）的指令。
     *
     * <p>措辞与 {@link #REPAIR_MARKER} 同一风格：说清事实与要求，不指责、不要求道歉——
     * 模型多写一段检讨，用户就多读一段废话。</p>
     *
     * <p>轮次上限来自配置，因此指令必须**按轮次生成**而不是写成静态常量
     * （静态常量会把"缺省值"写死进文案，改配置后用户看到的数字就对不上）。</p>
     */
    static String finalRoundInstruction(int maxRounds) {
        return "【系统提示：本轮只读工具调用已达到上限（" + maxRounds + " 轮），"
                + "并且已取消你的工具调用能力。请立即依据上面**已经获得的数据**给出最终结论，"
                + "不要再尝试调用任何工具；若某个维度确实没有查到数据，就如实说明该维度缺失。】";
    }

    /**
     * 框架自带硬上限触顶时的收口轮指令。
     *
     * <p>Spring AI 的 {@code DefaultToolCallingManager} 另有两条默认硬上限
     * （单工具 40 次 / 整轮 150 次，触顶行为 {@code THROW}）。它同样属于"取数触顶"，
     * 因此按 REQ-BA-06 的统一口径处理：停止取数 → 收口轮，而不是把整轮变成 error。</p>
     */
    static final String TOOL_LIMIT_INSTRUCTION =
            "【系统提示：本轮工具调用已经达到系统的单次分析上限，并且已取消你的工具调用能力。"
                    + "请立即依据上面**已经获得的数据**给出最终结论，不要再尝试调用任何工具；"
                    + "若某个维度确实没有查到数据，就如实说明该维度缺失。】";

    /**
     * 单轮调用超上限时，回灌给模型的**工具结果**（不是错误）。
     *
     * <p>为什么不直接失败：上限的本意是防"逐个小步试错"，不是让用户拿不到答案。
     * 因此超出部分不执行，但该次调用的 {@code tool_call_id} 必须有一条可读回复，
     * 模型才知道"这一项没查、下一轮补上"。</p>
     *
     * <p>改造前的上限是 12（键 {@code budget.max-calls-per-round}）；文案里的数字取自
     * **本轮生效的配置**，否则改配置后模型告诉用户的数字就是错的。</p>
     */
    static String overBudgetToolResult(String toolName, int maxCallsPerRound) {
        return "本轮工具调用过多，仅执行了前 " + maxCallsPerRound + " 个；"
                + "本次「" + toolName + "」调用**未执行**，该项数据本轮没有取到。"
                + "请在下一轮只查这一项（或减少单轮要查的项目数）。";
    }

    /**
     * 软超时触顶时的收口轮指令。
     *
     * <p>与 {@link #finalRoundInstruction(int)}（轮次用尽）分开写：两者的**原因**不同，
     * 模型只有知道"是因为超时"才不会继续试图取数。措辞风格保持一致。</p>
     *
     * <p>改造前的软超时是 60s（键 {@code budget.soft-timeout-ms}）：
     * 正常问答 8~36 次调用耗时 11~22s、工具执行合计 &lt;1s，60s 对正常问答无感，
     * 只在异常放大时兜底。触顶后**停止取数**，转入收口轮（摘掉工具、用已有数据作答）。</p>
     */
    static String timeBudgetInstruction(long softTimeoutMs) {
        return "【系统提示：本轮取数已超过 " + (softTimeoutMs / 1000) + " 秒的软超时预算，"
                + "并且已取消你的工具调用能力。请立即依据上面**已经获得的数据**给出最终结论，"
                + "不要再尝试调用任何工具；若某个维度确实没有查到数据，就如实说明该维度缺失。】";
    }

    /** 成本日志的固定标记，便于按 tag 检索（REQ-BA-11）。 */
    static final String COST_LOG_TAG = "AI_TURN_COST";

    /**
     * 自动重试指令的识别前缀（第三道兜底的**修复**动作）。
     *
     * <p>用 {@code public} 是为了让集成测试的假模型能识别"这一轮是修复轮"
     * （见 {@code ProposalRepairIT}），不必去猜措辞。</p>
     */
    public static final String REPAIR_MARKER =
            "【系统检测：你上一条回复声称已生成变更提案，但本轮没有任何写工具调用，"
                    + "数据库里并没有生成提案，用户也看不到确认卡】";

    /**
     * 编造提案后的自动重试指令。
     *
     * <p>它只做一件事：把"必须真的调用写工具"这句话，连同**用户自己的上一条要求**一起，
     * 再交给模型一轮。措辞刻意不指责、不要求道歉、不要求解释——模型多写一段检讨，
     * 用户就多读一段废话。</p>
     */
    private static final String REPAIR_INSTRUCTION = REPAIR_MARKER + "\n"
            + "请立刻纠正，不要道歉、不要解释、不要复述这句话本身。规则：\n"
            + "1. 如果用户的要求确实是一次系统变更，现在就调用对应的写工具"
            + "（proposeRoleChange / proposeUserChange / proposeOrgChange / "
            + "proposeDepartmentChange / proposeInsuranceTypeChange）生成提案；"
            + "参数不够就先用只读工具查清楚，不要猜。\n"
            + "2. 严禁再写出任何「提案编号」——编号只能来自写工具的返回值。\n"
            + "3. 如果用户的要求本来就不是变更（只是询问，或意图还不明确），就如实回答，不要提提案。\n";

    /**
     * 历史更正提示的标题（集成测试据此断言它被注入到下一轮的系统提示里）。
     *
     * <p>为什么要有这个提示：编造一旦落库，就会**二次伤害**——模型下一轮读回自己的话，
     * 把它当成既成事实。真机事故（2026-09-24 23:55 编造"角色已改名为行政" → 00:08、00:16
     * 两轮都答"这个角色现在名称是「行政」"，而库里 `updated_at = created_at` 证明从未改名；
     * 00:16 那次更直接导致同屏卡片写「业务运营（无系统配置）」、正文写「行政」）。
     * 已落库的历史不能改写（会破坏"落库 = 用户所见"的口径），因此改为**每一轮都显式告知模型：
     * 那几条回复是判定过的编造，不要引用**。</p>
     */
    public static final String RETRACTION_MARKER = "# 本会话的历史更正（系统生成，必须遵守）";

    /**
     * 扫描历史，把"被判定为编造"的助手回复数量转成一段系统提示片段。
     *
     * @return 没有编造历史时返回空串（不改变既有提示词）
     */
    static String retractionNotice(List<Message> history) {
        int retracted = 0;
        for (Message message : history) {
            if (message instanceof AssistantMessage assistant
                    && assistant.getText() != null
                    && assistant.getText().contains(ProposalClaimGuard.CORRECTION)) {
                retracted++;
            }
        }
        if (retracted == 0) {
            return "";
        }
        return "\n\n" + RETRACTION_MARKER + "\n"
                + "本会话里有 " + retracted + " 条**你此前的回复被系统判定为编造**（它们末尾带「系统提示」）。\n"
                + "那些回复里描述的变更**没有发生**；其中出现的实体名称（角色 / 用户 / 险种等）、状态、"
                + "提案编号**都不是事实**。\n"
                + "用户若问到这些实体，必须**用只读工具重新查询**当前真实状态后再回答，"
                + "严禁引用那几条回复里的任何描述。\n";
    }

    private final ChatModel chatModel;
    private final ToolCallingManager toolCallingManager;
    private final AiConversationService conversationService;
    private final BusinessAssistantPrompt promptProvider;
    private final TimeSemanticParser timeSemanticParser;
    private final AiToolRegistry toolRegistry;
    private final ProposalEventPublisher proposalEventPublisher;
    private final ProposalClaimGuard proposalClaimGuard;
    private final DataSourceClaimGuard dataSourceClaimGuard;
    private final NumberClaimGuard numberClaimGuard;
    private final ObjectMapper objectMapper;
    private final String modelName;

    /**
     * 时间源（纳秒）。生产恒为 {@link System#nanoTime}；单测注入假时钟以验证
     * {@link AiConfigCatalog#BUDGET_SOFT_TIMEOUT_MS} 软超时，避免真的等 60 秒。
     */
    private final LongSupplier nanoClock;

    /** AI 配置服务（T4-01 接线）：预算/温度/模型名的运行期唯一来源。 */
    private final AiConfigService configService;

    /**
     * 会话 Mapper：只用于写"本轮回答所用的配置版本号"（REQ-CFG-05 回答级回溯）。
     *
     * <p>为什么直接注入 Mapper 而不是经 {@code AiConversationService}：该服务当前没有
     * 这个写法的包装方法，而它属于 T4-02 的在途文件；T4-01 的约定是"不改别人的在途文件"。
     * 分层上 Service → Mapper 是允许的（Tool 才禁止碰 Mapper）。若后续 phase4 补了包装方法，
     * 这行依赖可以就地替换（调用点只有 {@link #recordConfigVersion}）。</p>
     */
    private final AiConversationMapper conversationMapper;

    /**
     * 观测指标（T5-01 / REQ-MCP-08）：**只读旁路**，任何异常都不该影响回答。
     * 单测用的包内构造器不注入它，因此可能为 {@code null}（调用点做空值跳过）。
     */
    private AiChatMetrics metrics;

    /**
     * 单轮指标落库（T5-01 / REQ-MCP-09）：一次问答一行（含失败与触顶）。
     * 与 {@link #metrics} 一样，单测路径可能为 {@code null}。
     */
    private TurnMetricService turnMetricService;

    /** 单轮指标落库开关（REQ §6.3）：运维应急可关；关掉后回答与指标计数都不受影响。 */
    @Value("${guarantee.ai.observability.turn-metric-persist:true}")
    private boolean turnMetricPersistEnabled = true;

    @Autowired
    public AiChatService(ChatModel chatModel,
                         ToolCallingManager toolCallingManager,
                         AiConversationService conversationService,
                         BusinessAssistantPrompt promptProvider,
                         TimeSemanticParser timeSemanticParser,
                         AiToolRegistry toolRegistry,
                         ProposalEventPublisher proposalEventPublisher,
                         ProposalClaimGuard proposalClaimGuard,
                         DataSourceClaimGuard dataSourceClaimGuard,
                         NumberClaimGuard numberClaimGuard,
                         ObjectMapper objectMapper,
                         AiConfigService configService,
                         AiConversationMapper conversationMapper,
                         AiChatMetrics metrics,
                         TurnMetricService turnMetricService,
                         @Value("${spring.ai.openai.chat.model:unknown}") String modelName) {
        this(chatModel, toolCallingManager, conversationService, promptProvider, timeSemanticParser,
                toolRegistry, proposalEventPublisher, proposalClaimGuard, dataSourceClaimGuard,
                numberClaimGuard, objectMapper, configService, conversationMapper, modelName, System::nanoTime);
        this.metrics = metrics;
        this.turnMetricService = turnMetricService;
    }

    /** 仅供单测注入时间源（软超时判定），生产链路走上面的 14 参构造。 */
    AiChatService(ChatModel chatModel,
                  ToolCallingManager toolCallingManager,
                  AiConversationService conversationService,
                  BusinessAssistantPrompt promptProvider,
                  TimeSemanticParser timeSemanticParser,
                  AiToolRegistry toolRegistry,
                  ProposalEventPublisher proposalEventPublisher,
                  ProposalClaimGuard proposalClaimGuard,
                  DataSourceClaimGuard dataSourceClaimGuard,
                  NumberClaimGuard numberClaimGuard,
                  ObjectMapper objectMapper,
                  AiConfigService configService,
                  AiConversationMapper conversationMapper,
                  String modelName,
                  LongSupplier nanoClock) {
        this.chatModel = chatModel;
        this.toolCallingManager = toolCallingManager;
        this.conversationService = conversationService;
        this.promptProvider = promptProvider;
        this.timeSemanticParser = timeSemanticParser;
        this.toolRegistry = toolRegistry;
        this.proposalEventPublisher = proposalEventPublisher;
        this.proposalClaimGuard = proposalClaimGuard;
        this.dataSourceClaimGuard = dataSourceClaimGuard;
        this.numberClaimGuard = numberClaimGuard;
        this.objectMapper = objectMapper;
        this.configService = configService;
        this.conversationMapper = conversationMapper;
        this.modelName = modelName;
        this.nanoClock = nanoClock;
    }

    /**
     * 执行一次流式对话。
     *
     * <p><b>配置快照的取用时机</b>（REQ-CFG-06）：请求开始前 {@link AiConfigService#refreshIfStale()}
     * 做一次版本比对（不一致即重载），拿到快照后**整轮都用它**——包括工具注册裁剪、预算、
     * 温度与模型名。这样"改配置不重启、下一轮生效"与"一轮对话不出现半新半旧"同时成立。</p>
     *
     * @return SSE 事件流（meta / delta / tool_call / reset / proposal / proposal_result / done / error）
     */
    public Flux<ServerSentEvent<String>> stream(Long userId, AiChatRequest request) {
        String userText = request.getMessage();
        /*
          TraceId 快照（REQ-MCP-10 / AC-MCP-10）：此处仍在 **Web 线程**，MDC 有效；
          流式链路随后切到 Reactor 线程，MDC 会丢。因此把值显式抓住、随本轮上下文下传
          （ToolContext + TurnCost），绝不依赖 Reactor 上的自然传播。
        */
        String traceId = TraceContext.currentTraceId();

        // 本轮配置快照：DB 不可用时返回上一份/默认值，绝不因此让助手不可用（AC-CFG-07）
        AiConfigSnapshot config = configService.refreshIfStale();

        AiConversation conversation = conversationService.resolveOrCreate(
                userId, request.getConversationId(), userText, resolveModelName(config));
        Long conversationId = conversation.getId();

        // 先取历史，再写入本轮用户消息，避免历史里重复出现本轮问题
        List<Message> history = loadHistory(conversationId, config);
        conversationService.appendMessage(conversationId, "USER", userText);
        conversationService.audit(conversationId, userId, "CHAT", "用户提问已受理");

        Optional<TimeRange> parsedTime = timeSemanticParser.parse(userText);
        // 历史里若有被判定为编造的回复，必须每轮显式提醒模型"那些话不算事实"（见 RETRACTION_MARKER）
        String systemPrompt = promptProvider.build(parsedTime) + retractionNotice(history);

        Sinks.Many<ToolCallEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        Sinks.Many<ChatStreamEvents.Proposal> proposalSink = Sinks.many().unicast().onBackpressureBuffer();
        Sinks.Many<ChatStreamEvents.ProposalResult> resultSink = Sinks.many().unicast().onBackpressureBuffer();
        // 注册通道：提案确认发生在**另一个 HTTP 请求**上，只有靠注册表才能把结果推回本会话
        proposalEventPublisher.register(conversationId, proposalSink, resultSink);

        /*
          本轮工具事实（真实口径 + 真实提案编号）。收尾时服务端用它生成口径页脚、
          并按白名单校验正文里的提案编号——这两样原本靠"要求模型逐字照抄"，
          已多次被真机证明会编造，因此真值必须由服务端掌握。
        */
        TurnFacts turnFacts = new TurnFacts();

        /*
          本轮知识检索事实（REQ-RAG-04）。与 turnFacts 分工：turnFacts 管"数字从哪来"（口径），
          这里管"这段话的依据是哪一条知识"。收尾时服务端据此生成「知识来源：」行，
          并把知识工具返回的 dataSource 从口径行里精确剔除——两类来源行必须分开展示。
        */
        KnowledgeClaimGuard.TurnKnowledge knowledgeTurn = new KnowledgeClaimGuard.TurnKnowledge();

        /*
          本轮预算与成本（REQ-BA-06 / REQ-BA-11）：
          - budget：单轮工具调用数上限，超出不执行、只回灌可读说明；
          - cost：轮次 / 工具调用数 / 工具耗时 / token / 总耗时 / 是否触顶，收尾时汇总成一条日志。
          两者都挂在**本次请求**上（不是单例字段），避免并发会话互相污染。
        */
        ToolCallBudget budget = new ToolCallBudget(config.getInt(AiConfigCatalog.BUDGET_MAX_CALLS_PER_ROUND));
        TurnCost cost = new TurnCost(nanoClock);
        // 本轮上下文绑定（REQ-MCP-09/10）：traceId 与提示词版本随 cost 的**显式**下传，
        // 收尾时即便跑在 Reactor 线程上也能落库/打日志，不依赖 MDC 传播。
        //
        // prompt_version（T4-03）：取的是**本轮实际生效**的提示词版本（DB 无发布版、
        // 回落到 classpath 内置提示词时为 null）。不能用 conversation.getPromptVersion()：
        // 那是上一轮写下的值，提示词刚发布时会把新版本记成旧版本。
        cost.bindTurn(traceId, normalizePromptVersion(promptProvider.activeVersionNo()));

        Map<String, Object> toolContext = buildToolContext(conversationId, userId, userText,
                principalContext(), new ToolCallEventSink(sink), proposalSink, turnFacts, knowledgeTurn);

        ToolCallingChatOptions options = buildToolCallingOptions(toolContext, budget, config);

        List<Message> messages = new ArrayList<>(history.size() + 2);
        messages.add(new SystemMessage(systemPrompt));
        messages.addAll(history);
        messages.add(new UserMessage(buildUserText(userText, parsedTime)));

        StringBuilder answer = new StringBuilder();
        AtomicBoolean persisted = new AtomicBoolean(false);
        /**
         * 本轮请求内是否**执行过任何工具**（读或写）。
         *
         * <p>收尾兜底要用它：口径行只能由工具返回值生成，所以"零工具 + 有口径行"必然是编造。
         * 用 {@code AtomicBoolean} 而不是普通 boolean 是因为工具循环在 Reactor 线程上跑。</p>
         */
        AtomicBoolean toolsExecuted = new AtomicBoolean(false);
        /**
         * 最后一轮真正发给模型的 Prompt。
         *
         * <p>收尾的自动重试要用它：重试必须**接着**最后一轮（含本轮已执行工具的结果），
         * 而不是退回到最初的 {@code messages}——否则模型会把已经查过的东西再查一遍。</p>
         */
        AtomicReference<Prompt> lastPrompt = new AtomicReference<>();

        Flux<ServerSentEvent<String>> contentEvents =
                runToolLoop(new Prompt(messages, options), answer, 0, toolsExecuted, lastPrompt,
                        budget, cost, config)
                /*
                  收尾必须挂在**内容流之内**，不能在 merge 之后另起一段 concatWith：

                  ① 收尾的自动重试要让模型再调一次写工具，而写工具产出的提案是经
                     proposalSink 推到前端的；sink 一旦先被 complete，推送会以
                     FAIL_TERMINATED 失败——**提案落库了，用户却看不到卡片**（实测日志：
                     "推送提案事件失败（FAIL_TERMINATED），提案 684 仅落库"）。
                  ② 顺带保证了事件顺序：提案事件一定排在 done 之前。

                  收尾跑完才关闭三个通道，merge 随之完成。
                */
                .concatWith(Flux.defer(() -> tailEvents(answer, lastPrompt, options, toolsExecuted,
                        budget, cost, conversationId, userId, persisted, userText, turnFacts,
                        knowledgeTurn, config)))
                .doOnComplete(() -> {
                    sink.tryEmitComplete();
                    proposalSink.tryEmitComplete();
                    resultSink.tryEmitComplete();
                });

        // 工具调用明细（工具名 / 入参 / 返回 JSON / 耗时 / 状态）是**工程遥测**：
        // 对业务用户没有价值，还会暴露内部工具名与字段结构。因此按权限决定是否下发
        // （AUTH：ai:debug:view）。
        //
        // 注意这里用 filter 而不是"不并入流"：sink 是 unicast + onBackpressureBuffer，
        // 若无人订阅会一直缓冲。filter 保证事件始终被消费掉，只是不下发。
        //
        // 也正因为是在**服务端**过滤，普通用户拿不到这些数据——前端 v-if 只能让界面不显示，
        // 数据仍在 SSE 响应里，浏览器开发者工具一开就能看到。
        boolean toolDetailVisible = canSeeToolDetails();
        Flux<ServerSentEvent<String>> toolEvents = sink.asFlux()
                .filter(toolCall -> toolDetailVisible)
                .map(toolCall -> event("tool_call", toolCall));

        // 写工具生成的提案：必须紧跟 tool_call 之后推到前端，才能渲染确认卡（5.3.1）
        Flux<ServerSentEvent<String>> proposalEvents = proposalSink.asFlux()
                .map(payload -> event("proposal", payload));

        // 提案结果事件：确认接口在另一个请求上产生，通过注册表推回本流
        Flux<ServerSentEvent<String>> proposalResultEvents = resultSink.asFlux()
                .map(payload -> event("proposal_result", payload));

        Flux<ServerSentEvent<String>> meta = Flux.just(event("meta",
                new ChatStreamEvents.Meta(conversationId, conversation.getConversationNo(),
                        conversation.getTitle())));

        Flux<ServerSentEvent<String>> streamed = Flux.concat(meta,
                Flux.merge(contentEvents, toolEvents, proposalEvents, proposalResultEvents));

        Flux<ServerSentEvent<String>> safe = streamed.onErrorResume(ex -> {
            log.error("AI 流式对话失败 conversationId={}", conversationId, ex);
            persistAssistant(persisted, conversationId, userId, answer.toString());
            conversationService.audit(conversationId, userId, "ERROR", safeMessage(ex));
            // 出错轮也必须留下成本日志与指标行（REQ-BA-11 / REQ-MCP-09：含失败）；logged 保证一轮只写一条
            finishTurnCost(null, conversationId, userId, config, cost, budget, AiTurnMetric.OUTCOME_ERROR);
            /*
              出错轮同样要记"这一轮用的是哪一版配置 + 哪一版提示词"（REQ-CFG-05 / AC-CFG-09）：
              该列的语义是"**最近一次回答**的版本回溯"，失败轮也是一次回答尝试；只写成功轮会让
              "最近一次回答"在失败后指向更早的那一轮（甚至一直空着，回调链断在半路）。
              两者在出错路径上都拿得到：conversationId 来自 resolveOrCreate（第 356 行，失败前已就绪），
              config 是本轮快照，cost.promptVersion() 由 bindTurn（第 403 行）在**错误发生之前**绑定。
              与成功路径一致：写失败只告警，不影响错误事件本身。
            */
            recordConfigVersion(conversationId, config.version(), cost.promptVersion());
            return Flux.just(event("error", new ChatStreamEvents.Error(safeMessage(ex))));
        });

        // 注意：收尾（自动重试 / 纠正 / 落库 / done）已在 contentEvents 之内，
        // 出错时 concatWith 不会执行，与旧实现里 "failed 则跳过收尾" 等价。
        return safe.doFinally(signal -> proposalEventPublisher.unregister(conversationId));
    }

    /**
     * 收尾事件：编造提案的自动重试，或（无编造时）直接结束本轮。
     *
     * <p>判定顺序就是优先级：**先修，再判**。声称有提案而会话内没有 PENDING 提案时，
     * 直接追加一句"并未生成"只解决"用户别被误导"，不解决"用户要的变更没发生"——
     * 真机第三次复现（2026-09-24 23:55，用户只回了一句「确定」）：模型正文写
     * 「我已生成变更提案…提案编号 OP202609242359135602」，而 {@code ai_tool_call} 一条都没有、
     * 库里也不存在该编号，用户看到的是一条**自相矛盾**的回复（抬头写着编号、结尾说没生成），
     * 还得自己重述一遍需求。</p>
     */
    private Flux<ServerSentEvent<String>> tailEvents(StringBuilder answer, AtomicReference<Prompt> promptRef,
                                                     ToolCallingChatOptions options,
                                                     AtomicBoolean toolsExecuted,
                                                     ToolCallBudget budget, TurnCost cost,
                                                     Long conversationId, Long userId,
                                                     AtomicBoolean persisted,
                                                     String userText, TurnFacts turnFacts,
                                                     KnowledgeClaimGuard.TurnKnowledge knowledgeTurn,
                                                     AiConfigSnapshot config) {
        Prompt finalPrompt = promptRef.get();
        String produced = answer.toString();
        if (finalPrompt != null
                && proposalClaimGuard.correctionFor(userId, conversationId, produced).isPresent()) {
            return repairFabricatedProposal(answer, finalPrompt, options, toolsExecuted, budget, cost,
                    conversationId, userId, persisted, promptRef, userText, turnFacts, knowledgeTurn, config);
        }
        return finishTurn(answer, toolsExecuted, budget, cost, conversationId, userId, persisted,
                userText, turnFacts, knowledgeTurn, config);
    }

    /**
     * 把「编造的提案」修成「真的提案」：追加一轮用户侧指令，要求模型立刻调用写工具。
     *
     * <p><b>为什么不是只贴一句纠正提示</b>：提示只解决"用户别被误导"，不解决"用户要的变更没发生"。
     * 真机上用户已经确认过一次（"确定"），再让他重述一遍需求是把模型的错转嫁给用户。
     * 这里是**一次**有界重试：重试后仍编造则退回纠正提示（见 {@link #finishTurn}）。</p>
     *
     * <p><b>必须先发 {@code reset}</b>：那段编造的正文已经流式显示给用户了，
     * 不清掉就会与修复后的回答粘成一段（前端 {@code onReset} 会清空本条气泡）。</p>
     *
     * <p><b>落库只写修复后的正文</b>：编造内容不进入历史，用户刷新后不会再看到它。</p>
     */
    private Flux<ServerSentEvent<String>> repairFabricatedProposal(StringBuilder answer, Prompt sourcePrompt,
                                                                   ToolCallingChatOptions options,
                                                                   AtomicBoolean toolsExecuted,
                                                                   ToolCallBudget budget, TurnCost cost,
                                                                   Long conversationId, Long userId,
                                                                   AtomicBoolean persisted,
                                                                   AtomicReference<Prompt> promptRef,
                                                                   String userText, TurnFacts turnFacts,
                                                                   KnowledgeClaimGuard.TurnKnowledge knowledgeTurn,
                                                                   AiConfigSnapshot config) {
        log.warn("回复声称已生成提案但会话内无 PENDING 提案，自动重试一轮让模型调用写工具 conversationId={}",
                conversationId);
        conversationService.audit(conversationId, userId, "CHAT", "检测到回复编造提案，自动重试一次");
        // 重试轮的正文单独累积：只有它非空时才替换掉原来那段（否则保留原文，交给兜底纠正）
        StringBuilder repaired = new StringBuilder();
        List<Message> instructions = new ArrayList<>(sourcePrompt.getInstructions());
        instructions.add(new AssistantMessage(answer.toString()));
        instructions.add(new UserMessage(REPAIR_INSTRUCTION));
        /*
          修复轮同样受工具轮次上限约束，用尽时也会转入收尾轮。
          注意收尾原因仍记在**本次请求**的 cost 上（caps 是"这一整轮触顶了没有"的口径，
          不因中间夹了一轮修复而重置）；成本日志也只写一条。
        */
        return Flux.concat(
                Flux.just(event("reset", new ChatStreamEvents.Reset())),
                runToolLoop(new Prompt(instructions, options), repaired, 0, toolsExecuted, promptRef,
                        budget, cost, config),
                Flux.defer(() -> {
                    if (StringUtils.hasText(repaired)) {
                        answer.setLength(0);
                        answer.append(repaired);
                    }
                    return finishTurn(answer, toolsExecuted, budget, cost, conversationId,
                            userId, persisted, userText, turnFacts, knowledgeTurn, config);
                }));
    }

    /**
     * 收尾：服务端接管事实 → 兜底校验 → 落库 → 结束事件。
     *
     * <p><b>这一步是"编造"类问题的结构性出口</b>，顺序固定为：</p>
     * <ol>
     *   <li><b>剥离模型自写的口径行</b>（{@link DataSourceClaimGuard#stripDataSourceLines}）——
     *       照抄与编造在文本上无法区分，因此模型写的口径行一律不生效；</li>
     *   <li><b>剥离模型自写的知识来源行</b>（{@link KnowledgeClaimGuard#stripSourceLines}）——
     *       同一道理：知识来源行是"这段话依据哪一条知识"的唯一凭据，只能由服务端产出；</li>
     *   <li><b>提案编号白名单</b>（{@link ProposalNumberGuard}）——正文里的编号必须来自本轮
     *       工具真实返回，或用户自己打出来的串；其余一律移除并留下系统提示；</li>
     *   <li><b>追加服务端口径页脚</b>（{@link DataSourceClaimGuard#footer}）——口径的唯一出口；
     *       知识工具返回的 dataSource 会先被精确剔除，避免与知识来源行混排；</li>
     *   <li><b>追加服务端数据摘要</b>（{@link DataMetrics}）——已登记指标的权威数值，数值溯源出口；</li>
     *   <li><b>追加服务端知识来源行</b>（{@link KnowledgeClaimGuard#footer}）——本轮真实命中的
     *       条目号 + 标题 + 版本，单独成行；未命中不追加；</li>
     *   <li>追加兜底纠正（编造提案 / 零工具却有口径行 / 零工具却有业务数字 / 零检索却有来源行 / 空回答）。</li>
     * </ol>
     *
     * <p><b>为什么"改写过"就要 reset 重发</b>：正文是流式下发的，前端此时已经显示了被移除的
     * 内容。只追加纠正会造成"界面留着假编号、库里没有"，刷新后两副面孔——正是既有设计里
     * 最忌讳的"落库 ≠ 用户所见"。因此只要发生了移除，就先发 {@code reset} 清空气泡、
     * 再把服务端最终正文整体重发一次；没有改写的常见路径完全不变（仍走增量 delta）。</p>
     *
     * <p>纠正文案**追加进本条助手消息**并同步推给前端，刻意不调用
     * {@code conversationService.appendMessage}（即 {@code ProposalService.appendResultMessage}
     * 的机制）：那会额外落一条 ASSISTANT 消息，用户会看到"编造的原话"与"纠正"分成两个气泡，
     * 纠正反而像是无关的一句。这里只有一条消息、一次落库，不存在消息重复。</p>
     */
    private Flux<ServerSentEvent<String>> finishTurn(StringBuilder answer, AtomicBoolean toolsExecuted,
                                                     ToolCallBudget budget, TurnCost cost,
                                                     Long conversationId, Long userId,
                                                     AtomicBoolean persisted,
                                                     String userText, TurnFacts turnFacts,
                                                     KnowledgeClaimGuard.TurnKnowledge knowledgeTurn,
                                                     AiConfigSnapshot config) {
        String produced = answer.toString();
        List<String> corrections = new ArrayList<>(3);
        boolean proposalClaimFlagged = false;
        boolean dataSourceClaimFlagged = false;
        if (produced.isBlank()) {
            /*
              ③ 空回答兜底：无论什么原因，都不能让用户对着空气泡。
              现场反馈原话：「就算有bug或者做不了，也应该兜底一下吧」——
              前端在"零正文 + 零工具调用"时会把气泡整个删掉，用户看到的是"什么都没发生"。
            */
            corrections.add(emptyAnswerNotice(cost.capReason(),
                    config.getInt(AiConfigCatalog.BUDGET_MAX_ROUNDS),
                    config.getLong(AiConfigCatalog.BUDGET_SOFT_TIMEOUT_MS)));
        } else {
            // ① 声称有提案但会话内没有 PENDING 提案（自动重试后仍未解决时才会走到这里）
            proposalClaimFlagged = proposalClaimGuard.correctionFor(userId, conversationId, produced)
                    .map(text -> {
                        corrections.add(text);
                        return true;
                    })
                    .orElse(false);
            // ② 本轮零工具调用却出现「口径：」行。必须在剥离之前判定，否则证据已经被自己删掉
            dataSourceClaimFlagged = dataSourceClaimGuard.correctionFor(produced, toolsExecuted.get())
                    .map(text -> {
                        corrections.add(text);
                        return true;
                    })
                    .orElse(false);
            // ②-b 本轮零工具调用却直接给出业务数字（不写口径行的形态）。
            //     与 ② 说的是同一件事，已判过就不再补第二段系统提示。
            if (!dataSourceClaimFlagged) {
                numberClaimGuard.correctionFor(produced, toolsExecuted.get()).ifPresent(corrections::add);
            }
            // ②-c 本轮零知识检索却出现「知识来源：」行（REQ-RAG-04：无检索就不得有来源行）。
            //     同样必须在剥离之前判定，否则证据已经被自己删掉。
            KnowledgeClaimGuard.correctionFor(produced, knowledgeTurn.retrieved())
                    .ifPresent(corrections::add);
        }

        // 服务端接管事实：先剥离模型自写的口径行与知识来源行，再按白名单校验提案编号
        String sanitized = KnowledgeClaimGuard.stripSourceLines(
                DataSourceClaimGuard.stripDataSourceLines(produced));
        boolean rewritten = !sanitized.equals(produced);
        ProposalNumberGuard.Result numberCheck = ProposalNumberGuard.sanitize(sanitized,
                ProposalNumberGuard.trusted(userText, turnFacts.proposalNumbers()));
        if (numberCheck.changed()) {
            log.warn("正文出现 {} 个非本轮工具返回的提案编号，已移除 conversationId={}",
                    numberCheck.removed().size(), conversationId);
            sanitized = numberCheck.text();
            rewritten = true;
            // 已经判过"这份提案根本没生成"时不再补第二条纠正：两句话说的是同一件事，
            // 连贴两段「系统提示」只会让用户以为出了两个问题
            if (!proposalClaimFlagged) {
                corrections.add(ProposalNumberGuard.CORRECTION);
            }
        }

        // 正文已被改写：先清空前端气泡，再整体重发服务端最终正文，保证"界面 = 落库"
        Flux<ServerSentEvent<String>> events = Flux.empty();
        if (rewritten) {
            answer.setLength(0);
            answer.append(sanitized);
            events = Flux.just(
                    event("reset", new ChatStreamEvents.Reset()),
                    event("delta", new ChatStreamEvents.Delta(sanitized)));
        }

        /*
          服务端口径页脚 + 数据摘要：只在本轮有真实工具返回值时追加。
          正文为空（空回答兜底）时不追加——那段文案刻意不带口径行，带上反而像"有数据"。
          摘要里的数值全部来自工具返回值、字段名与单位在 DataMetrics 里登记过，
          因此"权威数字"不再经过模型的手（数值溯源的服务端出口）。
        */
        StringBuilder tail = new StringBuilder();
        if (!sanitized.isBlank()) {
            /*
              知识工具返回的 dataSource 不能进「口径：」行——口径是"数字从哪来"，
              知识是"这句话的依据是哪一条"。剔除规则见 KnowledgeClaimGuard
              （按收集器记录的原值精确剔除，不做子串猜测）。
            */
            List<String> dataSources = KnowledgeClaimGuard.excludingKnowledgeDataSources(
                    turnFacts.dataSources(), knowledgeTurn);
            tail.append(DataSourceClaimGuard.footer(dataSources));
            tail.append(DataMetrics.renderAll(turnFacts.metricBlocks()));
            // 「知识来源：」单独成行，与口径行 / 数据摘要分开（REQ-RAG-04-5）；
            // 只有本轮**真的检索到条目**才追加（未收录不追加任何来源行，AC-RAG-03）
            tail.append(KnowledgeClaimGuard.footer(knowledgeTurn.sourceFragments()));
        }
        tail.append(String.join("", corrections));

        if (tail.length() > 0) {
            String tailText = tail.toString();
            answer.append(tailText);
            events = events.concatWith(Flux.just(event("delta", new ChatStreamEvents.Delta(tailText))));
        }

        Long messageId = persistAssistant(persisted, conversationId, userId, answer.toString());
        conversationService.audit(conversationId, userId, "CHAT", "助手回答已完成");
        /*
          成本与耗时汇总（REQ-BA-11 / AC-BA-08）：**一条**结构化日志，字段固定为
          conversationId / 轮次 / 工具调用数 / 工具耗时合计 / 输入输出 token / 本次总耗时 / 是否触顶。
          放在落库之后：即便这里抛异常（不会），也已经有一条助手消息可查。
        */
        // 收尾三件事共用同一次收尾：成本日志 + 单轮指标落库 + Micrometer 指标
        finishTurnCost(messageId, conversationId, userId, config, cost, budget,
                cost.capped() ? AiTurnMetric.OUTCOME_CAPPED : AiTurnMetric.OUTCOME_SUCCESS);
        /*
          回答级回溯（REQ-CFG-05 / V8 §3）：记下"这一轮回答用的是哪一版配置 + 哪一版提示词"。
          prompt_version 由 T4-03 在这里接上（取本轮绑定的实际生效版本，见上面的 bindTurn），
          config_version 由 T4-01 负责；两列由同一条 updateVersionTrace 写入。
        */
        recordConfigVersion(conversationId, config.version(), cost.promptVersion());
        return events.concatWith(Flux.just(
                event("done", new ChatStreamEvents.Done(conversationId, messageId))));
    }

    /**
     * 提示词版本号归一化：版本从 1 开始，{@code <=0} 一律记为 null
     * （"无 DB 发布版" 与 "取值失败" 都不该在库里写成 0，那会被读成"第 0 版"）。
     */
    private static Integer normalizePromptVersion(Integer version) {
        return version == null || version <= 0 ? null : version;
    }

    /**
     * 写入本轮回答所用的配置快照版本号与提示词版本号（REQ-CFG-05 回答级回溯）。
     *
     * <p><b>两列一次写完</b>：{@code updateVersionTrace} 同时写 {@code prompt_version} 与
     * {@code config_version}（V8 §3）。{@code promptVersion} 由调用方传入**本轮实际生效**的值
     * （T4-03）；为 null 表示本轮用的是 classpath 内置提示词（DB 无发布版），这本身就是
     * 一次可回溯的事实，不能拿上一轮的值来填。</p>
     *
     * <p><b>写失败只告警，绝不影响回答</b>：这是一条审计线索，不是答案的一部分；
     * 让它把一次成功的问答变成 error 是本末倒置（与成本日志同一取舍）。</p>
     */
    private void recordConfigVersion(Long conversationId, long configVersion, Integer promptVersion) {
        if (conversationId == null) {
            return;
        }
        try {
            conversationMapper.updateVersionTrace(conversationId, promptVersion, configVersion);
        } catch (RuntimeException ex) {
            log.warn("记录回答级版本回溯失败（不影响回答）conversationId={} configVersion={} promptVersion={}",
                    conversationId, configVersion, promptVersion, ex);
        }
    }

    /**
     * 空回答兜底文案（第三道兜底）。
     *
     * <p>写成一条**独立可测**的静态方法：这是唯一一处"确实没答案"时要对用户说的话，
     * 措辞需要能随现场反馈迭代，但不应该被埋在流式装配里。</p>
     *
     * <p>用户看到的必须是「这次没成 + 为什么 + 下一步怎么办」，不能是空气泡。
     * 文案里也刻意**不带口径行**：口径只能逐字来自工具返回值（提示词第 39 条），
     * 兜底文案没有对应的工具返回值，带上就是编造。</p>
     *
     * @param capReason 触顶原因（{@link TurnCost#CAP_ROUNDS} / {@link TurnCost#CAP_DURATION} /
     *                  {@link TurnCost#CAP_TOOL_LIMIT} / {@link TurnCost#CAP_NONE}）。
     *                  说清原因是兜底文案的价值所在：轮次用尽说明"数据其实查过了"，
     *                  超时说明"取数预算用完了"，两者给用户的下一步建议并不一样。
     * @param maxRounds       本轮生效的轮次上限（写进文案，改配置后不会说错数字）
     * @param softTimeoutMs   本轮生效的软超时（毫秒，同上）
     */
    static String emptyAnswerNotice(String capReason, int maxRounds, long softTimeoutMs) {
        String cause;
        if (TurnCost.CAP_DURATION.equals(capReason)) {
            cause = "这一轮取数超过了 " + (softTimeoutMs / 1000)
                    + " 秒的软超时预算，停止取数后收尾那一轮也没能给出结论";
        } else if (TurnCost.CAP_TOOL_LIMIT.equals(capReason)) {
            cause = "这一轮的工具调用已经达到系统的单次分析上限，停止取数后收尾那一轮也没能给出结论";
        } else if (TurnCost.CAP_ROUNDS.equals(capReason)) {
            cause = "这一轮我把 " + maxRounds + " 轮工具调用都用在了取数上，收尾那一轮也没能给出结论";
        } else {
            cause = "模型这一轮没有返回任何内容";
        }
        return "\n\n抱歉，这次没能给出结论：" + cause + "。"
                + "请重试一次；如果仍然如此，建议把问题拆小一点再问，"
                + "例如先问「2026 年第二季度投标订单的订单量与担保金额」，"
                + "再单独问某一个维度（区域 / 机构 / 险种）的变化。";
    }

    // ------------------------------------------------------------------
    // 工具调用循环
    // ------------------------------------------------------------------

    /**
     * 构造带工具的工具调用选项。
     *
     * <p>必须**基于模型自身的默认选项**做 mutate，而不能用
     * {@code ToolCallingChatOptions.builder()} 从零构造：具体实现（如
     * {@code OpenAiChatOptions}）会把 prompt 的 options 强转成自己的类型，
     * 传通用的 {@code DefaultToolCallingChatOptions} 会在运行时抛 ClassCastException。</p>
     *
     * <p>工具集在这里按权限裁剪（SYS-P-12a）：无权限的工具不出现在模型面前。</p>
     *
     * <p><b>再套一层单轮预算</b>（REQ-BA-06）：登记进模型的每个工具都被
     * {@link BudgetedToolCallback} 包住，单轮超出配置项
     * {@link AiConfigCatalog#BUDGET_MAX_CALLS_PER_ROUND} 的调用
     * 不执行、只回灌可读说明。装饰链最外层是预算包装（而不是替换掉 Recording），
     * 因此"执行了哪几次"仍照常落库/推 SSE，"没执行的那几次"根本不会产生假记录。</p>
     *
     * <p><b>配置温度</b>：只有 {@code model.temperature} 被**显式配置**时才覆盖
     * （未配置时沿用模型 starter 的 yml 值，缺省行为与改造前一致，AC-CFG-08）。</p>
     */
    private ToolCallingChatOptions buildToolCallingOptions(Map<String, Object> toolContext,
                                                           ToolCallBudget budget,
                                                           AiConfigSnapshot config) {
        // 注意用 getOptions() 而不是 getDefaultOptions()：
        // OpenAiChatModel.getOptions() 返回 OpenAiChatOptions（其内部会强转 options），
        // 而 getDefaultOptions() 在部分实现里返回的是通用 ChatOptions。
        ChatOptions defaults = chatModel.getOptions();
        ToolCallingChatOptions.Builder<?> builder = defaults instanceof ToolCallingChatOptions toolCallingDefaults
                ? toolCallingDefaults.mutate()
                : ToolCallingChatOptions.builder();
        // 权限必须在调用前可见：Spring AI 只会在执行工具时才把 ToolContext 传给 call()，
        // 因此这里从已构造好的上下文中取权限快照来决定注册哪些工具。
        @SuppressWarnings("unchecked")
        List<String> permissions = toolContext.get(AiToolContextKeys.PERMISSIONS) instanceof List<?> list
                ? (List<String>) list : List.of();
        // 工具裁剪与本轮预算参数用**同一份快照**（REQ-CFG-06：一轮对话不出现半新半旧）
        ToolCallback[] registered = toolRegistry.callbacks(permissions, config);
        List<ToolCallback> budgeted = new ArrayList<>(registered.length);
        for (ToolCallback callback : registered) {
            budgeted.add(new BudgetedToolCallback(callback, budget));
        }
        builder.toolCallbacks(budgeted);
        builder.toolContext(toolContext);
        if (config.isOverridden(AiConfigCatalog.MODEL_TEMPERATURE)) {
            builder.temperature(config.getDouble(AiConfigCatalog.MODEL_TEMPERATURE));
        }
        if (config.isOverridden(AiConfigCatalog.MODEL_NAME)) {
            // 显式配置的模型名必须**随请求发给模型**：只改 ai_conversation.model 的落库值
            // 等于"看起来改了、其实没生效"（TEST-CFG-06 的可观测点正是模型调用参数）。
            // 未配置时不设置，沿用 starter/yml（AC-CFG-08）。
            builder.model(config.get(AiConfigCatalog.MODEL_NAME));
        }
        return builder.build();
    }

    /**
     * 本轮使用的模型名：**显式配置优先，未配置沿用 starter/yml**（AC-CFG-08）。
     *
     * <p>为什么不直接用配置默认值：改造前这里取的是
     * {@code spring.ai.openai.chat.model}（${DEEPSEEK_MODEL:deepseek-chat}）。
     * 若一律以配置默认值覆盖，运维用环境变量切模型时会与落库的会话模型名不一致。</p>
     */
    private String resolveModelName(AiConfigSnapshot config) {
        return config.isOverridden(AiConfigCatalog.MODEL_NAME)
                ? config.get(AiConfigCatalog.MODEL_NAME)
                : modelName;
    }

    /**
     * 执行一轮模型调用；若该轮产生工具调用，则执行工具并递归下一轮。
     *
     * <p><b>为什么必须按轮区分正文：</b>模型在发起工具调用时，往往还会先输出一句
     * 「我这就去查…」式的前言正文（实测 DeepSeek 会输出例如
     * {@code I'll query the tender order statistics for August 2026}）。这类正文只属于
     * 中间过程，不能并入正式回答——否则最终答案会变成「前言 + 真正回答」直接粘连，
     * 既落库也展示给用户。</p>
     *
     * <p>因此这里按轮累积：只有<b>没有工具调用</b>的那一轮（即最终回答轮）才写入
     * {@code answer}。中间轮为了保持实时感仍会流式转发正文，但在进入下一轮前补发
     * {@code reset} 事件，让前端把这一轮已经显示的前言清掉。</p>
     *
     * <p><b>两道预算在每轮开始与"取数之前"各判一次</b>（REQ-BA-06）：软超时
     * （{@link AiConfigCatalog#BUDGET_SOFT_TIMEOUT_MS}）优先于轮次——超时的原因是"预算用完了"，
     * 不是"轮次用尽"，模型与兜底文案都要说对。任一条触顶都**不再取数**，直接进
     * {@link #finalAnswerRound} 用已有数据作答。</p>
     */
    private Flux<ServerSentEvent<String>> runToolLoop(Prompt prompt, StringBuilder answer, int depth,
                                                      AtomicBoolean toolsExecuted,
                                                      AtomicReference<Prompt> lastPromptRef,
                                                      ToolCallBudget budget, TurnCost cost,
                                                      AiConfigSnapshot config) {
        int maxRounds = config.getInt(AiConfigCatalog.BUDGET_MAX_ROUNDS);
        long softTimeoutMs = config.getLong(AiConfigCatalog.BUDGET_SOFT_TIMEOUT_MS);
        if (cost.softTimeoutExceeded(softTimeoutMs)) {
            log.warn("本轮取数已超过软超时 {}ms，转入收口轮（摘掉工具，用已有数据作答）", softTimeoutMs);
            cost.markDurationCap();
            return finalAnswerRound(prompt, answer, timeBudgetInstruction(softTimeoutMs), cost);
        }
        if (depth >= maxRounds) {
            log.warn("工具调用达到最大轮次 {}，转入收尾轮（摘掉工具，只要求最终回答）", maxRounds);
            cost.markRoundsCap();
            return finalAnswerRound(prompt, answer, finalRoundInstruction(maxRounds), cost);
        }
        return Flux.defer(() -> {
            // 记下本轮 Prompt：收尾的自动重试要接着它继续（含本轮已执行工具的结果）
            lastPromptRef.set(prompt);
            // doOnNext 是串行调用的，普通 ArrayList 足够
            List<ChatResponse> collected = new ArrayList<>();
            // 本轮正文：仅当本轮不产生工具调用时，才并入最终回答
            StringBuilder roundText = new StringBuilder();
            cost.modelRounds.incrementAndGet();

            Flux<ServerSentEvent<String>> streamed = chatModel.stream(prompt)
                    .doOnNext(collected::add)
                    .concatMap(chunk -> {
                        String text = textOf(chunk);
                        // 必须用 isEmpty 而不是 StringUtils.hasText：
                        // hasText 对纯空白返回 false，会把「只含空格或换行」的增量分片整个丢掉。
                        // 模型的分片经常会单独给出一个 " " 或 "\n"，一旦丢弃，回答里的空格与换行
                        // 就会缺失，Markdown 的标题/表格/列表结构会被压成一行。
                        if (text == null || text.isEmpty()) {
                            return Flux.empty();
                        }
                        roundText.append(text);
                        return Flux.just(event("delta", new ChatStreamEvents.Delta(text)));
                    });

            return streamed.concatWith(Flux.defer(() -> {
                accumulateUsage(collected, cost);
                List<AssistantMessage.ToolCall> toolCalls = mergeToolCalls(collected);
                if (toolCalls.isEmpty()) {
                    // 最终回答轮：本轮的正文才是要返回给用户并落库的内容
                    answer.append(roundText);
                    return Flux.empty();
                }
                log.debug("模型请求执行 {} 个工具调用（第 {} 轮），本轮前言正文 {} 字符不计入最终回答",
                        toolCalls.size(), depth + 1, roundText.length());

                // 先让前端丢弃本轮前言，再执行工具并进入下一轮，避免前言与最终回答粘连。
                // 工具执行是阻塞的，且 Tool 内部会通过 ToolContext 中的 sink 实时推送事件。
                Flux<ServerSentEvent<String>> discardPreamble =
                        Flux.just(event("reset", new ChatStreamEvents.Reset()));

                /*
                  取数之前再判一次软超时：模型这一轮的耗时同样计入预算。
                  已经超时就不取数了——"停止取数 → 收口轮"是 REQ-BA-06 的明确行为，
                  而不是把这一批工具查完再说。
                  注意此分支**不置位 toolsExecuted**：一次工具都没执行时，正文里的口径行
                  仍按"编造"处理（与既有兜底语义一致）。
                */
                if (cost.softTimeoutExceeded(softTimeoutMs)) {
                    log.warn("模型第 {} 轮请求 {} 个工具时已超过软超时 {}ms，放弃取数转入收口轮",
                            depth + 1, toolCalls.size(), softTimeoutMs);
                    cost.markDurationCap();
                    return Flux.concat(discardPreamble,
                            finalAnswerRound(prompt, answer, timeBudgetInstruction(softTimeoutMs), cost));
                }

                // 置位"本轮执行过工具"：收尾的「口径行」兜底据此判定是否存在编造
                // （口径只能来自工具返回值，零工具 + 有口径行必然是编造）。
                toolsExecuted.set(true);
                budget.startRound();
                long toolStartNanos = nanoClock.getAsLong();
                return Flux.concat(discardPreamble, Flux.defer(() -> {
                    ToolExecutionResult result;
                    try {
                        result = toolCallingManager.executeToolCalls(prompt, aggregate(toolCalls));
                    } catch (ToolCallLimitExceededException ex) {
                        /*
                          框架自带硬上限（默认：单工具 40 次 / 整轮 150 次，触顶即抛）。
                          它同样是"取数触顶"，不该变成一个 error 事件丢给用户：按 REQ-BA-06 的
                          统一口径停止取数、进入收口轮。

                          收口轮**接着本轮工具执行之前的 prompt**（含前几轮已拿到的数据），
                          而不是用异常里的 partial 结果：partial 的 assistant 消息带的是本轮
                          **全部** tool_calls，而 tool 响应只覆盖已执行的那部分，直接喂给模型
                          可能出现"有 tool_call 没有 tool 响应"的非法消息序列。
                        */
                        log.warn("工具调用触发框架硬上限（tool={} limit={}），转入收口轮",
                                ex.getToolName(), ex.getLimit());
                        cost.addToolCostMs(cost.elapsedMsSince(toolStartNanos));
                        cost.markToolLimitCap();
                        return finalAnswerRound(prompt, answer, TOOL_LIMIT_INSTRUCTION, cost);
                    }
                    cost.addToolCostMs(cost.elapsedMsSince(toolStartNanos));
                    Prompt next = new Prompt(result.conversationHistory(), prompt.getOptions());
                    return runToolLoop(next, answer, depth + 1, toolsExecuted, lastPromptRef,
                            budget, cost, config);
                }));
            }));
        });
    }

    /**
     * 累加一轮模型调用的 token 用量（REQ-BA-11）。
     *
     * <p>同一轮内只取**最后一次**非空上报：OpenAI 兼容协议在
     * {@code stream_options.include_usage=true}（Spring AI 流式默认开启）下，
     * 只在整轮结束的那个分片里带用量；万一实现改成每片都带，取最后一片也仍然是该轮的汇总。
     * 跨轮求和才是"本次分析"的总成本（每轮都是一次独立的模型调用）。</p>
     */
    private static void accumulateUsage(List<ChatResponse> chunks, TurnCost cost) {
        Integer input = null;
        Integer output = null;
        for (ChatResponse chunk : chunks) {
            if (chunk == null || chunk.getMetadata() == null) {
                continue;
            }
            Usage usage = chunk.getMetadata().getUsage();
            if (usage == null) {
                continue;
            }
            if (usage.getPromptTokens() != null) {
                input = usage.getPromptTokens();
            }
            if (usage.getCompletionTokens() != null) {
                output = usage.getCompletionTokens();
            }
        }
        if (input != null) {
            cost.addInputTokens(input);
        }
        if (output != null) {
            cost.addOutputTokens(output);
        }
    }

    /**
     * 收口轮：**摘掉工具**再要一次回答，只能用已经查到的数据作答。
     *
     * <p><b>为什么不能像原先那样直接收场</b>（2026-09-29 22:25 现场复现，SSE 原文实测）：
     * 用户问「请分析 2026 年第二季度投标订单，和第一季度比较，并从区域、机构、险种三个维度
     * 找出主要变化」，模型把 4 轮**全部**用在查数据上（36 次只读工具调用，含逐区域、逐机构展开），
     * 第 5 轮被 {@link AiConfigCatalog#BUDGET_MAX_ROUNDS} 挡掉后 {@code Flux.empty()} 收场 →
     * {@code answer} 为空 → 前端只剩一个空气泡。用户看到的是"助手坏了"，
     * 而不是"这次的数据面不够、结论如下"。上限的本意是防死循环，不是"不回答"。</p>
     *
     * <p><b>为什么是"摘掉工具"而不是"再提示一句"</b>：提示只降低概率，模型仍可能再发起工具调用；
     * 而按既有口径，**只要那一轮有工具调用，正文（前言）就不计入最终回答**——
     * 等于什么都没发生。摘掉工具是硬保证：没有可调用的工具，模型只能输出正文。
     * 同时补一条 {@code instruction} 说明"为什么必须现在收口"（轮次用尽 / 软超时各有措辞），
     * 并要求它如实说明没查到的维度。</p>
     *
     * <p>本轮正文按"最终回答轮"处理：直接写入 {@code answer} 并流式下发，
     * 之后照常走 {@link #tailEvents} 的兜底校验与落库。</p>
     *
     * @param instruction 收口原因（{@link #finalRoundInstruction(int)} / {@link #timeBudgetInstruction(long)}
     *                    / {@link #TOOL_LIMIT_INSTRUCTION}）
     */
    private Flux<ServerSentEvent<String>> finalAnswerRound(Prompt prompt, StringBuilder answer,
                                                           String instruction, TurnCost cost) {
        List<Message> instructions = new ArrayList<>(prompt.getInstructions());
        instructions.add(new UserMessage(instruction));
        Prompt answerOnly = new Prompt(instructions, withoutTools(prompt.getOptions()));

        cost.modelRounds.incrementAndGet();
        List<ChatResponse> collected = new ArrayList<>();
        StringBuilder roundText = new StringBuilder();
        return chatModel.stream(answerOnly)
                .doOnNext(collected::add)
                .concatMap(chunk -> {
                    String text = textOf(chunk);
                    if (text == null || text.isEmpty()) {
                        return Flux.empty();
                    }
                    roundText.append(text);
                    return Flux.just(event("delta", new ChatStreamEvents.Delta(text)));
                })
                .concatWith(Flux.defer(() -> {
                    accumulateUsage(collected, cost);
                    answer.append(roundText);
                    return Flux.empty();
                }));
    }

    /**
     * 摘掉工具调用能力，保留模型 / 温度等其余选项。
     *
     * <p>不能从零构造 options（会丢模型自身的实现类型，见
     * {@link #buildToolCallingOptions} 的说明），必须在原 options 上 mutate。</p>
     */
    private static ChatOptions withoutTools(ChatOptions options) {
        if (!(options instanceof ToolCallingChatOptions toolOptions)) {
            return options;
        }
        return toolOptions.mutate().toolCallbacks(List.of()).build();
    }

    private static String textOf(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return null;
        }
        return response.getResult().getOutput().getText();
    }

    /**
     * 合并流式分片中的工具调用。
     *
     * <p>OpenAI 兼容协议的流式响应中，同一次工具调用的 name 出现在首个分片，
     * 参数可能被拆成多个增量分片，因此按 id 合并并拼接参数。
     * 若后一个分片携带的是「更完整的完整参数」而非增量，则直接采用它。</p>
     */
    private static List<AssistantMessage.ToolCall> mergeToolCalls(List<ChatResponse> chunks) {
        Map<String, AssistantMessage.ToolCall> byKey = new LinkedHashMap<>();
        for (ChatResponse response : chunks) {
            if (response == null || !response.hasToolCalls()) {
                continue;
            }
            for (AssistantMessage.ToolCall call : response.getResult().getOutput().getToolCalls()) {
                String key = StringUtils.hasText(call.id()) ? call.id() : call.name();
                byKey.merge(key, call, AiChatService::mergeToolCall);
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private static AssistantMessage.ToolCall mergeToolCall(AssistantMessage.ToolCall previous,
                                                           AssistantMessage.ToolCall current) {
        String merged = current.arguments();
        if (merged == null) {
            merged = previous.arguments();
        } else if (previous.arguments() != null && !merged.startsWith(previous.arguments())) {
            merged = previous.arguments() + merged;
        }
        String name = StringUtils.hasText(previous.name()) ? previous.name() : current.name();
        String type = StringUtils.hasText(previous.type()) ? previous.type() : current.type();
        return new AssistantMessage.ToolCall(previous.id(), type, name, merged);
    }

    /** 把合并后的工具调用重新包装成一次完整的模型响应，交给 ToolCallingManager 执行。 */
    private static ChatResponse aggregate(List<AssistantMessage.ToolCall> toolCalls) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(toolCalls)
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    // ------------------------------------------------------------------
    // 持久化与工具方法
    // ------------------------------------------------------------------

    /**
     * 保存助手消息（只执行一次），并把本轮 Tool Call 关联到该消息。
     *
     * @return 助手消息主键；内容为空时不落库，返回 null
     */
    private Long persistAssistant(AtomicBoolean persisted, Long conversationId, Long userId, String answer) {
        if (!persisted.compareAndSet(false, true)) {
            return null;
        }
        if (!StringUtils.hasText(answer)) {
            return null;
        }
        try {
            AiMessage saved = conversationService.appendMessage(conversationId, "ASSISTANT", answer);
            conversationService.bindToolCallsToMessage(conversationId, saved.getId());
            return saved.getId();
        } catch (RuntimeException ex) {
            log.error("保存助手消息失败 conversationId={} userId={}", conversationId, userId, ex);
            return null;
        }
    }

    /**
     * 载入最近的历史消息。
     *
     * <p>条数取自配置项 {@link AiConfigCatalog#BUDGET_HISTORY_LIMIT}（改造前 20）：
     * 调大增加 token 成本，调小可能让模型丢失上文。整轮使用同一份快照。</p>
     */
    private List<Message> loadHistory(Long conversationId, AiConfigSnapshot config) {
        List<AiMessage> recent = conversationService.recentMessages(
                conversationId, config.getInt(AiConfigCatalog.BUDGET_HISTORY_LIMIT));
        List<Message> messages = new ArrayList<>(recent.size());
        for (AiMessage m : recent) {
            Message converted = toSpringMessage(m);
            if (converted != null) {
                messages.add(converted);
            }
        }
        return messages;
    }

    private static Message toSpringMessage(AiMessage message) {
        if (message == null || !StringUtils.hasText(message.getContent())) {
            return null;
        }
        return switch (message.getRole()) {
            case "USER" -> new UserMessage(message.getContent());
            case "ASSISTANT" -> new AssistantMessage(message.getContent());
            // SYSTEM / TOOL 消息不进多轮上下文，避免污染对话
            default -> null;
        };
    }

    /**
     * 构造 ToolContext。
     *
     * <p>注意：Spring AI 的 {@code ToolContext} 不允许 value 为 null
     * （{@code Assert.noNullElements}），因此非 Web 线程（例如无 MDC 的集成测试）下
     * TraceId 为空时必须跳过该键。</p>
     *
     * <p><b>权限必须随上下文中传</b>（SYS-P-03）：工具线程上没有 SecurityContext、
     * 也没有 {@code CurrentUser} ThreadLocal，这是权限到达工具的唯一通路。
     * 同时 {@code AiToolRegistry} 也用这份快照裁剪注册集（SYS-P-12a）。</p>
     */
    private static Map<String, Object> buildToolContext(Long conversationId, Long userId, String userText,
                                                        CurrentUser.Principal principal,
                                                        ToolCallEventSink eventSink,
                                                        Sinks.Many<ChatStreamEvents.Proposal> proposalSink,
                                                        TurnFacts turnFacts,
                                                        KnowledgeClaimGuard.TurnKnowledge knowledgeTurn) {
        Map<String, Object> context = new HashMap<>();
        // 助手侧调用一律记 CHAT：落进 ai_tool_call.source 与 ai.tool.calls{source}（AC-MCP-05）
        context.put(AiToolContextKeys.CALL_SOURCE, AiTurnMetric.SOURCE_CHAT);
        putIfNotNull(context, AiToolContextKeys.CONVERSATION_ID, conversationId);
        putIfNotNull(context, AiToolContextKeys.USER_ID, userId);
        putIfNotNull(context, AiToolContextKeys.TRACE_ID, TraceContext.currentTraceId());
        putIfNotNull(context, AiToolContextKeys.USER_TEXT, userText);
        context.put(AiToolContextKeys.EVENT_SINK, eventSink);
        context.put(AiToolContextKeys.PROPOSAL_SINK, proposalSink);
        // 本轮工具事实收集器：与收尾的服务端口径/编号校验共享同一实例
        context.put(AiToolContextKeys.TURN_FACTS, turnFacts);
        // 本轮知识检索事实收集器：知识工具写入，收尾生成「知识来源：」行（REQ-RAG-04）
        context.put(KnowledgeClaimGuard.CONTEXT_KEY, knowledgeTurn);
        if (principal != null) {
            putIfNotNull(context, AiToolContextKeys.USERNAME, principal.username());
            putIfNotNull(context, AiToolContextKeys.REAL_NAME, principal.realName());
            // 机构不再是人/部门的归属属性（机构服务于订单），ToolContext 不再携带 orgId
            context.put(AiToolContextKeys.PERMISSIONS, principal.permissions());
            context.put(AiToolContextKeys.ROLES, principal.roles());
        } else {
            // 无上下文（例如集成测试直接调用 stream）：给出空列表而不是缺键，
            // 使 AiToolRegistry 走 fail-closed（只注册无需权限的工具）
            context.put(AiToolContextKeys.PERMISSIONS, List.of());
            context.put(AiToolContextKeys.ROLES, List.of());
        }
        return context;
    }

    /**
     * 取请求线程上的登录主体。
     *
     * <p>注意 {@code stream()} 在 Web 线程上被调用（Controller 内），
     * 此时 {@code CurrentUser} 仍然有效，因此在构造 ToolContext 的这一刻读取它是可靠的；
     * 之后工具循环切到 Reactor 线程，ThreadLocal 才会失效。</p>
     */
    private static CurrentUser.Principal principalContext() {
        return CurrentUser.get();
    }

    /**
     * 当前用户是否可以接收工具调用明细（{@code ai:debug:view}）。
     *
     * <p><b>为什么由服务端判定而不是前端隐藏</b>：{@code tool_call} 事件带着完整的
     * 入参与返回 JSON，前端 {@code v-if} 只能让界面不显示，数据仍在响应里，浏览器
     * 开发者工具一开就能看到。要让普通用户**真正拿不到**，只能在服务端不下发。</p>
     *
     * <p><b>为什么缺失主体时判为不可见（fail-closed）</b>：拿不到权限快照时应当
     * 收敛到"不给调试信息"，而不是默认放开。工具执行与 {@code ai_tool_call} 落库
     * **完全不受本判定影响**——隐藏的只是推给浏览器的过程事件，审计照旧。</p>
     */
    private static boolean canSeeToolDetails() {
        CurrentUser.Principal principal = principalContext();
        return principal != null && principal.hasPermission(Permissions.AI_DEBUG_VIEW);
    }

    private static void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    /** 把预解析出的明确日期追加给模型，降低其自行换算相对时间的出错概率。 */
    private static String buildUserText(String userText, Optional<TimeRange> parsedTime) {
        if (parsedTime.isEmpty()) {
            return userText;
        }
        TimeRange range = parsedTime.get();
        return userText + "\n\n[系统预解析] 本轮时间范围：" + range.startDate() + " ~ " + range.endDate()
                + "（" + range.description() + "）。调用工具时请直接使用这两个日期。";
    }

    private ServerSentEvent<String> event(String name, Object payload) {
        return ServerSentEvent.<String>builder()
                .event(name)
                .data(toJson(payload))
                .build();
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException ex) {
            log.warn("SSE 载荷序列化失败: {}", payload, ex);
            return "{}";
        }
    }

    /** 把底层异常翻译成用户能看懂、且不泄漏堆栈的提示。 */
    private static String safeMessage(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String text = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        String lower = text == null ? "" : text.toLowerCase();
        if (lower.contains("401") || lower.contains("unauthorized") || lower.contains("api key")
                || lower.contains("incorrect api key")) {
            return "AI 模型调用失败：API Key 未配置或无效（请设置环境变量 DEEPSEEK_API_KEY 后重启服务）";
        }
        if (lower.contains("connection refused") || lower.contains("connect timed out")
                || lower.contains("unknownhost") || lower.contains("timeout")) {
            return "AI 模型调用失败：无法连接模型服务，请检查网络与 spring.ai.openai.base-url 配置";
        }
        if (lower.contains("429") || lower.contains("rate limit")) {
            return "AI 模型调用失败：请求过于频繁或额度不足，请稍后重试";
        }
        return "AI 处理失败：" + Objects.toString(text, "未知错误");
    }

    // ------------------------------------------------------------------
    // 单轮预算 / 成本（REQ-BA-06 / REQ-BA-11）
    // ------------------------------------------------------------------

    /**
     * 单轮工具调用预算（REQ-BA-06）。
     *
     * <p>每轮**取数之前** {@link #startRound()} 归零；同一轮的并发工具调用用
     * {@link AtomicInteger} 抢名额，**恰好**只有前 {@code limit} 个真正执行。
     * 不执行的那几次仍然会拿到一条可读的工具结果（
     * {@link AiChatService#overBudgetToolResult(String)}），因此模型能继续而不会报错。</p>
     */
    static final class ToolCallBudget {

        private final int limit;
        private final AtomicInteger requestedThisRound = new AtomicInteger();
        private final AtomicInteger skippedTotal = new AtomicInteger();
        private final AtomicInteger executedTotal = new AtomicInteger();

        ToolCallBudget(int limit) {
            this.limit = limit;
        }

        void startRound() {
            requestedThisRound.set(0);
        }

        /** 本轮允许执行的工具调用数上限（来自配置快照）。 */
        int limit() {
            return limit;
        }

        /** @return true 表示本次工具调用获准执行；false 表示超限、应回灌可读说明 */
        boolean tryAcquire() {
            if (requestedThisRound.incrementAndGet() <= limit) {
                executedTotal.incrementAndGet();
                return true;
            }
            int skipped = skippedTotal.incrementAndGet();
            if (skipped == 1) {
                log.warn("单轮工具调用超过上限 {}，超出部分不执行，改为回灌可读说明", limit);
            }
            return false;
        }

        /** 本轮实际执行过的工具调用数合计（成本日志的「工具调用数」）。 */
        int executedTotal() {
            return executedTotal.get();
        }
    }

    /**
     * 给单个工具调用加"单轮预算"的装饰器。
     *
     * <p>为什么做成装饰器：预算语义是"每次调用"，与具体工具无关；做在 ToolCallback 层，
     * Spring AI 的 {@code ToolCallingManager} 会为**每一次**调用（含被跳过的）生成
     * 一条合法的 tool 响应，消息序列不会出现"有 tool_call 没有 tool 响应"的非法形态。</p>
     */
    static final class BudgetedToolCallback implements ToolCallback {

        private final ToolCallback delegate;
        private final ToolCallBudget budget;

        BudgetedToolCallback(ToolCallback delegate, ToolCallBudget budget) {
            this.delegate = delegate;
            this.budget = budget;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public String call(String toolInput) {
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            if (!budget.tryAcquire()) {
                return overBudgetToolResult(delegate.getToolDefinition().name(), budget.limit());
            }
            return delegate.call(toolInput, toolContext);
        }
    }

    /**
     * 收尾的统一入口：**结构化成本日志 + 单轮指标落库 + Micrometer 指标**三件事一起做。
     *
     * <p><b>为什么合成一个方法</b>：三者口径必须一一对应（REQ-MCP-09 要求"与 AI_TURN_COST
     * 日志同源、字段一一对应"），分三处写迟早漂移；而 {@code TurnCost.log} 的 CAS 保证了
     * 失败路径与正常路径只会真正收尾一次，所以这里用它的返回值当唯一闸门。</p>
     *
     * <p><b>失败语义</b>：Micrometer 先记（落库被开关关掉或写失败都不该让计数断掉）；
     * 落库由 {@link TurnMetricService#record} 内部吞异常并告警——**绝不影响回答**
     * （与审计"写失败要回滚业务"相反）。</p>
     *
     * @param messageId 助手消息 id；失败路径可能为 null
     * @param outcome   SUCCESS / ERROR / CAPPED
     */
    private void finishTurnCost(Long messageId, Long conversationId, Long userId,
                                AiConfigSnapshot config, TurnCost cost, ToolCallBudget budget,
                                String outcome) {
        if (!cost.log(conversationId, budget.executedTotal(), outcome)) {
            return;
        }

        String model = resolveModelName(config);
        if (metrics != null) {
            metrics.chatRequest(outcome, model);
            metrics.chatDuration(outcome, cost.elapsedMs());
            metrics.chatRounds(cost.rounds(), cost.capped());
            metrics.tokens(cost.inputTokens(), cost.outputTokens(), model);
        }

        if (turnMetricService == null || !turnMetricPersistEnabled) {
            return;
        }
        AiTurnMetric row = new AiTurnMetric();
        row.setConversationId(conversationId);
        row.setMessageId(messageId);
        row.setUserId(userId);
        row.setModel(model);
        // 提示词版本来自会话记录（第四阶段 prompt_version，INT）→ 本表用 VARCHAR(32) 兼容
        // "版本号"与"降级时的文件名"两种形态
        Integer promptVersion = cost.promptVersion();
        row.setPromptVersion(promptVersion == null ? null : String.valueOf(promptVersion));
        row.setRounds(cost.rounds());
        row.setToolCalls(budget.executedTotal());
        row.setToolCostMs(cost.toolCostMs());
        row.setTotalCostMs(cost.elapsedMs());
        row.setInputTokens(cost.inputTokens());
        row.setOutputTokens(cost.outputTokens());
        row.setCapped(cost.capped() ? 1 : 0);
        row.setCapReason(capReasonOf(cost));
        row.setSource(AiTurnMetric.SOURCE_CHAT);
        row.setOutcome(outcome);
        row.setTraceId(cost.traceId());
        turnMetricService.record(row);
    }

    /** 触顶原因映射：{@link TurnCost} 的内部值 → {@code ai_turn_metric.cap_reason} 的登记枚举。 */
    private static String capReasonOf(TurnCost cost) {
        return switch (cost.capReason()) {
            case TurnCost.CAP_DURATION -> AiTurnMetric.CAP_SOFT_TIMEOUT;
            case TurnCost.CAP_ROUNDS -> AiTurnMetric.CAP_MAX_ROUNDS;
            case TurnCost.CAP_TOOL_LIMIT -> AiTurnMetric.CAP_FRAMEWORK_LIMIT;
            default -> null;
        };
    }

    /**
     * 本轮成本与耗时的**可变累加器**（REQ-BA-11）。
     *
     * <p>只属于一次请求：{@code AiChatService} 是单例 Bean，任何计数都不能挂在字段上，
     * 否则两个并发会话会互相污染。收尾（{@link #finishTurn}）与出错路径各调一次
     * {@link #log(Long, int)}，用 CAS 保证**一轮只写一条**结构化日志。</p>
     */
    static final class TurnCost {

        static final String CAP_NONE = "none";

        /** 轮次用尽（{@link AiConfigCatalog#BUDGET_MAX_ROUNDS}）。 */
        static final String CAP_ROUNDS = "rounds";

        /** 软超时（{@link AiConfigCatalog#BUDGET_SOFT_TIMEOUT_MS}）。 */
        static final String CAP_DURATION = "duration";

        /** 框架自带硬上限（单工具 40 次 / 整轮 150 次，触顶即抛）。 */
        static final String CAP_TOOL_LIMIT = "tool_limit";

        private final long startNanos;
        private final LongSupplier nanoClock;
        private final AtomicInteger modelRounds = new AtomicInteger();
        private final AtomicLong toolCostMs = new AtomicLong();
        private final AtomicInteger inputTokens = new AtomicInteger();
        private final AtomicInteger outputTokens = new AtomicInteger();
        private final AtomicReference<String> capReason = new AtomicReference<>(CAP_NONE);
        private final AtomicBoolean logged = new AtomicBoolean();

        /**
         * 本轮的 traceId 与提示词版本：由 {@code stream()} 在 **Web 线程**绑定
         * （见 {@link #bindTurn}），收尾时在 Reactor 线程上直接读——
         * 这是"不依赖 MDC 自然传播"的落地方式（REQ-MCP-10）。
         */
        private final AtomicReference<String> traceId = new AtomicReference<>();
        private final AtomicReference<Integer> promptVersion = new AtomicReference<>();

        TurnCost(LongSupplier nanoClock) {
            this.nanoClock = nanoClock;
            this.startNanos = nanoClock.getAsLong();
        }

        /** 绑定本轮上下文（traceId 可能为 null：非 Web 线程/无 MDC 的集成测试）。 */
        void bindTurn(String traceId, Integer promptVersion) {
            this.traceId.set(traceId);
            this.promptVersion.set(promptVersion);
        }

        String traceId() {
            return traceId.get();
        }

        Integer promptVersion() {
            return promptVersion.get();
        }

        int rounds() {
            return modelRounds.get();
        }

        int inputTokens() {
            return inputTokens.get();
        }

        int outputTokens() {
            return outputTokens.get();
        }

        /** 是否触顶（capReason 非 none）。 */
        boolean capped() {
            return !CAP_NONE.equals(capReason.get());
        }

        void addToolCostMs(long millis) {
            toolCostMs.addAndGet(millis);
        }

        void addInputTokens(int tokens) {
            inputTokens.addAndGet(tokens);
        }

        void addOutputTokens(int tokens) {
            outputTokens.addAndGet(tokens);
        }

        long elapsedMs() {
            return elapsedMsSince(startNanos);
        }

        long elapsedMsSince(long fromNanos) {
            return (nanoClock.getAsLong() - fromNanos) / 1_000_000L;
        }

        /** 软超时是否已触顶（含"刚好等于"）；上限来自本轮配置快照。 */
        boolean softTimeoutExceeded(long softTimeoutMs) {
            return elapsedMs() >= softTimeoutMs;
        }

        /** 记录触顶原因；先到先记（轮次与时长同时触顶时，记先发生的那个）。 */
        void markRoundsCap() {
            capReason.compareAndSet(CAP_NONE, CAP_ROUNDS);
        }

        void markDurationCap() {
            capReason.compareAndSet(CAP_NONE, CAP_DURATION);
        }

        void markToolLimitCap() {
            capReason.compareAndSet(CAP_NONE, CAP_TOOL_LIMIT);
        }

        /** 触顶原因：{@code none} / {@code rounds} / {@code duration} / {@code tool_limit}。 */
        String capReason() {
            return capReason.get();
        }

        long toolCostMs() {
            return toolCostMs.get();
        }

        /**
         * 写一条结构化成本日志（固定字段）：
         * {@code traceId / conversationId / rounds 轮次 / toolCalls 工具调用数 / toolCostMs 工具耗时合计 /
         * inputTokens+outputTokens 输入输出 token / totalCostMs 本次总耗时 / capped 是否触顶 / source / outcome}。
         *
         * <p><b>为什么带 traceId 且要做成返回值</b>：这条日志跑在 Reactor 线程，字段里没有 traceId 时
         * 恰好串不起"一次异常问答的成本"（AC-MCP-10）；返回值用于把"一条日志"与"一行指标"绑成同一次收尾，
         * 避免失败路径重复落库（{@code logged} 的 CAS 是唯一闸门）。</p>
         *
         * @param toolCalls 本轮实际执行的工具调用数（来自 {@link ToolCallBudget#executedTotal()}）
         * @param outcome   SUCCESS / ERROR / CAPPED（与 {@code ai.chat.requests} 的 outcome 标签同值域）
         * @return true 表示本次调用真的写了日志（调用方据此决定是否落指标行）
         */
        boolean log(Long conversationId, int toolCalls, String outcome) {
            if (!logged.compareAndSet(false, true)) {
                return false;
            }
            log.info("{} traceId={} conversationId={} rounds={} toolCalls={} toolCostMs={} inputTokens={} "
                            + "outputTokens={} totalCostMs={} capped={} capReason={} source={} outcome={}",
                    COST_LOG_TAG, traceId.get(), conversationId, modelRounds.get(), toolCalls, toolCostMs.get(),
                    inputTokens.get(), outputTokens.get(), elapsedMs(), capped(), capReason.get(),
                    AiTurnMetric.SOURCE_CHAT, outcome);
            return true;
        }
    }
}
