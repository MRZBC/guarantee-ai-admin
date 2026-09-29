package com.guarantee.ai.knowledge;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识真源（Markdown + YAML front-matter）解析器。
 *
 * <p><b>只支持受控的 YAML 子集</b>，这是刻意的：真源里全部是扁平标量
 * （编号、域、标题、标签、日期、版本、状态、权限码、来源），没有嵌套、没有数组、
 * 没有多行标量，也没有块标量。为此引入一个 YAML 依赖并不划算——本模块的 pom
 * 也没有它的直接依赖（不能靠传递依赖干活）；而一个受控子集解析器能给出
 * **带行号的可读错误**，这正是知识维护者需要的。</p>
 *
 * <p>支持的语法：</p>
 * <ul>
 *   <li>文件必须以独立一行 {@code ---} 开头，以独立一行 {@code ---} 结束；</li>
 *   <li>字段行 {@code key: value}，值可为裸串、单引号串（{@code ''} 转义）、
 *       双引号串（{@code \\ \" \n \r \t} 转义）；</li>
 *   <li>整行注释（{@code # ...}）与空行忽略。<b>不支持行尾注释</b>——值里出现
 *       {@code #} 会被当作值的一部分（宁可让作者写引号，也不要静默截断内容）；</li>
 *   <li>未知字段**报错**而不是忽略：写错字段名（例如 {@code keyword}）必须当场发现，
 *       否则那条知识会以"字段静默缺失"的形态进库。</li>
 * </ul>
 *
 * <p>正文是 front-matter 之后的所有内容，按 {@code strip()} 规范化（见
 * {@link KnowledgeDocument}）。</p>
 */
public final class KnowledgeDocumentParser {

    /** front-matter 允许出现的字段（与 REQ §5.1.1 的条目模型一一对应）。 */
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "knowledge_no", "domain", "title", "keywords", "effective_from", "effective_to",
            "version", "status", "permission_code", "source_ref");

    /** 编号格式：{@code KB-<DOMAIN>-NNNN}（四位数字，人工固定）。 */
    private static final Pattern KNOWLEDGE_NO = Pattern.compile("^KB-([A-Z]+)-(\\d{4})$");

    /** 单条正文上限 2KB（REQ-RAG-01）；与检索时的字节预算同源。 */
    public static final int MAX_CONTENT_BYTES = 2048;

    /** 标题上限 60 字（REQ-RAG-01；库列宽 120 是冗余余量）。 */
    public static final int MAX_TITLE_CHARS = 60;

    /** 标签数量区间（REQ-RAG-01：5~15 个）。 */
    public static final int MIN_KEYWORDS = 5;
    public static final int MAX_KEYWORDS = 15;

    private KnowledgeDocumentParser() {
    }

    /**
     * 解析一个真源文件。
     *
     * @param rawText    文件全文
     * @param sourcePath 真源路径（仅用于错误定位与留痕）
     * @throws KnowledgeParseException 任何不合法之处，消息含文件与行号
     */
    public static KnowledgeDocument parse(String rawText, String sourcePath) {
        String path = (sourcePath == null || sourcePath.isBlank()) ? "(未知真源)" : sourcePath;
        if (rawText == null || rawText.isBlank()) {
            throw new KnowledgeParseException("知识真源 " + path + "：文件为空");
        }
        String text = rawText.replace("\r\n", "\n").replace('\r', '\n');
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        String[] lines = text.split("\n", -1);

        // ---- 1. front-matter 边界 ----
        if (lines.length == 0 || !"---".equals(lines[0].strip())) {
            throw new KnowledgeParseException(
                    "知识真源 " + path + " 第 1 行：必须以独立一行 --- 开头（YAML front-matter）");
        }
        int end = -1;
        for (int i = 1; i < lines.length; i++) {
            if ("---".equals(lines[i].strip())) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            throw new KnowledgeParseException(
                    "知识真源 " + path + "：front-matter 没有结束标记 ---");
        }

        // ---- 2. 字段 ----
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 1; i < end; i++) {
            String line = lines[i];
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                throw new KnowledgeParseException("知识真源 " + path + " 第 " + (i + 1)
                        + " 行：不是合法的 key: value 形式 —— " + trimmed);
            }
            String key = line.substring(0, colon).strip();
            if (!ALLOWED_KEYS.contains(key)) {
                throw new KnowledgeParseException("知识真源 " + path + " 第 " + (i + 1)
                        + " 行：未知字段 " + key + "（允许：" + String.join("/", ALLOWED_KEYS.stream().sorted().toList()) + "）");
            }
            if (fields.containsKey(key)) {
                throw new KnowledgeParseException("知识真源 " + path + " 第 " + (i + 1)
                        + " 行：字段 " + key + " 重复");
            }
            String value = parseScalar(line.substring(colon + 1), path, i + 1);
            fields.put(key, value);
        }

        // ---- 3. 正文 ----
        List<String> body = new ArrayList<>();
        for (int i = end + 1; i < lines.length; i++) {
            body.add(lines[i]);
        }
        String content = String.join("\n", body).strip();
        if (content.isEmpty()) {
            throw new KnowledgeParseException("知识真源 " + path + "：front-matter 之后没有正文");
        }

        // ---- 4. 校验与组装 ----
        String knowledgeNo = required(fields, "knowledge_no", path);
        Matcher matcher = KNOWLEDGE_NO.matcher(knowledgeNo);
        if (!matcher.matches()) {
            throw new KnowledgeParseException("知识真源 " + path + "：knowledge_no 必须是 "
                    + "KB-<DOMAIN>-NNNN（四位数字）形式，实际为 " + knowledgeNo);
        }
        KnowledgeDomain domain = KnowledgeDomain.fromCode(required(fields, "domain", path))
                .orElseThrow(() -> new KnowledgeParseException("知识真源 " + path + "：domain 取值必须是 "
                        + "ORDER/SYSTEM/CONCEPT/POLICY，实际为 " + fields.get("domain")));
        String prefixDomain = matcher.group(1);
        if (!prefixDomain.equals(domain.name())) {
            throw new KnowledgeParseException("知识真源 " + path + "：编号前缀 " + prefixDomain
                    + " 与 domain=" + domain.name() + " 不一致（编号前缀必须等于域）");
        }

        String title = required(fields, "title", path);
        if (title.length() > MAX_TITLE_CHARS) {
            throw new KnowledgeParseException("知识真源 " + path + "：title 超过 " + MAX_TITLE_CHARS
                    + " 字（实际 " + title.length() + " 字）");
        }

        int contentBytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (contentBytes > MAX_CONTENT_BYTES) {
            throw new KnowledgeParseException("知识真源 " + path + "：正文超过 "
                    + MAX_CONTENT_BYTES + " 字节（实际 " + contentBytes + " 字节）");
        }

        List<String> keywords = parseKeywords(required(fields, "keywords", path));
        if (keywords.size() < MIN_KEYWORDS || keywords.size() > MAX_KEYWORDS) {
            throw new KnowledgeParseException("知识真源 " + path + "：keywords 需要 "
                    + MIN_KEYWORDS + "~" + MAX_KEYWORDS + " 个（实际 " + keywords.size() + " 个）");
        }

        int version = parseInt(fields.get("version"), 1, "version", path);
        if (version < 1) {
            throw new KnowledgeParseException("知识真源 " + path + "：version 必须 ≥ 1，实际为 " + version);
        }

        KnowledgeStatus status = fields.get("status") == null
                ? KnowledgeStatus.PUBLISHED
                : KnowledgeStatus.fromCode(fields.get("status"))
                .orElseThrow(() -> new KnowledgeParseException("知识真源 " + path
                        + "：status 取值必须是 DRAFT/PUBLISHED/RETIRED，实际为 " + fields.get("status")));

        LocalDate effectiveFrom = parseDate(fields.get("effective_from"), "effective_from", path);
        LocalDate effectiveTo = parseDate(fields.get("effective_to"), "effective_to", path);
        if (effectiveFrom != null && effectiveTo != null && effectiveFrom.isAfter(effectiveTo)) {
            throw new KnowledgeParseException("知识真源 " + path + "：effective_from（" + effectiveFrom
                    + "）不能晚于 effective_to（" + effectiveTo + "）");
        }

        String permissionCode = limitLength(fields.get("permission_code"), 64, "permission_code", path);
        String sourceRef = limitLength(fields.get("source_ref"), 255, "source_ref", path);

        return new KnowledgeDocument(knowledgeNo, domain, title, content, keywords,
                effectiveFrom, effectiveTo, version, status, permissionCode, sourceRef, path);
    }

    /** 按 REQ-RAG-01 解析逗号分隔标签；中英文逗号、顿号、分号都当分隔符。 */
    private static List<String> parseKeywords(String raw) {
        Set<String> keywords = new LinkedHashSet<>();
        for (String part : raw.split("[,，、;；]")) {
            String tag = part.strip();
            if (!tag.isEmpty()) {
                keywords.add(tag);
            }
        }
        return List.copyOf(keywords);
    }

    private static String required(Map<String, String> fields, String key, String path) {
        String value = fields.get(key);
        if (value == null || value.isBlank()) {
            throw new KnowledgeParseException("知识真源 " + path + "：缺少必填字段 " + key);
        }
        return value.strip();
    }

    private static int parseInt(String raw, int defaultValue, String key, String path) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException ex) {
            throw new KnowledgeParseException("知识真源 " + path + "：" + key + " 必须是整数，实际为 " + raw);
        }
    }

    private static LocalDate parseDate(String raw, String key, String path) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.strip());
        } catch (DateTimeParseException ex) {
            throw new KnowledgeParseException("知识真源 " + path + "：" + key
                    + " 必须是 yyyy-MM-dd，实际为 " + raw);
        }
    }

    private static String limitLength(String value, int max, String key, String path) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.length() > max) {
            throw new KnowledgeParseException("知识真源 " + path + "：" + key + " 超过 " + max
                    + " 字符（实际 " + trimmed.length() + " 字符）");
        }
        return trimmed;
    }

    /**
     * 解析一个标量值：裸串 / 单引号 / 双引号。
     *
     * <p>空值（{@code key:}）返回 {@code null}，与"未提供"同义。</p>
     */
    static String parseScalar(String rawValue, String path, int lineNo) {
        String value = rawValue.strip();
        if (value.isEmpty()) {
            return null;
        }
        char first = value.charAt(0);
        if (value.length() >= 2 && (first == '\'' || first == '"')
                && value.charAt(value.length() - 1) == first) {
            String inner = value.substring(1, value.length() - 1);
            return first == '\'' ? inner.replace("''", "'") : unescapeDoubleQuoted(inner, path, lineNo);
        }
        if (first == '\'' || first == '"') {
            throw new KnowledgeParseException("知识真源 " + path + " 第 " + lineNo
                    + " 行：引号没有成对闭合");
        }
        return value;
    }

    private static String unescapeDoubleQuoted(String inner, String path, int lineNo) {
        StringBuilder sb = new StringBuilder(inner.length());
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (i + 1 >= inner.length()) {
                throw new KnowledgeParseException("知识真源 " + path + " 第 " + lineNo
                        + " 行：双引号字符串以转义符结尾");
            }
            char next = inner.charAt(++i);
            switch (next) {
                case '\\' -> sb.append('\\');
                case '"' -> sb.append('"');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                default -> throw new KnowledgeParseException("知识真源 " + path + " 第 " + lineNo
                        + " 行：不支持的转义 \\" + next);
            }
        }
        return sb.toString();
    }

    /** 供测试与调用方查询允许字段（避免测试里再拷一份清单）。 */
    public static Set<String> allowedKeys() {
        return ALLOWED_KEYS;
    }

    /** 宽松编号解析（供导入器/文档工具使用），失败返回空。 */
    public static Optional<KnowledgeDomain> domainOfNumber(String knowledgeNo) {
        if (knowledgeNo == null) {
            return Optional.empty();
        }
        Matcher matcher = KNOWLEDGE_NO.matcher(knowledgeNo.strip());
        return matcher.matches() ? KnowledgeDomain.fromCode(matcher.group(1)) : Optional.empty();
    }
}
