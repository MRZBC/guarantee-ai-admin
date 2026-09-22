package com.guarantee.common.security;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 敏感字段统一脱敏工具（D-4 / SYS-A-02a / SYS-A-09 / SYS-P-11）。
 *
 * <p><b>为什么必须集中实现</b>：脱敏有三个落点——审计的 before/after 快照、工具返回值组装、
 * {@code ai_tool_call} / {@code ai_operation_proposal} 落库。任何一处各写一套正则，
 * 都会出现某条旁路泄漏明文（RK-12）。因此所有脱敏一律经本类。</p>
 *
 * <p><b>两条不同的规则</b>：</p>
 * <ul>
 *   <li>{@link #maskValue(String, String)}：给"需要给人看"的返回值用，手机号保留前三后四、
 *       邮箱保留首字母与域名；用于 ADMIN/OPERATOR 的 {@code queryUser} 返回值。</li>
 *   <li>{@link #maskSnapshotForAudit(Map)}：给"审计/落库快照"用，敏感字段**只记录字段名与
 *       是否发生变化，不记录任何具体值**（连掩码都不写）。该规则与操作者角色无关，
 *       ADMIN 同样脱敏（D-4）。</li>
 * </ul>
 */
public final class SensitiveFieldMasker {

    /** 审计快照中敏感字段的占位符：只表示"该字段已变更"，不携带任何值信息。 */
    public static final String CHANGED_PLACEHOLDER = "<changed>";

    /**
     * 敏感字段白名单（小写、下划线形式）。
     *
     * <p>命名覆盖 Java 字段（{@code realName}）与下划线形式（{@code real_name}）两种写法，
     * 比较前统一归一化。</p>
     */
    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "phone", "mobile", "telephone", "contactphone", "contact_phone",
            "email", "mail",
            "password", "passwd", "passwordhash", "password_hash",
            "token", "accesstoken", "access_token", "refreshtoken", "refresh_token",
            "secret",
            "idcard", "id_card", "idcardno", "id_card_no", "creditcode", "credit_code");

    /** 手机号：11 位大陆手机号。 */
    private static final Pattern PHONE = Pattern.compile("1[3-9]\\d{9}");

    /** 邮箱：宽松匹配，只用于判断"看起来像邮箱"。 */
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /** 单行 JSON 快照上限 8KB（SYS-A-16）。 */
    public static final int SNAPSHOT_MAX_BYTES = 8 * 1024;

    private SensitiveFieldMasker() {
    }

    /** 字段名是否属于敏感字段。 */
    public static boolean isSensitiveField(String fieldName) {
        return fieldName != null && SENSITIVE_FIELDS.contains(normalizeField(fieldName));
    }

    /** 归一化字段名：去掉下划线与大小写差异。 */
    private static String normalizeField(String fieldName) {
        return fieldName.trim().toLowerCase(Locale.ROOT).replace("_", "");
    }

    /**
     * 面向"展示"的脱敏：手机号 {@code 138****5678}、邮箱 {@code a***@guarantee.com}。
     *
     * <p>非敏感字段原样返回；无法识别为手机号/邮箱的值做整体掩码，宁可多脱也不泄漏。
     * 邮箱/手机号的判断同时依据字段名与值本身，避免"字段名不像但值像"时漏脱。</p>
     */
    public static String maskValue(String fieldName, String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        boolean phoneField = "phone".equals(normalizeField(fieldName))
                || "mobile".equals(normalizeField(fieldName))
                || "telephone".equals(normalizeField(fieldName))
                || "contactphone".equals(normalizeField(fieldName));
        boolean emailField = "email".equals(normalizeField(fieldName)) || "mail".equals(normalizeField(fieldName));

        if (phoneField || PHONE.matcher(value).matches()) {
            return maskPhone(value);
        }
        if (emailField || EMAIL.matcher(value).matches()) {
            return maskEmail(value);
        }
        if (isSensitiveField(fieldName)) {
            // 密码 / 令牌 / 证件类：整体掩码，绝不返回片段
            return "******";
        }
        return value;
    }

    /** {@code 13812345678 -> 138****5678}。 */
    public static String maskPhone(String phone) {
        if (phone == null || phone.isEmpty()) {
            return phone;
        }
        String trimmed = phone.trim();
        if (trimmed.length() < 7) {
            return "****";
        }
        return trimmed.substring(0, 3) + "****" + trimmed.substring(trimmed.length() - 4);
    }

    /** {@code analyst@guarantee.com -> a***@guarantee.com}。 */
    public static String maskEmail(String email) {
        if (email == null || email.isEmpty()) {
            return email;
        }
        String trimmed = email.trim();
        int at = trimmed.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = trimmed.substring(0, at);
        String domain = trimmed.substring(at);
        return local.charAt(0) + "***" + domain;
    }

    /**
     * 为审计/落库准备快照：敏感字段只保留"是否变更"，不写具体值（D-4）。
     *
     * <p>非敏感字段原样保留。返回一个**新的** Map，不修改入参。</p>
     */
    public static Map<String, Object> maskSnapshotForAudit(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> masked = new LinkedHashMap<>(source.size());
        for (Map.Entry<String, ?> entry : source.entrySet()) {
            String field = entry.getKey();
            if (isSensitiveField(field)) {
                Object value = entry.getValue();
                // 只有"确实有值"才标记为已变更；null 表示未参与本次变更
                masked.put(field, value == null ? null : CHANGED_PLACEHOLDER);
            } else {
                masked.put(field, entry.getValue());
            }
        }
        return masked;
    }

    /**
     * 从一个"变更前 / 变更后"字段快照对里挑出真正发生变化的敏感字段名。
     *
     * <p>用于填充 {@code ai_operation_audit.changed_fields}，使"手机号改过"这件事可检索，
     * 同时不泄漏改成了什么。</p>
     */
    public static Set<String> changedSensitiveFields(Map<String, ?> before, Map<String, ?> after) {
        Set<String> changed = new LinkedHashSet<>();
        Set<String> keys = new LinkedHashSet<>();
        if (before != null) {
            keys.addAll(before.keySet());
        }
        if (after != null) {
            keys.addAll(after.keySet());
        }
        for (String key : keys) {
            if (!isSensitiveField(key)) {
                continue;
            }
            Object beforeValue = before == null ? null : before.get(key);
            Object afterValue = after == null ? null : after.get(key);
            if (!java.util.Objects.equals(beforeValue, afterValue)) {
                changed.add(key);
            }
        }
        return changed;
    }

    /**
     * 对任意文本做兜底脱敏：把文本中出现的手机号 / 邮箱替换为掩码。
     *
     * <p>用于模型自由文本、错误消息等无法按字段名判定但又可能携带明文的场景。
     * 不做任何结构假设，只做模式替换。</p>
     */
    public static String maskText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = PHONE.matcher(text).replaceAll(match -> maskPhone(match.group()));
        return EMAIL.matcher(result).replaceAll(match -> maskEmail(match.group()));
    }

    /**
     * 递归脱敏一个 JSON 友好的结构（Map / List / 标量）。
     *
     * <p>这是 {@code ai_tool_call.arguments} / {@code result} 与
     * {@code ai_operation_proposal.request_payload} 的落库入口（SYS-A-09）：
     * 敏感键名 → 占位符；其它字符串值做模式兜底。</p>
     */
    public static Object maskDeep(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (isSensitiveField(key)) {
                    result.put(key, entry.getValue() == null ? null : CHANGED_PLACEHOLDER);
                } else {
                    result.put(key, maskDeep(entry.getValue()));
                }
            }
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(SensitiveFieldMasker::maskDeep).toList();
        }
        if (value instanceof String text) {
            return maskText(text);
        }
        return value;
    }
}
