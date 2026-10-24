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
import java.util.stream.Stream;

/** 提示词版本管理：候选评测独立执行，发布仅校验持久化证据并原子切换。 */
@Service
public class PromptVersionService {

    private static final Logger log = LoggerFactory.getLogger(PromptVersionService.class);

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
    @Autowired
    private org.springframework.beans.factory.ObjectProvider<PromptEvaluationService> evaluations;

    public record EffectivePrompt(String source, Integer versionNo, String content, String contentHash) {}

    /** 管理端严格读取，不把读库故障伪装成“从未发布”。 */
    public EffectivePrompt effectivePrompt() {
        AiPromptVersion current = mapper.selectPublished();
        String content = current == null ? PromptEvaluationService.builtin() : current.getContent();
        return new EffectivePrompt(current == null ? "BUILTIN" : "PUBLISHED",
                current == null ? null : current.getVersionNo(), content, sha256(content));
    }

    /** 客户端只能提交补充规则；基础正文、最终正文和哈希全部由服务端生成。 */
    @Transactional
    public AiPromptVersion saveBusinessDraft(String business, String note, String baseVersion, String baseHash,
            Integer sourceVersion, String sourceHash, boolean confirmed, String operator) {
        mapper.lockControl();
        PromptComposition.Base base = PromptComposition.base();
        if (!java.util.Objects.equals(base.version(), baseVersion)
                || !java.util.Objects.equals(base.contentHash(), baseHash))
            throw BizException.badRequest("平台基础规则已变化，请刷新后核对并保存");
        if (business == null || business.length() > 60000)
            throw BizException.badRequest("业务补充规则不能为空值且不得超过60000字符（允许空文本）");
        AiPromptVersion existing = mapper.selectLatestDraft();
        AiPromptVersion source = existing == null ? mapper.selectPublished() : existing;
        boolean legacy = source != null && source.getBaseVersion() == null;
        if (confirmed && (source == null || !java.util.Objects.equals(sourceVersion, source.getVersionNo())
                || !java.util.Objects.equals(sourceHash, source.getContentHash())))
            throw BizException.badRequest("迁移来源已变化，请刷新后重新核对旧正文并确认迁移");
        if (legacy && (!confirmed || !java.util.Objects.equals(sourceVersion, source.getVersionNo())
                || !java.util.Objects.equals(sourceHash, source.getContentHash())))
            throw BizException.badRequest("请核对旧完整正文并确认迁移；原版本或哈希已变化时须重新核对");
        String content = PromptComposition.compose(base.content(), business);
        AiPromptVersion draft;
        if (existing != null && !legacy) {
            int changed = mapper.updateDraft(existing.getVersionNo(), content, sha256(content), note);
            if (changed != 1) throw BizException.badRequest("草稿状态已变化，请刷新");
            draft = existing;
        } else {
            Integer max = mapper.maxVersionNo();
            draft = new AiPromptVersion();
            draft.setVersionNo(max == null ? 1 : max + 1);
            draft.setStatus(AiPromptVersion.STATUS_DRAFT);
            draft.setCreatedBy(operator);
        }
        draft.setContent(content); draft.setContentHash(sha256(content)); draft.setNote(note);
        draft.setBaseVersion(base.version()); draft.setBaseHash(base.contentHash());
        draft.setBaseContent(base.content()); draft.setBusinessContent(business);
        if (legacy) {
            draft.setMigrationSourceVersion(source.getVersionNo()); draft.setMigrationSourceHash(source.getContentHash());
        }
        if (existing == null || legacy) mapper.insert(draft);
        mapper.saveComposition(draft);
        return mapper.selectByVersionNo(draft.getVersionNo());
    }

    /** 配置修改与发布共用同一控制行，调用方须位于事务内。 */
    public void lockConfigurationChange() { mapper.lockControl(); }

    /**
     * 真机集（{@code --suite=live}）报告的读取器：只读文件系统，**不执行任何评测**。
     *
     * <p>之所以要它：AC-CFG-10 子句②要求"真机集缺失时页面明确标注未跑"。
     * 真机集需要真实模型（{@code DEEPSEEK_API_KEY}），本机没有 → 页面必须显示"未跑"，
     * 而**不能**因为没有 Key 就假装通过、也不能因为读不到报告而报错。</p>
     */
    private final ObjectMapper objectMapper;
    private final Environment environment;

    /** 发布、回滚和草稿创建共用控制行锁，数据库变更与审计在短事务内完成。 */
    private final TransactionTemplate transactionTemplate;

