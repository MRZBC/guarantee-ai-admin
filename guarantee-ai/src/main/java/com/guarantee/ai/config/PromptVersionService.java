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
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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

    private final AiPromptVersionMapper mapper;
    private final AiConfigService configService;
    private final WebAuditor webAuditor;
    private final PromptGate gate;

    /** 生产构造器：门禁用命令行实现。 */
    @Autowired
    public PromptVersionService(AiPromptVersionMapper mapper,
                                AiConfigService configService,
                                WebAuditor webAuditor,
                                Environment environment) {
        this(mapper, configService, webAuditor, new CommandPromptGate(
                environment.getProperty("guarantee.ai.prompt.gate-command", GATE_COMMAND_DEFAULT)));
    }

    /** 测试构造器：注入可打桩的门禁，避免单测真的去起 Node。 */
    PromptVersionService(AiPromptVersionMapper mapper,
                         AiConfigService configService,
                         WebAuditor webAuditor,
                         PromptGate gate) {
        this.mapper = mapper;
        this.configService = configService;
        this.webAuditor = webAuditor;
        this.gate = gate;
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

    /** 当前门禁结果（页面用它显示"通过 / 失败 / 未跑"，不隐藏失败）。 */
    public GateResult evaluateGate() {
        return gate.evaluate();
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
     * <p>顺序固定：**保护标记校验 → 门禁 → 归档旧版本 → 发布 → 审计**。
     * 校验或门禁不通过时**不产生任何状态变化**。</p>
     */
    @Transactional
    public AiPromptVersion publish(int versionNo, String operator) {
        AiPromptVersion version = requireVersion(versionNo);
        if (!AiPromptVersion.STATUS_DRAFT.equals(version.getStatus())) {
            throw BizException.badRequest("只有草稿可以发布：v" + versionNo + " 当前状态为 " + version.getStatus()
                    + "（已发布版本不可修改；如需回退请使用回滚）");
        }
        requireProtectedMarkers(version.getContent());

        GateResult result = gate.evaluate();
        if (!result.passed()) {
            throw BizException.badRequest(result.ran()
                    ? "发布门禁未通过：" + result.summary()
                    : "发布门禁未跑：" + result.summary()
                            + "（确定性黄金问题集必须先全绿；不允许在门禁未跑时发布）");
        }
        return switchPublished(version, operator, "PUBLISH");
    }

    /**
     * 回滚：把生效版本指回某个历史版本（REQ-CFG-02 / §5.1.6）。
     *
     * <p>不修改历史版本内容，也不重跑门禁（应急止损路径）；全过程写审计。</p>
     */
    @Transactional
    public AiPromptVersion rollback(int toVersionNo, String operator) {
        AiPromptVersion target = requireVersion(toVersionNo);
        if (AiPromptVersion.STATUS_DRAFT.equals(target.getStatus())) {
            throw BizException.badRequest("不能回滚到草稿版本 v" + toVersionNo + "：草稿从未生效");
        }
        if (AiPromptVersion.STATUS_PUBLISHED.equals(target.getStatus())) {
            throw BizException.badRequest("版本 v" + toVersionNo + " 当前已是生效版本，无需回滚");
        }
        requireProtectedMarkers(target.getContent());
        return switchPublished(target, operator, "ROLLBACK");
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
