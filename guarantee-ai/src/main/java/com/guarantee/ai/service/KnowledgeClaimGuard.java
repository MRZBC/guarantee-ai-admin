package com.guarantee.ai.service;

import com.guarantee.ai.knowledge.KnowledgeHit;
import com.guarantee.ai.knowledge.KnowledgeSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 「知识来源」行的服务端唯一出口（REQ-RAG-04，与 {@link DataSourceClaimGuard} 同构）。
 *
 * <p><b>与口径行同一套道理</b>：口径行管"数字从哪来"，知识来源行管"这句话的依据是哪一条知识"。
 * 两者都只能由服务端产出——模型自写的行一律剥离，因为"照抄"与"编造"在文本上无法区分。
 * 真机已有"编造提案编号"的先例，溯源行是同一类风险（RK-RAG-07）。</p>
 *
 * <p><b>三条规则</b>：</p>
 * <ol>
 *   <li>本轮**真的检索到条目**才追加：{@code 知识来源：KB-x《标题》vN；KB-y《标题》vM}，
 *       片段逐字取自检索返回值（编号 + 标题 + 版本）；</li>
 *   <li>模型自写的「知识来源：」行无条件剥离（行首判定，行中出现不算——避免误伤
 *       "这条知识的来源是……"这类正常表述）；</li>
 *   <li>本轮一次检索都没调用，却出现「知识来源：」行 → 剥离并追加一条系统提示
 *       （零检索却有来源行，必然是编造）。</li>
 * </ol>
 *
 * <p><b>检索到 0 条时不追加任何来源行</b>（AC-RAG-03：未收录只能说"未收录"，
 * 不得给出任何来源行）。此时工具返回值里的 {@code meta.dataSource} 会告诉模型"知识库：未收录"，
 * 由模型如实转述。</p>
 *
 * <p><b>为什么是工具类而不是 Spring Bean</b>：本类无状态（收集器随每次请求新建），
 * 做成 Bean 就得给 {@code AiChatService} 的构造器再加一个参数——那是一个多阶段共用的热点文件，
 * 为一个无状态规则类扩大它的签名并不划算；{@code DataSourceClaimGuard} 是历史形态，
 * 行为与本类一致（{@link #stripSourceLines} / {@link #footer} 都是静态方法）。</p>
 */
public final class KnowledgeClaimGuard {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeClaimGuard.class);

    /**
     * 本轮知识检索事实在 {@code ToolContext} 里的键。
     *
     * <p>刻意定义在本类而不是 {@code AiToolContextKeys}（那是跨模块共享键的清单，
     * 属于其他任务的写范围）：新增一个只被知识工具与知识守卫使用的键，
     * 放在本类里同样只有一个定义处。</p>
     */
    public static final String CONTEXT_KEY = "aiKnowledgeTurn";

    /** 来源行的前缀（全角冒号，与服务端产出格式一致）。 */
    public static final String PREFIX = "知识来源：";

    /** 半角冒号变体：模型与用户都可能写。 */
    private static final String PREFIX_HALF_WIDTH = "知识来源:";

    /** 整行匹配（行首 + 前后空白 + 行尾换行），与 {@code DataSourceClaimGuard} 同款。 */
    private static final Pattern SOURCE_LINE =
            Pattern.compile("^[ \\t]*知识来源[：:][^\\r\\n]*(?:\\R|$)", Pattern.MULTILINE);

    /**
     * 零检索却写出来源行时的纠正文案。
     *
     * <p>必须点明"不是系统回显"：用户之所以会信那行字，正是因为它长得像系统输出。</p>
     */
    public static final String CORRECTION =
            "（系统提示：本轮没有调用知识检索工具，因此没有任何知识来源；正文里那行「知识来源：」"
                    + "不是系统回显，已由系统移除。请勿据此认为这段话有知识依据。"
                    + "如需引用知识，请先调用知识检索工具。）";

    private KnowledgeClaimGuard() {
    }

    /**
     * 本轮知识检索事实的收集器（与 {@code TurnFacts} 同构，但只服务知识层）。
     *
     * <p>由知识工具在拿到检索结果后 {@link #record}，收尾时服务端据此生成来源行、
     * 并把知识类 dataSource 从"口径行"里精确剔除（口径 = 数据来源，不是知识来源）。
     * 工具可能并发执行，因此所有读写都加锁。</p>
     */
    public static final class TurnKnowledge {

        private final List<String> sourceFragments = new ArrayList<>();
        private final List<String> dataSources = new ArrayList<>();
        private boolean retrieved;

        /** 记录一次真实检索（无论是否命中）。 */
        public synchronized void record(KnowledgeSearchResult result) {
            if (result == null) {
                return;
            }
            retrieved = true;
            String dataSource = result.dataSource();
            if (dataSource != null && !dataSource.isBlank() && !dataSources.contains(dataSource)) {
                dataSources.add(dataSource);
            }
            for (KnowledgeHit hit : result.items()) {
                String fragment = hit.sourceFragment();
                if (fragment != null && !fragment.isBlank() && !sourceFragments.contains(fragment)) {
                    sourceFragments.add(fragment);
                }
            }
        }

        /** 本轮是否调用过知识检索（无论命中与否）。 */
        public synchronized boolean retrieved() {
            return retrieved;
        }

        /** 命中条目的来源片段（{@code KB-x《标题》vN}），按命中顺序去重。 */
        public synchronized List<String> sourceFragments() {
            return List.copyOf(sourceFragments);
        }

        /** 知识工具返回的 dataSource 原文（用于从口径页脚里精确剔除）。 */
        public synchronized List<String> dataSources() {
            return List.copyOf(dataSources);
        }
    }

    /** 从 ToolContext 取本轮收集器；未挂载（例如单测直接调用工具）时返回 {@code null}。 */
    public static TurnKnowledge turnKnowledge(ToolContext context) {
        if (context == null || context.getContext() == null) {
            return null;
        }
        Object value = context.getContext().get(CONTEXT_KEY);
        return value instanceof TurnKnowledge knowledge ? knowledge : null;
    }

    /**
     * 判断是否需要追加纠正提示（"零检索却有来源行"）。
     *
     * @param answer             本轮模型产出的正文
     * @param anyKnowledgeCall   本轮是否调用过知识检索工具
     */
    public static Optional<String> correctionFor(String answer, boolean anyKnowledgeCall) {
        if (anyKnowledgeCall) {
            // 检索过就放过：来源行写错属于"引用错误"，不在本兜底（零检索 = 必然编造）的职责内
            return Optional.empty();
        }
        if (!claimsKnowledgeSource(answer)) {
            return Optional.empty();
        }
        log.warn("本轮零知识检索却出现「知识来源」行，追加纠正提示并剥离");
        return Optional.of(CORRECTION);
    }

    /** 正文是否包含形如 {@code 知识来源：…} 的独立行（行首判定，允许缩进）。 */
    static boolean claimsKnowledgeSource(String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        for (String line : answer.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith(PREFIX) || trimmed.startsWith(PREFIX_HALF_WIDTH)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 剥离模型自写的知识来源行（服务端是来源行的唯一出口）。
     *
     * <p>正文未被改动时**原样返回**，便于调用方据此判断要不要 reset 重发。</p>
     */
    public static String stripSourceLines(String answer) {
        if (answer == null || answer.isBlank()) {
            return answer == null ? "" : answer;
        }
        String stripped = SOURCE_LINE.matcher(answer).replaceAll("");
        if (stripped.equals(answer)) {
            return answer;
        }
        return stripped.replaceAll("[ \\t\\r\\n]+$", "");
    }

    /**
     * 从本轮全部工具 dataSource 中剔掉知识检索工具返回的那些（口径行的净化）。
     *
     * <p>「口径：」行回答"数字从哪来"，知识回答"这句话的依据是哪一条"——两类来源必须分开展示
     * （REQ-RAG-04-5）。这里按收集器记录的**原值精确剔除**，不做字符串包含匹配：
     * 用"含『知识库』字样"去猜，迟早会误伤某个业务工具的口径文本（例如未来某个
     * "知识库数据量统计"工具）。</p>
     *
     * @param dataSources   {@code TurnFacts.dataSources()} 的原文
     * @param knowledgeTurn 本轮知识收集器（可为 null）
     */
    public static List<String> excludingKnowledgeDataSources(List<String> dataSources,
                                                            TurnKnowledge knowledgeTurn) {
        if (dataSources == null || dataSources.isEmpty()) {
            return List.of();
        }
        List<String> knowledgeDataSources = knowledgeTurn == null ? List.of() : knowledgeTurn.dataSources();
        if (knowledgeDataSources.isEmpty()) {
            return List.copyOf(dataSources);
        }
        return dataSources.stream()
                .filter(dataSource -> !knowledgeDataSources.contains(dataSource))
                .toList();
    }

    /**
     * 服务端知识来源页脚。
     *
     * @param sourceFragments 本轮真实命中的来源片段（调用方负责去重）
     * @return 需要追加的页脚（含前置空行）；没有命中返回空串（未收录不追加来源行）
     */
    public static String footer(List<String> sourceFragments) {
        if (sourceFragments == null || sourceFragments.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>(sourceFragments.size());
        for (String fragment : sourceFragments) {
            if (fragment != null && !fragment.isBlank()) {
                lines.add(fragment);
            }
        }
        return lines.isEmpty() ? "" : "\n\n" + PREFIX + String.join("；", lines);
    }
}
