package com.guarantee.ai.knowledge;

import com.guarantee.ai.knowledge.entity.AiKnowledgeItem;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;

/**
 * 知识内容的规范化哈希——幂等导入的判据（REQ-RAG-02）。
 *
 * <p><b>为什么要单独一个类</b>：导入器需要把"真源文件解析出来的内容"与
 * "库里现有行"当成同一件事来比较。若两处各写一套拼接逻辑，迟早会分叉——
 * 一旦分叉，要么每次都判定"变了"（版本号狂涨、每次都写留痕），
 * 要么内容真变了却判定"没变"（知识静默过期，最坏的一种）。因此
 * <b>两个来源共用本类</b>：{@link KnowledgeDocument#contentHash()} 与
 * {@link #of(AiKnowledgeItem)}。</p>
 *
 * <p><b>规范化口径（参与哈希的字段）</b>：domain、title、content、keywords、
 * effective_from、effective_to、status、permission_code、source_ref；字段间用
 * {@code \u0000} 连接，空值统一成空串。</p>
 *
 * <p><b>刻意排除 {@code version}</b>：version 是"内容变了"的**结果**，不能反过来参与
 * "是否变了"的判定（否则每一次 version+1 都会让下一次导入再判定一次变化，自我永动）。
 * {@code is_deleted}/{@code deleted_at}/{@code deleted_by} 与三个时间戳同理排除。</p>
 */
public final class KnowledgeContentHash {

    private static final char SEPARATOR = '\u0000';

    private KnowledgeContentHash() {
    }

    /** 真源文件一侧的内容哈希。 */
    public static String of(KnowledgeDomain domain,
                            String title,
                            String content,
                            String keywords,
                            LocalDate effectiveFrom,
                            LocalDate effectiveTo,
                            KnowledgeStatus status,
                            String permissionCode,
                            String sourceRef) {
        String canonical = String.join(String.valueOf(SEPARATOR),
                text(domain == null ? null : domain.name()),
                text(title),
                text(content),
                text(keywords),
                date(effectiveFrom),
                date(effectiveTo),
                text(status == null ? null : status.name()),
                text(permissionCode),
                text(sourceRef));
        return sha256Hex(canonical);
    }

    /** 数据库一侧的内容哈希（字段与真源一侧一一对应）。 */
    public static String of(AiKnowledgeItem item) {
        if (item == null) {
            throw new IllegalArgumentException("item 不能为空");
        }
        return of(item.getDomain(), item.getTitle(), item.getContent(), item.getKeywords(),
                item.getEffectiveFrom(), item.getEffectiveTo(), item.getStatus(),
                item.getPermissionCode(), item.getSourceRef());
    }

    public static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // JDK 必然提供 SHA-256；走到这里说明运行环境被裁剪过，必须显式失败而不是静默降级
            throw new IllegalStateException("运行环境缺少 SHA-256 实现", ex);
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String date(LocalDate value) {
        return value == null ? "" : value.toString();
    }
}
