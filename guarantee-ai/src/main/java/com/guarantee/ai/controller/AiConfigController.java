package com.guarantee.ai.controller;

import com.guarantee.ai.config.AiConfigCatalog;
import com.guarantee.ai.config.AiConfigDefinition;
import com.guarantee.ai.config.AiConfigService;
import com.guarantee.ai.config.AiConfigSnapshot;
import com.guarantee.ai.config.AiPromptVersion;
import com.guarantee.ai.config.PromptVersionService;
import com.guarantee.ai.service.OperationAuditService;
import com.guarantee.common.api.Result;
import com.guarantee.common.api.ResultCode;
import com.guarantee.common.exception.BizException;
import com.guarantee.common.security.CurrentUser;
import com.guarantee.common.security.Permissions;
import com.guarantee.common.security.SensitiveFieldMasker;
import com.guarantee.system.service.WebAuditor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.core.env.Environment;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * AI 配置接口（REQ-CFG-01 / REQ-CFG-05 / REQ-CFG-10 / docs REQ §6.3）。
 *
 * <p><b>通道口径（Q-CFG-06/07 已拍板）</b>：第一版<b>不给助手</b>写配置的能力，
 * 配置变更只走页面渠道：表单 + 前端二次确认（危险项）+ <b>直接落库</b> + {@code source=WEB} 审计。
 * 因此这里没有"配置提案 / 确认卡"，也不存在 {@code ConfigProposalExecutor}——
 * 红线要求的"确认 + 审计"由"前端二次确认 + 服务端 100% 写审计"承担，
 * 而不是把配置变更绕成一条助手工具（登记为后续扩展点）。</p>
 *
 * <p><b>权限</b>：读 {@code ai:config:view}、写 {@code ai:config:update}（危险权限）。
 * 前端隐藏按钮不算数，两个接口都必须有 {@code @PreAuthorize} 兜底。</p>
 *
 * <p><b>写入口径</b>：{@code target_type=AI_CONFIG}、{@code action=CONFIG_UPDATE}、
 * {@code target_id=NULL}（配置键是字符串，而 {@code ai_operation_audit.target_id} 是 BIGINT）、
 * {@code target_name=配置键}；密钥类只记"是否变化"（{@code <changed>} 占位符），绝不落值。</p>
 *
 * <p><b>提示词版本接口</b>（{@code /prompts*}）由 T4-03 在本类追加：
 * 版本机（草稿/发布/回滚 + 门禁）在 {@code PromptVersionService}，T4-02 不提前实现。</p>
 */
@RestController
@RequestMapping("/api/ai/config")
public class AiConfigController {

    /**
     * 密钥类配置项（审计只记"是否变化"）。
     *
     * <p>第一版只有 {@code model.api-key-ref}：它的值本身就是"引用名"，
     * 页面上允许展示（否则管理员不知道自己引用了哪个环境变量），但审计里仍按密钥类处理——
     * 引用名会暴露"密钥从哪来"，且一旦将来该值的语义变成真实密钥，这里不用再改一次。</p>
     */
    private static final Set<String> SECRET_CLASS_KEYS = Set.of(AiConfigCatalog.MODEL_API_KEY_REF);

    private final AiConfigService configService;
    private final AiConfigCatalog catalog;
    private final WebAuditor webAuditor;
    private final Environment environment;
    private final PromptVersionService promptVersionService;

    public AiConfigController(AiConfigService configService,
                              AiConfigCatalog catalog,
                              WebAuditor webAuditor,
                              Environment environment,
                              PromptVersionService promptVersionService) {
        this.configService = configService;
        this.catalog = catalog;
        this.webAuditor = webAuditor;
        this.environment = environment;
        this.promptVersionService = promptVersionService;
    }

    // ==================================================================
    // 读：全部配置项
    // ==================================================================

