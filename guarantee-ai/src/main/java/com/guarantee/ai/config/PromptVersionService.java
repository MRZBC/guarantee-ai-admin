package com.guarantee.ai.config;

import com.guarantee.ai.config.mapper.AiPromptVersionMapper;
import com.guarantee.ai.service.OperationAuditService;
import com.guarantee.common.exception.BizException;
import com.guarantee.system.service.WebAuditor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 提示词版本机（REQ-CFG-02 / REQ-CFG-11 / docs REQ §5.1.2、§5.1.11）。
 *
 * <p><b>状态机</b>：{@code DRAFT → PUBLISHED → ARCHIVED}，同一时刻**只有一个 PUBLISHED**
 * （发布时先把其它 PUBLISHED 归档，再发布目标版本，两步同一事务）。</p>
 *
 * <p><b>已发布版本只读</b>：内容更新语句只命中 {@code status='DRAFT'}（见
 * {@link AiPromptVersionMapper#updateDraft}），由 SQL 而不是调用方的自觉来保证。</p>
 *
 * <p><b>保护标记</b>：红线/服务端产出类条款在正文里以固定小标题存在，发布前**服务端**逐条校验；
 * 缺失即拒绝发布并列出缺了哪一条——只靠前端警告挡不住"直接 POST 接口"。</p>
 *
 * <p><b>发布门禁（REQ-CFG-11）</b>：发布前必须跑确定性评测集（Stub 模型、无需 Key）。
 * 判定只有三种，且**没有"默认放行"**：</p>
 * <ul>
 *   <li>{@code PASSED}：命令退出码 0 → 允许发布；</li>
 *   <li>{@code FAILED}：命令退出码非 0 → 拒绝发布；</li>
 *   <li>{@code NOT_RUN}：脚本不存在/未实现 {@code --suite} 参数/执行异常/超时 → **拒绝发布**，
 *       页面明确显示"未跑"。</li>
 * </ul>
 * 回滚**不过门禁**：它是"回到一个曾经通过门禁的版本"的应急路径，此时再要求评测可用会挡住止损。
 *
 * <p><b>事务边界（e2e 实测倒逼出来的）</b>：门禁是分钟级的外部命令，必须在事务**外**评估；
 * 只有"归档旧版 → 发布 → 投影 → 审计"进短事务。否则一个发布请求会持有一个分钟级的
 * 数据库连接与行锁（真机实测：并发构建下发布请求 >180s 未返回）。</p>
 *
 * <p><b>后续优化候选（本期不做）</b>：把门禁结果按 {@code versionNo + contentHash + TTL}
 * 缓存，发布时要求"该内容在 TTL 内有过 PASSED"——仍是"没有新鲜通过就不放行"，
 * 但发布请求可以立刻返回。它引入新的状态与审计口径，收益有限，登记为候选而非本期交付。</p>
 */
@Service
public class PromptVersionService {

    private static final Logger log = LoggerFactory.getLogger(PromptVersionService.class);

    /** 确定性评测集门禁命令（REQ-CFG-11）。可用属性 {@code guarantee.ai.prompt.gate-command} 覆盖。 */
    public static final String GATE_COMMAND_DEFAULT =
            "node scripts/ai-golden-questions.mjs --suite=deterministic";

    /** 门禁命令超时（毫秒）：超过即判 NOT_RUN，绝不让发布请求无限挂住。 */
    public static final long GATE_TIMEOUT_MS = 10 * 60 * 1000L;

    /**
     * 受保护段落标记（发布内容必须逐条包含）。
     *
     * <p>选的是正文里的**小标题**，不是某句话：措辞可以改，但"这一节必须存在"不能改。
     * 逐条对应红线：数据必须来自 Tool、事实与推测分开、写操作必须走确认、
     * 敏感信息不落明文、权限与可见性不可越界。</p>
     */
    public static final List<String> PROTECTED_MARKERS = List.of(
            "# 铁律：数据必须来自 Tool",
            "# 事实与推测必须分开",
            "# 写操作铁律（最重要）",
            "# 敏感信息规则",
            "# 权限与可见性");

    /** 审计的 target_name 前缀（提示词版本号）。 */
    public static final String TARGET_NAME_PREFIX = "prompt.v";

    /** 真机集状态取值（AC-CFG-10 子句②）。 */
    public static final String LIVE_NOT_RUN = "NOT_RUN";
    public static final String LIVE_PASSED = "PASSED";
    public static final String LIVE_FAILED = "FAILED";

    private final AiPromptVersionMapper mapper;
    private final AiConfigService configService;
    private final WebAuditor webAuditor;
    private final PromptGate gate;
    /**
     * 真机集（{@code --suite=live}）报告的读取器：只读文件系统，**不执行任何评测**。
     *
     * <p>之所以要它：AC-CFG-10 子句②要求"真机集缺失时页面明确标注未跑"。
     * 真机集需要真实模型（{@code DEEPSEEK_API_KEY}），本机没有 → 页面必须显示"未跑"，
     * 而**不能**因为没有 Key 就假装通过、也不能因为读不到报告而报错。</p>
     */
    private final ObjectMapper objectMapper;
    private final Environment environment;

    /**
     * 最近一次门禁结果（进程内缓存）。
     *
     * <p><b>为什么需要它</b>：门禁命令要跑一整套确定性黄金问题集（分钟级）。如果"读版本历史"
     * 这种列表接口也顺手跑一次门禁，页面每次打开都会卡住并超时（CDP 走查实测就是这个表现）。
     * 因此约定：</p>
     * <ul>
     *   <li>{@link #lastGateResult()} 只返回**最近一次**结果（可为 null = 尚未检查），不执行命令；</li>
     *   <li>{@link #evaluateGate()} 显式执行（页面「刷新门禁」按钮）；</li>
     *   <li>发布时**必跑**一次——发布是权威判定点，不能拿缓存放行。</li>
     * </ul>
     */
    private volatile GateResult lastGateResult;

    /**
     * 短事务边界：只包住"归档旧发布版 + 发布目标版 + 配置投影 + 审计"。
     *
     * <p>用 {@link TransactionTemplate} 而不是在 {@code publish} 上加 {@code @Transactional}：
     * 门禁（分钟级）必须在事务**外**先跑完，再用这个模板开一个毫秒级事务完成切换。
     * 这也是同一个类里"自我调用不会走代理"的正确解法。</p>
     */
    private final TransactionTemplate transactionTemplate;

    /** 生产构造器：门禁用命令行实现。 */
    @Autowired
    public PromptVersionService(AiPromptVersionMapper mapper,
                                AiConfigService configService,
                                WebAuditor webAuditor,
                                Environment environment,
                                ObjectMapper objectMapper,
                                PlatformTransactionManager transactionManager) {
        this(mapper, configService, webAuditor, new CommandPromptGate(
                environment.getProperty("guarantee.ai.prompt.gate-command", GATE_COMMAND_DEFAULT)),
                environment, objectMapper, transactionManager);
    }

    /** 测试构造器：注入可打桩的门禁，避免单测真的去起 Node。 */
    PromptVersionService(AiPromptVersionMapper mapper,
                         AiConfigService configService,
                         WebAuditor webAuditor,
                         PromptGate gate,
                         Environment environment,
                         ObjectMapper objectMapper,
                         PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.configService = configService;
        this.webAuditor = webAuditor;
        this.gate = gate;
        this.environment = environment;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ==================================================================
    // 读取（运行期）
    // ==================================================================

    /**
     * 当前生效的提示词正文；**没有发布版时返回 null**（调用方回落到 classpath 内置提示词）。
     *
     * <p>读库失败同样返回 null 并告警：冷启动/DB 抖动时助手必须还能用内置提示词工作
     * （REQ-CFG-02 的"保留 classpath 兜底"）。</p>
     */
    public String currentContent() {
        try {
            AiPromptVersion published = mapper.selectPublished();
            return published == null ? null : published.getContent();
        } catch (RuntimeException ex) {
            log.error("读取提示词发布版失败，将回落到 classpath 内置提示词（助手仍可用）：{}", ex.getMessage());
            return null;
        }
    }

    /** 当前生效版本号；没有发布版时返回 0。 */
    public int activeVersionNo() {
        AiPromptVersion published = mapper.selectPublished();
        return published == null || published.getVersionNo() == null ? 0 : published.getVersionNo();
    }

    public AiPromptVersion published() {
        return mapper.selectPublished();
    }

    public AiPromptVersion draft() {
        return mapper.selectLatestDraft();
    }

    /** 版本历史（按版本号倒序）。 */
    public List<AiPromptVersion> history() {
        return mapper.selectAll();
    }

    public AiPromptVersion requireVersion(int versionNo) {
        AiPromptVersion version = mapper.selectByVersionNo(versionNo);
        if (version == null) {
            throw BizException.notFound("提示词版本不存在：v" + versionNo);
        }
        return version;
    }

    /** 缺失的保护标记（为空表示可以发布）。 */
    public static List<String> missingProtectedMarkers(String content) {
        if (content == null || content.isBlank()) {
            return List.copyOf(PROTECTED_MARKERS);
        }
        return PROTECTED_MARKERS.stream().filter(marker -> !content.contains(marker)).toList();
    }

    /**
     * 显式执行门禁（页面按钮 / 发布路径）。
     *
     * <p>结果记入 {@link #lastGateResult()}，供"版本历史"这类列表接口**免费**读取，
     * 避免每次打开页面都重跑一遍分钟级评测。</p>
     */
    public GateResult evaluateGate() {
        GateResult result = gate.evaluate();
        this.lastGateResult = result;
        return result;
    }

    /** 最近一次门禁结果；从未检查过时返回 null（页面显示"未检查"，且不允许发布）。 */
    public GateResult lastGateResult() {
        return lastGateResult;
    }

    // ==================================================================
    // 真机集（--suite=live）状态：只读报告，绝不执行、绝不伪造（AC-CFG-10 子句②）
    // ==================================================================

    /**
     * 真机集状态：读最近一份 {@code reports/eval-live-*.json}，**不执行任何评测**。
     *
     * <p>为什么是"读报告"而不是"跑一次"：真机集需要真实模型（{@code DEEPSEEK_API_KEY}）
     * 与真实后端，一次跑几分钟且要花钱；它在本阶段只是**标注**维度，不是发布门禁
     * （发布门禁仍是确定性集）。</p>
     *
     * <p><b>绝不伪造</b>：报告不存在、未跑、解析失败、缺 Key —— 一律 {@code NOT_RUN} 并给出原因；
     * 只有报告里 {@code failed=0 且 notRun=0 且 total>0} 才算 {@code PASSED}。</p>
     */
    public LiveGate liveGate() {
        boolean keyConfigured = apiKeyConfigured();
        String keyHint = keyConfigured ? "" : "未配置 DEEPSEEK_API_KEY；";
        Path report = latestLiveReport();
        if (report == null) {
            return new LiveGate(LIVE_NOT_RUN, null,
                    keyHint + "未跑：没有找到 reports/eval-live-*.json（请执行 --suite=live 生成）", null);
        }
        String fileName = report.getFileName().toString();
        try {
            JsonNode root = objectMapper.readTree(report.toFile());
            JsonNode totals = root.path("totals");
            int total = totals.path("total").asInt(0);
            int passed = totals.path("passed").asInt(0);
            int failed = totals.path("failed").asInt(0);
            int notRun = totals.path("notRun").asInt(0);
            String at = root.path("generatedAt").asText(null);
            String detail = root.path("status").path("live").asText("");

            if (failed > 0) {
                return new LiveGate(LIVE_FAILED, at,
                        "最近一次真机评测有 " + failed + " 条失败（" + fileName + "）", fileName);
            }
            if (notRun > 0 || total == 0) {
                return new LiveGate(LIVE_NOT_RUN, at,
                        keyHint + "未跑：" + (detail.isBlank() ? "最近一次报告显示未跑" : detail)
                                + "（" + fileName + "）", fileName);
            }
            return new LiveGate(LIVE_PASSED, at,
                    "通过：" + passed + "/" + total + "（" + fileName + "）", fileName);
        } catch (RuntimeException ex) {
            // Jackson 3 的 JacksonException 是**非受检**异常；这里连解析失败也按"未跑"处理，绝不伪造通过
            log.warn("读取真机集报告失败，按『未跑』处理（不伪造通过）：{}", ex.getMessage());
            return new LiveGate(LIVE_NOT_RUN, null,
                    keyHint + "未跑：报告无法解析（" + fileName + "）", fileName);
        }
    }

    private boolean apiKeyConfigured() {
        if (environment == null) {
            return false;
        }
        String value = environment.getProperty("DEEPSEEK_API_KEY");
        return value != null && !value.isBlank() && !"not-configured".equals(value);
    }

    /** 最新一份 {@code eval-live-*.json}；找不到返回 null（工作目录与模块目录都试一次）。 */
    private Path latestLiveReport() {
        String configured = environment == null
                ? "reports" : environment.getProperty("guarantee.ai.eval.report-dir", "reports");
        Path base = Path.of(configured);
        if (!Files.isDirectory(base)) {
            base = Path.of("..", configured);
        }
        if (!Files.isDirectory(base)) {
            return null;
        }
        try (Stream<Path> files = Files.list(base)) {
            return files.filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith("eval-live-") && name.endsWith(".json");
                    })
                    .max(Comparator.comparingLong(path -> path.toFile().lastModified()))
                    .orElse(null);
        } catch (IOException ex) {
            log.warn("扫描真机集报告目录失败：{}", ex.getMessage());
            return null;
        }
    }

    // ==================================================================
    // 草稿 / 发布 / 回滚
    // ==================================================================

    /**
     * 保存草稿：已有草稿则原地更新，否则新建一个版本。
     *
     * <p>草稿允许暂时缺保护标记（编辑过程），**发布时**才强制校验。</p>
     */
    @Transactional
    public AiPromptVersion saveDraft(String content, String note, String operator) {
        if (content == null || content.isBlank()) {
            throw BizException.badRequest("提示词正文不能为空");
        }
        String hash = sha256(content);
        AiPromptVersion existing = mapper.selectLatestDraft();
        if (existing != null) {
            int updated = mapper.updateDraft(existing.getVersionNo(), content, hash, note);
            if (updated != 1) {
                // 状态在读取与更新之间变化（并发发布）：明确报错，不静默丢失编辑内容
                throw new BizException("草稿保存失败：版本 v" + existing.getVersionNo()
                        + " 已不是草稿状态，请刷新后重新编辑");
            }
            log.info("提示词草稿已更新 v{} hash={} operator={}", existing.getVersionNo(), hash, operator);
            return mapper.selectByVersionNo(existing.getVersionNo());
        }

        Integer max = mapper.maxVersionNo();
        int next = (max == null ? 0 : max) + 1;
        AiPromptVersion draft = new AiPromptVersion();
        draft.setVersionNo(next);
        draft.setContent(content);
        draft.setContentHash(hash);
        draft.setStatus(AiPromptVersion.STATUS_DRAFT);
        draft.setNote(note);
        draft.setCreatedBy(operator);
        mapper.insert(draft);
        log.info("提示词草稿已创建 v{} hash={} operator={}", next, hash, operator);
        return mapper.selectByVersionNo(next);
    }

    /**
     * 发布草稿（REQ-CFG-02 / REQ-CFG-11）。
     *
     * <p>顺序固定：**保护标记校验 → 门禁（事务外）→ 短事务切版本 + 审计**。
     * 校验或门禁不通过时**不产生任何状态变化**。</p>
     *
     * <p><b>门禁必须跑在事务之外</b>：它要跑一整套确定性黄金问题集（嵌套 maven，实测独立 13.9s；
     * 与其它构建并发时曾超过 180s）。若挂在 {@code @Transactional} 里，就会持有一个分钟级的
     * 数据库连接与行锁——真机 e2e 实测撞到过这一点。因此这里是"先评估、再开短事务"，
     * 而不是"先切版本、后补门禁"：**没有新鲜 PASSED 就绝不进入切版本那一步**。</p>
     */
    public AiPromptVersion publish(int versionNo, String operator) {
        AiPromptVersion version = requireVersion(versionNo);
        if (!AiPromptVersion.STATUS_DRAFT.equals(version.getStatus())) {
            throw BizException.badRequest("只有草稿可以发布：v" + versionNo + " 当前状态为 " + version.getStatus()
                    + "（已发布版本不可修改；如需回退请使用回滚）");
        }
        requireProtectedMarkers(version.getContent());

        GateResult result = evaluateGate();
        if (!result.passed()) {
            throw BizException.badRequest(result.ran()
                    ? "发布门禁未通过：" + result.summary()
                    : "发布门禁未跑：" + result.summary()
                            + "（确定性黄金问题集必须先全绿；不允许在门禁未跑时发布）");
        }
        return transactionTemplate.execute(status -> switchPublished(version, operator, "PUBLISH"));
    }

    /**
     * 回滚：把生效版本指回某个历史版本（REQ-CFG-02 / §5.1.6）。
     *
     * <p>不修改历史版本内容，也不重跑门禁（应急止损路径）；全过程写审计。
     * 切版本与审计仍在**同一个短事务**里。</p>
     */
    public AiPromptVersion rollback(int toVersionNo, String operator) {
        AiPromptVersion target = requireVersion(toVersionNo);
        if (AiPromptVersion.STATUS_DRAFT.equals(target.getStatus())) {
            throw BizException.badRequest("不能回滚到草稿版本 v" + toVersionNo + "：草稿从未生效");
        }
        if (AiPromptVersion.STATUS_PUBLISHED.equals(target.getStatus())) {
            throw BizException.badRequest("版本 v" + toVersionNo + " 当前已是生效版本，无需回滚");
        }
        requireProtectedMarkers(target.getContent());
        return transactionTemplate.execute(status -> switchPublished(target, operator, "ROLLBACK"));
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private void requireProtectedMarkers(String content) {
        List<String> missing = missingProtectedMarkers(content);
        if (!missing.isEmpty()) {
            throw BizException.badRequest("提示词缺少受保护段落，已拒绝发布："
                    + String.join("；", missing)
                    + "。这些是红线条款（数据来源、事实与推测、写操作确认、敏感信息、权限可见性），"
                    + "只能修改措辞、不能整节删除。");
        }
    }

    /** 归档旧发布版 → 发布目标版本 → 同步配置投影 → 写审计（同一事务）。 */
    private AiPromptVersion switchPublished(AiPromptVersion version, String operator, String action) {
        String previousStatus = version.getStatus();
        mapper.archivePublishedExcept(version.getVersionNo());
        int updated = mapper.publish(version.getVersionNo(), operator);
        if (updated != 1) {
            throw new BizException("提示词发布失败：版本 v" + version.getVersionNo()
                    + " 状态已变化，请刷新后重试");
        }

        // 配置投影：prompt.active-version 指向当前生效版本（真源是 ai_prompt_version.status=PUBLISHED）
        try {
            configService.update(AiConfigCatalog.PROMPT_ACTIVE_VERSION,
                    String.valueOf(version.getVersionNo()), operator);
        } catch (RuntimeException ex) {
            log.warn("同步 prompt.active-version 投影失败（不影响发布本身）：{}", ex.getMessage());
        }

        // 审计：发布/回滚都属于配置变更（REQ-CFG-05）
        webAuditor.success(
                OperationAuditService.ACTION_CONFIG_UPDATE,
                OperationAuditService.TARGET_TYPE_AI_CONFIG,
                (long) version.getVersionNo(),
                TARGET_NAME_PREFIX + version.getVersionNo(),
                Map.of("status", previousStatus, "contentHash", String.valueOf(version.getContentHash())),
                Map.of("status", AiPromptVersion.STATUS_PUBLISHED, "contentHash", String.valueOf(version.getContentHash())),
                Set.of("status", "contentHash"));

        log.info("提示词{}完成 v{} hash={} operator={}（{}）",
                "ROLLBACK".equals(action) ? "回滚" : "发布", version.getVersionNo(),
                version.getContentHash(), operator, action);
        return mapper.selectByVersionNo(version.getVersionNo());
    }

    static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用，无法计算提示词内容哈希", ex);
        }
    }

    // ==================================================================
    // 发布门禁
    // ==================================================================

    /**
     * 门禁结果。
     *
     * @param ran     命令是否真的跑起来（false = 脚本不存在 / 参数未实现 / 执行异常 / 超时）
     * @param passed  是否通过（只有 ran 且退出码 0 才为 true；ran=false 时恒为 false）
     * @param summary 可读说明（页面直接展示，含"未跑"的原因）
     */
    public record GateResult(boolean ran, boolean passed, String summary) {

        public static GateResult passed(String summary) {
            return new GateResult(true, true, summary);
        }

        public static GateResult failed(String summary) {
            return new GateResult(true, false, summary);
        }

        public static GateResult notRun(String summary) {
            return new GateResult(false, false, summary);
        }
    }

    /** 门禁执行端口（单测打桩用；生产实现见 {@link CommandPromptGate}）。 */
    @FunctionalInterface
    public interface PromptGate {
        GateResult evaluate();
    }

    /**
     * 真机集（{@code --suite=live}）状态。
     *
     * @param status {@code NOT_RUN} / {@code PASSED} / {@code FAILED}
     * @param at     报告生成时间（可能为 null：没有报告时）
     * @param reason 可读原因（页面直接展示；"未跑"必须写清是因为缺 Key 还是没跑过）
     * @param source 报告文件名（可能为 null）
     */
    public record LiveGate(String status, String at, String reason, String source) {
    }

    /**
     * 命令行门禁：执行 {@code node scripts/ai-golden-questions.mjs --suite=deterministic}，
     * 退出码 0 才算通过。
     *
     * <p><b>任何"没跑起来"的情况都是 NOT_RUN，而不是通过</b>：命令不存在、Node 未安装、
     * 脚本还没实现 {@code --suite}（阶段五 task-14 正在补）、超时、读取输出失败——
     * 一律拒绝发布，并把原因写进 summary 供页面显示"未跑"。</p>
     */
    static final class CommandPromptGate implements PromptGate {

        private final String command;

        CommandPromptGate(String command) {
            this.command = command;
        }

        @Override
        public GateResult evaluate() {
            if (command == null || command.isBlank()) {
                return GateResult.notRun("未配置门禁命令");
            }
            Process process = null;
            try {
                ProcessBuilder builder = new ProcessBuilder(command.trim().split("\\s+"));
                builder.redirectErrorStream(true);
                process = builder.start();
                String output;
                try (InputStream in = process.getInputStream()) {
                    output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                if (!process.waitFor(GATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    return GateResult.notRun("门禁命令超时（>" + GATE_TIMEOUT_MS / 1000 + "s）：" + command);
                }
                int exit = process.exitValue();
                if (exit == 0) {
                    return GateResult.passed("确定性黄金问题集全绿（" + command + "）");
                }
                return GateResult.failed("确定性黄金问题集未全绿（exit=" + exit + "）：" + tail(output));
            } catch (IOException ex) {
                // 脚本不存在 / Node 未安装 / --suite 参数尚未实现（阶段五 task-14 才补齐）
                return GateResult.notRun("门禁命令无法执行（" + ex.getMessage() + "）：" + command);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return GateResult.notRun("门禁命令被中断：" + command);
            } finally {
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        }

        private static String tail(String output) {
            if (output == null || output.isBlank()) {
                return "（无输出）";
            }
            String flat = output.strip();
            return flat.length() <= 300 ? flat : flat.substring(flat.length() - 300);
        }
    }
}
