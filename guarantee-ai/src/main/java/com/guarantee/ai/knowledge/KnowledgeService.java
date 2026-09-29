package com.guarantee.ai.knowledge;

import com.guarantee.ai.knowledge.entity.AiKnowledgeItem;
import com.guarantee.ai.knowledge.mapper.AiKnowledgeItemMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 业务知识检索服务（REQ-RAG-03 / 07）。
 *
 * <p><b>分层</b>：{@code Tool(QueryBusinessKnowledgeTool) → KnowledgeService → Mapper → DB}。
 * Tool 只依赖本服务，不接触 Mapper，也不生成 SQL（§2.3-2）。</p>
 *
 * <p><b>过滤器都在服务端</b>（这是与"提示词里写一句请不要越权"的本质区别）：</p>
 * <ol>
 *   <li>状态：只取 {@code PUBLISHED}（SQL 条件）；</li>
 *   <li>生效期：未生效或已失效的条目**不进候选集**（REQ-RAG-08 默认口径）；</li>
 *   <li>权限：{@code permission_code} 为空表示登录即可见，否则要求调用者权限快照包含该码；
 *       无权限的条目**既不返回也不提示存在**（REQ-RAG-07，沿用"不暴露存在性"话术）；</li>
 *   <li>条数与字节：{@code limit} 归一（默认 3 / 上限 5），单条正文 ≤2KB，
 *       合计 ≤{@code max-bytes}（默认 6KB），触顶置 {@code truncated}。</li>
 * </ol>
 *
 * <p><b>空结果不编造</b>：没有命中就返回空列表与"知识库：未收录"的 dataSource，
 * 工具不回填任何内容——模型据此如实说"未收录"（AC-RAG-03）。</p>
 */
@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    private final AiKnowledgeItemMapper itemMapper;
    private final KnowledgeProperties properties;

    public KnowledgeService(AiKnowledgeItemMapper itemMapper, KnowledgeProperties properties) {
        this.itemMapper = itemMapper;
        this.properties = properties;
    }

    /**
     * 检索知识条目。
     *
     * @param permissions 调用者权限快照（可为空 = 只可见"无需权限"的条目）
     * @param query       用户问题里的关键词或原句
     * @param domain      可选的知识域（{@code ORDER/SYSTEM/CONCEPT/POLICY}）；空 = 不限；
     *                    取值非法时按"不限"处理并记 WARN（不返回错误，避免模型被一个拼错的域卡死）
     * @param limit       期望条数；空/≤0 用默认 3，超过上限按上限裁到 5
     */
    @Transactional(readOnly = true)
    public KnowledgeSearchResult search(List<String> permissions, String query, String domain, Integer limit) {
        KnowledgeDomain parsedDomain = normalizeDomain(domain);
        int effectiveLimit = normalizeLimit(limit);
        if (query == null || query.isBlank()) {
            return KnowledgeSearchResult.notSearched("未提供检索关键词", parsedDomain);
        }

        List<AiKnowledgeItem> candidates = itemMapper.selectSearchCandidates(
                parsedDomain == null ? null : parsedDomain.name(), KnowledgeStatus.PUBLISHED.name());
        LocalDate today = LocalDate.now();
        List<KnowledgeRanking.Scored> ranked = KnowledgeRanking.rank(
                candidates.stream()
                        .filter(item -> visibleByPermission(item, permissions))
                        .filter(item -> effectiveAt(item, today))
                        .toList(),
                query);

        int totalHits = ranked.size();
        int budget = properties.effectiveMaxBytes();
        int used = 0;
        boolean truncated = totalHits > effectiveLimit;
        List<KnowledgeHit> hits = new ArrayList<>();
        for (KnowledgeRanking.Scored scored : ranked) {
            if (hits.size() >= effectiveLimit) {
                truncated = true;
                break;
            }
            String content = scored.item().getContent() == null ? "" : scored.item().getContent();
            int size = content.getBytes(StandardCharsets.UTF_8).length;
            if (used + size > budget) {
                // 第一条就超预算时截断正文（保证"有命中"不等于"什么都不返回"），否则到此为止
                if (hits.isEmpty()) {
                    hits.add(KnowledgeHit.from(scored.item()).withContent(truncateUtf8(content, budget)));
                    used = budget;
                }
                truncated = true;
                break;
            }
            hits.add(KnowledgeHit.from(scored.item()));
            used += size;
        }

        KnowledgeSearchResult result = KnowledgeSearchResult.of(hits, truncated, totalHits, parsedDomain);
        if (!truncated && hits.isEmpty()) {
            log.debug("知识检索无命中 query={} domain={}", query, parsedDomain);
        }
        return result;
    }

    /** {@code limit} 归一：默认 3；上限取 {@code min(配置 max-items, 5)}（REQ-RAG-03 / §6.4）。 */
    public int normalizeLimit(Integer limit) {
        int requested = (limit == null || limit <= 0)
                ? KnowledgeProperties.DEFAULT_MAX_ITEMS
                : limit;
        return Math.min(requested, properties.effectiveMaxItems());
    }

    /** 域归一：空 → 不限；非法 → 不限并记 WARN。 */
    public KnowledgeDomain normalizeDomain(String domain) {
        if (domain == null || domain.isBlank()) {
            return null;
        }
        return KnowledgeDomain.fromCode(domain).orElseGet(() -> {
            log.warn("知识检索收到非法域 {}，按不限处理（合法值：ORDER/SYSTEM/CONCEPT/POLICY）", domain);
            return null;
        });
    }

    /** 知识层是否可用（开关关闭时检索工具不注册，见 REQ-RAG-07/§6.4）。 */
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /** 权限可见性：没有声明权限码 = 登录即可见；声明了则必须命中调用者权限快照。 */
    static boolean visibleByPermission(AiKnowledgeItem item, List<String> permissions) {
        String required = item.getPermissionCode();
        if (required == null || required.isBlank()) {
            return true;
        }
        return permissions != null && permissions.contains(required);
    }

    /** 生效期：两端都可空（空 = 不限）；未生效或已失效一律不返回。 */
    static boolean effectiveAt(AiKnowledgeItem item, LocalDate today) {
        LocalDate from = item.getEffectiveFrom();
        LocalDate to = item.getEffectiveTo();
        if (from != null && today.isBefore(from)) {
            return false;
        }
        return to == null || !today.isAfter(to);
    }

    /** 按 UTF-8 边界截断，避免把一个多字节字符切成半个。 */
    static String truncateUtf8(String text, int maxBytes) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return text;
        }
        int end = Math.max(0, maxBytes);
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }
}