    public PromptVersionService(AiPromptVersionMapper mapper, AiConfigService configService,
            WebAuditor webAuditor, Environment environment, ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.configService = configService;
        this.webAuditor = webAuditor;
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

    /** 旧客户端兼容入口，只读提示，绝不启动外部程序。 */
    public GateResult lastGateResult() {
        return GateResult.notRun("请保存草稿后使用候选版本评测；确定性回归由CI执行");
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
        mapper.lockControl();
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

    /** 兼容内部调用；仍须校验与目标正文一致的持久化评测证据。 */
    public AiPromptVersion publish(int versionNo, String operator) {
        AiPromptVersion version = requireVersion(versionNo);
        if (!AiPromptVersion.STATUS_DRAFT.equals(version.getStatus())) {
            throw BizException.badRequest("只有草稿可以发布：v" + versionNo + " 当前状态为 " + version.getStatus()
                    + "（已发布版本不可修改；如需回退请使用回滚）");
        }
        PromptComposition.requireSnapshot(version);

        return publish(versionNo, version.getContentHash(), null, operator);
    }

    public AiPromptVersion publish(int versionNo, String expectedHash, String evaluationId, String operator) {
        return transactionTemplate.execute(status -> {
            mapper.lockControl();
            AiPromptVersion version = mapper.lockVersion(versionNo);
            if (version == null || !AiPromptVersion.STATUS_DRAFT.equals(version.getStatus()))
                throw BizException.badRequest("只有草稿可以发布，请刷新后重试");
            if (!java.util.Objects.equals(expectedHash, version.getContentHash()))
                throw BizException.badRequest("草稿内容已变化，请刷新并重新评测");
            PromptComposition.requireSnapshot(version);
            String evidenceId = evaluations.getObject().requirePassed(version, evaluationId);
            return switchPublished(version, operator, "PUBLISH", evidenceId);
        });
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
        if (target.getBaseVersion() == null) requireProtectedMarkers(target.getContent());
        else PromptComposition.requireSnapshot(target);
        return transactionTemplate.execute(status -> {
            mapper.lockControl();
            AiPromptVersion locked = mapper.lockVersion(toVersionNo);
            if (locked == null || !AiPromptVersion.STATUS_ARCHIVED.equals(locked.getStatus()))
                throw BizException.badRequest("回滚目标状态已变化，请刷新后重试");
            if (locked.getBaseVersion() == null) requireProtectedMarkers(locked.getContent());
            else PromptComposition.requireSnapshot(locked);
            return switchPublished(locked, operator, "ROLLBACK", null);
        });
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
    private AiPromptVersion switchPublished(AiPromptVersion version, String operator, String action, String evaluationId) {
        // 调用方已持有控制行锁：记录真正的生效版切换，而不是目标版本自身的DRAFT→PUBLISHED。
        AiPromptVersion previous = mapper.selectPublished();
        Map<String, Object> before = new java.util.LinkedHashMap<>();
        before.put("source", previous == null ? "BUILTIN" : "PUBLISHED");
        before.put("versionNo", previous == null ? 0 : previous.getVersionNo());
        before.put("status", previous == null ? "BUILTIN" : previous.getStatus());
        before.put("contentHash", previous == null ? sha256(PromptEvaluationService.builtin()) : previous.getContentHash());
        if (previous != null && previous.getBaseVersion() != null) {
            before.put("baseVersion", previous.getBaseVersion()); before.put("baseHash", previous.getBaseHash());
        }
        mapper.archivePublishedExcept(version.getVersionNo());
        int updated = mapper.publish(version.getVersionNo(), operator);
        if (updated != 1) {
            throw new BizException("提示词发布失败：版本 v" + version.getVersionNo()
                    + " 状态已变化，请刷新后重试");
        }

        // 配置投影：prompt.active-version 指向当前生效版本（真源是 ai_prompt_version.status=PUBLISHED）
        configService.update(AiConfigCatalog.PROMPT_ACTIVE_VERSION,
                String.valueOf(version.getVersionNo()), operator);

        // 审计：发布/回滚都属于配置变更（REQ-CFG-05）
        Map<String, Object> after = new java.util.LinkedHashMap<>();
        after.put("promptAction", action);
        after.put("source", "PUBLISHED");
        after.put("versionNo", version.getVersionNo());
        after.put("status", AiPromptVersion.STATUS_PUBLISHED);
        after.put("contentHash", String.valueOf(version.getContentHash()));
        if (evaluationId != null) after.put("evaluationId", evaluationId);
        if (version.getBaseVersion() != null) {
            after.put("baseVersion", version.getBaseVersion());
            after.put("baseHash", version.getBaseHash());
            if (version.getMigrationSourceVersion() != null) {
                after.put("migrationSourceVersion", version.getMigrationSourceVersion());
                after.put("migrationSourceHash", version.getMigrationSourceHash());
            }
        }
        webAuditor.success(
                OperationAuditService.ACTION_CONFIG_UPDATE, OperationAuditService.TARGET_TYPE_AI_CONFIG,
                (long) version.getVersionNo(), TARGET_NAME_PREFIX + version.getVersionNo(),
                before, after, Set.copyOf(after.keySet()));

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

}