    /**
     * 返回全部配置项（REQ §6.3）。
     *
     * <p>密钥类只返回"是否已配置"（{@link AiConfigItemView#configured()}）与引用名，
     * 密钥值永远不经过本接口——它只存在于环境变量里。</p>
     */
    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.AI_CONFIG_VIEW + "')")
    public Result<AiConfigView> view() {
        AiConfigSnapshot snapshot = configService.snapshot();
        List<AiConfigItemView> items = catalog.all().stream()
                .map(def -> toView(def, snapshot))
                .toList();
        return Result.ok(new AiConfigView(snapshot.version(), snapshot.loadedAt(), items));
    }

    // ==================================================================
    // 写：直接落库 + WEB 审计
    // ==================================================================

    /**
     * 修改一个配置项（REQ-CFG-04 的页面渠道）。
     *
     * <p><b>为什么标 {@code @Transactional}</b>：审计与配置必须同事务（SYS-A-03）。
     * 配置写成功而审计写失败，就是"数据改了但没痕迹"——那比"操作失败"严重得多，
     * 无法事后补救。本方法内 {@code configService.update/reset} 与 {@code webAuditor.success}
     * 共享同一事务，审计写入失败会一起回滚。</p>
     *
     * <p><b>值未变化时不写入也不记审计</b>：没有变更就没有"谁改了什么"，
     * 记一条"改了但没变"只会污染审计与版本号。返回 {@code changed=false} 让页面如实提示。</p>
     */
    @PostMapping("/change")
    @PreAuthorize("hasAuthority('" + Permissions.AI_CONFIG_UPDATE + "')")
    @Transactional
    public Result<AiConfigChangeResult> change(@Valid @RequestBody AiConfigChangeRequest request) {
        AiConfigDefinition def = catalog.require(request.key());
        // 先校验（纯内存、不查库）再取快照：非法值在触库前就被拒绝，调用方也不会产生任何副作用
        String normalized = AiConfigCatalog.validate(def, request.value());

        AiConfigSnapshot before = configService.snapshot();
        String beforeValue = before.get(def.key());

        if (Objects.equals(beforeValue, normalized)) {
            return Result.ok(new AiConfigChangeResult(def.key(), false, beforeValue, before.version(),
                    "值未发生变化：未写入配置，也未产生审计"));
        }

        String operator = requireUserId().toString();
        AiConfigSnapshot after = normalized == null
                ? configService.reset(def.key(), operator)
                : configService.update(def.key(), request.value(), operator);
        String afterValue = after.get(def.key());

        boolean secret = SECRET_CLASS_KEYS.contains(def.key());
        webAuditor.success(
                OperationAuditService.ACTION_CONFIG_UPDATE,
                OperationAuditService.TARGET_TYPE_AI_CONFIG,
                null,
                def.key(),
                auditSnapshot(def, beforeValue, secret),
                auditSnapshot(def, afterValue, secret),
                Set.of(def.key()));

        return Result.ok(new AiConfigChangeResult(def.key(), true, afterValue, after.version(),
                "配置已更新（下一个请求生效），并已写入操作审计"));
    }

    // ==================================================================
    // 提示词版本（REQ-CFG-02 / REQ-CFG-11）
    // ==================================================================

    /**
     * 版本历史 + 当前草稿 + 当前门禁结果。
     *
     * <p>门禁结果**必须如实返回**：{@code ran=false} 就是"未跑"（脚本不存在 / {@code --suite}
     * 尚未实现 / 执行失败），页面据此显示"未跑"，而不是把它显示成"通过"。</p>
     */
    @GetMapping("/prompts")
    @PreAuthorize("hasAuthority('" + Permissions.AI_CONFIG_VIEW + "')")
    public Result<PromptHistoryView> promptHistory() {
        List<PromptVersionView> versions = promptVersionService.history().stream()
                .map(AiConfigController::toPromptView)
                .toList();
        return Result.ok(new PromptHistoryView(versions,
                toPromptView(promptVersionService.draft()),
                toGateView(promptVersionService.evaluateGate())));
    }

    /** 单个版本正文 + 缺失的保护标记（编辑页据此给出强警告）。 */
    @GetMapping("/prompts/{versionNo}")
    @PreAuthorize("hasAuthority('" + Permissions.AI_CONFIG_VIEW + "')")
    public Result<PromptDetailView> promptDetail(@PathVariable int versionNo) {
        return Result.ok(toPromptDetail(promptVersionService.requireVersion(versionNo)));
    }

    /** 只查门禁结果（发布前页面先刷一次，避免"点了发布才知道未跑"）。 */
    @GetMapping("/prompts/gate")
    @PreAuthorize("hasAuthority('" + Permissions.AI_CONFIG_VIEW + "')")
    public Result<GateView> promptGate() {
        return Result.ok(toGateView(promptVersionService.evaluateGate()));
    }

    /** 保存草稿（已有草稿则原地更新；已发布版本不可改）。 */
    @PostMapping("/prompts/draft")
    @PreAuthorize("hasAuthority('" + Permissions.AI_CONFIG_UPDATE + "')")
    public Result<PromptDetailView> savePromptDraft(@Valid @RequestBody PromptDraftRequest request) {
        AiPromptVersion draft = promptVersionService.saveDraft(request.content(), request.note(),
                requireUserId().toString());
        return Result.ok(toPromptDetail(draft));
    }

    /**
     * 发布草稿：服务端先校验保护标记，再跑确定性黄金问题集门禁；两者任一不通过都拒绝发布。
     *
     * <p>门禁"未跑"同样拒绝（REQ-CFG-11 / AC-CFG-10），错误信息里带原因，页面照原样展示。</p>
     */
    @PostMapping("/prompts/publish")
    @PreAuthorize("hasAuthority('" + Permissions.AI_CONFIG_UPDATE + "')")
    public Result<PromptPublishResult> publishPrompt(@Valid @RequestBody PromptVersionRequest request) {
        AiPromptVersion published = promptVersionService.publish(request.versionNo(),
                requireUserId().toString());
        return Result.ok(new PromptPublishResult(published.getVersionNo(), published.getStatus(),
                published.getContentHash(), true, "发布成功（确定性门禁通过），下一个请求生效"));
    }

    /** 回滚到历史版本（应急路径，不重跑门禁；写审计）。 */
    @PostMapping("/prompts/rollback")
    @PreAuthorize("hasAuthority('" + Permissions.AI_CONFIG_UPDATE + "')")
    public Result<PromptPublishResult> rollbackPrompt(@Valid @RequestBody PromptVersionRequest request) {
        AiPromptVersion version = promptVersionService.rollback(request.versionNo(),
                requireUserId().toString());
        return Result.ok(new PromptPublishResult(version.getVersionNo(), version.getStatus(),
                version.getContentHash(), false, "已回滚到 v" + version.getVersionNo() + "，下一个请求生效"));
    }

    // ==================================================================
    // 视图与请求体
    // ==================================================================

    /** 全部配置项 + 当前快照版本。 */
    public record AiConfigView(long version, LocalDateTime loadedAt, List<AiConfigItemView> items) {
    }

    /**
     * 单个配置项。
     *
     * @param key         配置键
     * @param value       生效值；未设置且无默认值时为 null（密钥类此处是"引用名"，不是密钥值）
     * @param defaultValue 目录默认值；null 表示未设置（沿用框架默认）
     * @param overridden  当前值是否来自库中显式配置（false = 正在用默认值）
     * @param secretClass 是否密钥类（审计只记"是否变化"）
     * @param configured  密钥类专用：引用名指向的环境变量是否真的配置了；非密钥类为 null
     */
    public record AiConfigItemView(
            String key,
            String value,
            String defaultValue,
            boolean overridden,
            String valueType,
            String category,
            boolean dangerous,
            boolean secretClass,
            Boolean configured,
            BigDecimal minValue,
            BigDecimal maxValue,
            List<String> enumOptions,
            String description) {
    }

    /**
     * 修改请求。
     *
     * @param key   配置键（必须在 {@link AiConfigCatalog} 中）
     * @param value 新值；{@code null} 表示恢复默认值。类型/范围/枚举在服务端校验，越界给可读错误。
     */
    public record AiConfigChangeRequest(@NotBlank String key, String value) {
    }

    /**
     * 修改结果。
     *
     * @param changed 是否真的发生了变更（false = 值相同，未写入也未审计）
     * @param value   变更后的生效值
     * @param version 变更后的配置快照版本号
     */
    public record AiConfigChangeResult(
            String key,
            boolean changed,
            String value,
            long version,
            String message) {
    }

    /** 提示词版本（列表用，不含正文）。 */
    public record PromptVersionView(
            int versionNo,
            String status,
            String note,
            String contentHash,
            String createdBy,
            LocalDateTime createdAt,
            String publishedBy,
            LocalDateTime publishedAt,
            int contentLength) {
    }

    /** 版本正文 + 缺失的保护标记（缺失非空即不可发布）。 */
    public record PromptDetailView(PromptVersionView version, String content,
                                   List<String> missingProtectedMarkers) {
    }

    /** 版本历史 + 当前草稿 + 门禁结果。 */
    public record PromptHistoryView(List<PromptVersionView> versions, PromptVersionView draft,
                                    GateView gate) {
    }

    /** 门禁结果：{@code ran=false} 就是"未跑"，页面必须如实显示而不是当成通过（AC-CFG-10）。 */
    public record GateView(boolean ran, boolean passed, String summary) {
    }

    /** 保存草稿请求。 */
    public record PromptDraftRequest(@NotBlank String content, String note) {
    }

    /** 发布 / 回滚的目标版本。 */
    public record PromptVersionRequest(@NotNull Integer versionNo) {
    }

    /** 发布 / 回滚结果。 */
    public record PromptPublishResult(int versionNo, String status, String contentHash,
                                      boolean gated, String message) {
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private static PromptVersionView toPromptView(AiPromptVersion version) {
        if (version == null) {
            return null;
        }
        return new PromptVersionView(
                version.getVersionNo() == null ? 0 : version.getVersionNo(),
                version.getStatus(),
                version.getNote(),
                version.getContentHash(),
                version.getCreatedBy(),
                version.getCreatedAt(),
                version.getPublishedBy(),
                version.getPublishedAt(),
                version.getContent() == null ? 0 : version.getContent().length());
    }

    private static PromptDetailView toPromptDetail(AiPromptVersion version) {
        return new PromptDetailView(toPromptView(version), version.getContent(),
                PromptVersionService.missingProtectedMarkers(version.getContent()));
    }

    private static GateView toGateView(PromptVersionService.GateResult result) {
        return new GateView(result.ran(), result.passed(), result.summary());
    }

    private AiConfigItemView toView(AiConfigDefinition def, AiConfigSnapshot snapshot) {
        boolean secret = SECRET_CLASS_KEYS.contains(def.key());
        String value = snapshot.get(def.key());
        return new AiConfigItemView(
                def.key(),
                value,
                def.defaultValue(),
                snapshot.isOverridden(def.key()),
                def.type().name(),
                def.category().name(),
                def.dangerous(),
                secret,
                secret ? apiKeyConfigured(value) : null,
                def.minValue(),
                def.maxValue(),
                def.enumOptions(),
                def.description());
    }

    /** 密钥是否已配置：引用名指向的环境变量存在且不是启动占位值 {@code not-configured}。 */
    private boolean apiKeyConfigured(String refName) {
        if (refName == null || refName.isBlank()) {
            return false;
        }
        String value = environment.getProperty(refName);
        return value != null && !value.isBlank() && !"not-configured".equals(value);
    }

    /**
     * 审计快照。
     *
     * <p>非密钥类：{@code {配置键: 值}}，字段级 diff 直接可读。</p>
     *
     * <p>密钥类：只写 {@link SensitiveFieldMasker#CHANGED_PLACEHOLDER}（{@code <changed>}），
     * 值为 null 时写空快照（清空引用名 = 从"有值"变成"无值"，本身就是一次可检索的变更）。
     * 绝不把引用名之外的东西写进审计。</p>
     */
    private static Map<String, Object> auditSnapshot(AiConfigDefinition def, String value, boolean secret) {
        if (value == null) {
            return Map.of();
        }
        return Map.of(def.key(), secret ? SensitiveFieldMasker.CHANGED_PLACEHOLDER : value);
    }

    private static Long requireUserId() {
        Long userId = CurrentUser.userId();
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED, "未登录或登录已过期");
        }
        return userId;
    }
}
