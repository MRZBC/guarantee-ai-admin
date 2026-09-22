package com.guarantee.system.mybatis;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 逻辑删除 SQL 改写器（设计文档 §5.2）。
 *
 * <p>把 {@code SELECT} 语句改写为"只见未删除行"：为受管表注入
 * {@code 别名.is_deleted = 0}。</p>
 *
 * <h2>实现边界（重要，与设计文档 §5.2 的"只处理最外层 FROM/JOIN"一致）</h2>
 * <ul>
 *   <li>只解析**最外层**（括号深度 0）的 {@code FROM} / {@code JOIN}。派生表
 *       （{@code FROM (…UNION ALL…) x}）、子查询、递归 CTE 内部的表**不注入**——
 *       它们由各自的 SQL 显式负责（设计 §5.2 风险表 + 任务书 §4.1）。
 *       分析模块的聚合 SQL 因此全部改为手工显式过滤。</li>
 *   <li>{@code FROM} 表与无 {@code ON} 的连接条件注入到 {@code WHERE} 末尾；
 *       带 {@code ON} 的 {@code JOIN} 注入到该 {@code ON} 子句末尾，
 *       以保持 {@code LEFT JOIN} 的外连接语义（否则会把左表行过滤掉）。</li>
 *   <li>语句中已显式出现 {@code is_deleted} **条件**时整句跳过（避免重复条件）。
 *      列投影 {@code is_deleted AS isDeleted} 不算条件：先剔除投影再判断，
 *       这样"VO 需要返回删除标记"与"拦截器仍要兜底过滤"可以同时成立。</li>
 *   <li>解析不出任何受管表时原样返回（例如 {@code SELECT 1}、纯审计表查询）。</li>
 * </ul>
 *
 * <p>纯函数，无状态，便于单元测试（LD-T14）。</p>
 */
final class LogicalDeleteSqlRewriter {

    private static final Pattern IS_DELETED = Pattern.compile("(?i)\\bis_deleted\\b");
    private static final Pattern IS_DELETED_PROJECTION =
            Pattern.compile("(?i)\\bis_deleted\\s+as\\s+[`A-Za-z0-9_]+");

    /** 出现在这些关键字之前时，WHERE 子句结束（需要把注入点插到它前面）。 */
    private static final Set<String> CLAUSE_ENDERS = Set.of(
            "HAVING", "LIMIT", "UNION", "FOR", "WINDOW", "PROCEDURE", "INTO", "OFFSET", "FETCH");
    /** 需要与 BY 组合才成立的子句关键字（避免把别名 GROUP/ORDER 误判为子句）。 */
    private static final Set<String> CLAUSE_ENDERS_WITH_BY = Set.of("GROUP", "ORDER");
    /** 连接关键字：出现在 ON 子句之后说明该 ON 子句结束。 */
    private static final Set<String> JOIN_KEYWORDS = Set.of(
            "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "CROSS", "STRAIGHT_JOIN", "NATURAL");
    /** 不能作为表别名的关键字。 */
    private static final Set<String> NOT_ALIAS = Set.of(
            "ON", "USING", "WHERE", "GROUP", "ORDER", "HAVING", "LIMIT", "UNION", "FOR", "WINDOW",
            "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "CROSS", "STRAIGHT_JOIN", "NATURAL",
            "AS", "SET", "VALUES", "AND", "OR", "NOT", "IS", "IN", "EXISTS", "SELECT", "FROM",
            "PARTITION", "PROCEDURE", "INTO", "BY", "ASC", "DESC", "LOCK", "SHARE", "MODE");

    private LogicalDeleteSqlRewriter() {
    }

    /**
     * 改写一条 SELECT。
     *
     * @return 注入后的 SQL；无需注入时返回原字符串（同一个实例，便于调用方比较）
     */
    static String rewrite(String sql) {
        if (sql == null || sql.isBlank()) {
            return sql;
        }
        String masked = mask(sql);
        if (hasExplicitIsDeletedCondition(masked)) {
            return sql;
        }
        List<Token> tokens = tokenize(masked);
        if (tokens.isEmpty()) {
            return sql;
        }

        List<Insertion> insertions = new ArrayList<>();
        // 需要注入到 WHERE 的限定名（别名，或未起别名时的表名）
        LinkedHashSet<String> whereQualifiers = new LinkedHashSet<>();

        for (int i = 0; i < tokens.size(); i++) {
            Token trigger = tokens.get(i);
            if (!trigger.word) {
                continue;
            }
            String upper = trigger.upper();
            if (!"FROM".equals(upper) && !"JOIN".equals(upper)) {
                continue;
            }
            boolean fromClause = "FROM".equals(upper);
            int cursor = i + 1;
            while (cursor < tokens.size()) {
                TableRef ref = parseTableRef(tokens, cursor);
                if (ref == null) {
                    break;
                }
                cursor = ref.nextIndex;
                if (LogicalDeleteTables.isManaged(ref.table)) {
                    String qualifier = ref.qualifier();
                    if (!fromClause) {
                        int onEnd = findOnClauseEnd(tokens, cursor, sql.length());
                        if (onEnd > 0) {
                            // 末尾保留空格：插入点是下一个关键字（如 LEFT/WHERE）的起始下标
                            insertions.add(Insertion.on(onEnd,
                                    " AND " + qualifier + ".is_deleted = 0 "));
                        } else {
                            whereQualifiers.add(qualifier);
                        }
                    } else {
                        whereQualifiers.add(qualifier);
                    }
                }
                // 逗号连接的多表 FROM：继续解析下一个表；否则结束本子句
                if (cursor < tokens.size() && ",".equals(tokens.get(cursor).text)) {
                    cursor++;
                    continue;
                }
                break;
            }
        }

        if (whereQualifiers.isEmpty() && insertions.isEmpty()) {
            return sql;
        }

        // 顶层 UNION：注入点只能落到第一个分支，会造成"另一个分支仍可见已删除数据"的静默泄漏。
        // 因此明确失败，要求该语句显式写 is_deleted = 0（符合"解析不确定就报错，绝不放行"）。
        boolean hasTopLevelUnion = tokens.stream()
                .anyMatch(t -> t.word && "UNION".equals(t.upper()));
        if (hasTopLevelUnion) {
            throw new IllegalStateException(
                    "逻辑删除过滤不支持顶层 UNION 语句的自动注入，请在该语句中显式写 is_deleted = 0："
                            + describe(sql));
        }

        if (!whereQualifiers.isEmpty()) {
            StringBuilder condition = new StringBuilder();
            for (String qualifier : whereQualifiers) {
                if (condition.length() > 0) {
                    condition.append(" AND ");
                }
                condition.append(qualifier).append(".is_deleted = 0");
            }
            int whereIndex = indexOfWord(tokens, "WHERE", 0);
            if (whereIndex >= 0) {
                int end = findWhereClauseEnd(tokens, whereIndex + 1, sql.length());
                insertions.add(Insertion.where(end, " AND " + condition + " "));
            } else {
                // 没有 WHERE：注入点必须在 FROM/JOIN **之后**、下一个子句之前。
                // 注意不能复用 findWhereClauseEnd——它会在连接关键字处停下，
                // 那会把 "WHERE x.is_deleted = 0" 插到第一个 LEFT JOIN 之前，产生语法错误。
                int end = findNoWhereInjectionPoint(tokens, sql.length());
                insertions.add(Insertion.where(end, " WHERE " + condition + " "));
            }
        }

        // 位置从后往前插入，避免前面的插入影响后面的偏移。
        // 同一位置时（ON 子句一直延续到语句末尾，且该语句还需要 WHERE 注入）先插 WHERE 注入，
        // 这样 ON 注入落在 WHERE 之前，仍然属于 ON 子句，不会把 LEFT JOIN 退化成 INNER JOIN。
        insertions.sort((a, b) -> a.position != b.position
                ? Integer.compare(b.position, a.position)
                : Integer.compare(a.priority, b.priority));
        StringBuilder out = new StringBuilder(sql);
        for (Insertion insertion : insertions) {
            out.insert(insertion.position, insertion.text);
        }
        return out.toString();
    }

    /** SQL 中是否已显式带 is_deleted 条件（列投影不算）。 */
    static boolean hasExplicitIsDeletedCondition(String sql) {
        String withoutProjections = IS_DELETED_PROJECTION.matcher(sql).replaceAll(" ");
        return IS_DELETED.matcher(withoutProjections).find();
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /** 解析 {@code FROM/JOIN} 之后的 "表 [AS] [别名]"。 */
    private static TableRef parseTableRef(List<Token> tokens, int idx) {
        if (idx >= tokens.size()) {
            return null;
        }
        Token tableToken = tokens.get(idx);
        if (!tableToken.word) {
            // 形如 FROM ( … ) 派生表 / 子查询：不处理（由该 SQL 自身负责）
            return null;
        }
        String table = unquote(tableToken.text);
        int cursor = idx + 1;
        String alias = table;
        if (cursor < tokens.size()) {
            Token next = tokens.get(cursor);
            if (next.word && "AS".equals(next.upper()) && cursor + 1 < tokens.size()
                    && tokens.get(cursor + 1).word) {
                alias = unquote(tokens.get(cursor + 1).text);
                cursor += 2;
            } else if (next.word && !NOT_ALIAS.contains(next.upper())) {
                alias = unquote(next.text);
                cursor += 1;
            }
        }
        return new TableRef(table, alias, cursor);
    }

    /**
     * 找 ON 子句的结束位置；返回 -1 表示该 JOIN 没有 ON（例如 USING / NATURAL / CROSS）。
     * ON 子句一直延续到语句末尾时返回语句末尾（此时注入点与 WHERE 注入点重合，两者都合法）。
     */
    private static int findOnClauseEnd(List<Token> tokens, int fromIndex, int sqlLength) {
        int onIndex = -1;
        for (int i = fromIndex; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (!t.word) {
                continue;
            }
            String upper = t.upper();
            if ("ON".equals(upper)) {
                onIndex = i;
                break;
            }
            if ("USING".equals(upper) || "WHERE".equals(upper) || isJoinKeyword(upper)
                    || isClauseEnder(tokens, i)) {
                return -1;
            }
        }
        if (onIndex < 0) {
            return -1;
        }
        for (int i = onIndex + 1; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (!t.word) {
                continue;
            }
            String upper = t.upper();
            if (isJoinKeyword(upper) || "WHERE".equals(upper) || isClauseEnder(tokens, i)) {
                return t.start;
            }
        }
        return trimSqlEnd(sqlLength, tokens);
    }

    /** 从 fromIndex 起找 WHERE 子句结束位置（下一个顶层子句关键字或语句末尾）。 */
    private static int findWhereClauseEnd(List<Token> tokens, int fromIndex, int sqlLength) {
        for (int i = fromIndex; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (!t.word) {
                continue;
            }
            if ("UNION".equals(t.upper()) || isClauseEnder(tokens, i) || "WHERE".equals(t.upper())) {
                return t.start;
            }
        }
        return trimSqlEnd(sqlLength, tokens);
    }

    /** 无 WHERE 时的注入点：FROM/JOIN 之后、下一个顶层子句（GROUP/ORDER/LIMIT/…）之前。 */
    private static int findNoWhereInjectionPoint(List<Token> tokens, int sqlLength) {
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (!t.word) {
                continue;
            }
            if ("UNION".equals(t.upper()) || isClauseEnder(tokens, i)) {
                return t.start;
            }
        }
        return trimSqlEnd(sqlLength, tokens);
    }

    private static int trimSqlEnd(int sqlLength, List<Token> tokens) {
        if (tokens.isEmpty()) {
            return sqlLength;
        }
        Token last = tokens.get(tokens.size() - 1);
        int end = last.end;
        return Math.min(end, sqlLength);
    }

    private static boolean isClauseEnder(List<Token> tokens, int index) {
        String upper = tokens.get(index).upper();
        if (CLAUSE_ENDERS.contains(upper)) {
            return true;
        }
        if (CLAUSE_ENDERS_WITH_BY.contains(upper)) {
            for (int i = index + 1; i < tokens.size(); i++) {
                Token next = tokens.get(i);
                if (!next.word) {
                    continue;
                }
                return "BY".equals(next.upper());
            }
        }
        return false;
    }

    private static boolean isJoinKeyword(String upper) {
        return JOIN_KEYWORDS.contains(upper);
    }

    private static int indexOfWord(List<Token> tokens, String word, int fromIndex) {
        for (int i = fromIndex; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.word && word.equals(t.upper())) {
                return i;
            }
        }
        return -1;
    }

    private static String unquote(String text) {
        if (text.length() >= 2 && text.charAt(0) == '`' && text.charAt(text.length() - 1) == '`') {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    // ------------------------------------------------------------------
    // 词法与掩码
    // ------------------------------------------------------------------

    /**
     * 把字符串字面量与注释替换为空格（保持下标不变），
     * 这样后续扫描不会把字面量里的 "from"/"is_deleted" 当成语法。
     */
    private static String mask(String sql) {
        char[] chars = sql.toCharArray();
        int i = 0;
        int n = chars.length;
        while (i < n) {
            char c = chars[i];
            if (c == '\'') {
                i = maskQuoted(chars, i, '\'');
            } else if (c == '"') {
                i = maskQuoted(chars, i, '"');
            } else if (c == '-' && i + 1 < n && chars[i + 1] == '-') {
                while (i < n && chars[i] != '\n') {
                    chars[i++] = ' ';
                }
            } else if (c == '#') {
                while (i < n && chars[i] != '\n') {
                    chars[i++] = ' ';
                }
            } else if (c == '/' && i + 1 < n && chars[i + 1] == '*') {
                chars[i++] = ' ';
                chars[i++] = ' ';
                while (i < n && !(chars[i] == '*' && i + 1 < n && chars[i + 1] == '/')) {
                    chars[i++] = ' ';
                }
                if (i < n) {
                    chars[i++] = ' ';
                }
                if (i < n) {
                    chars[i++] = ' ';
                }
            } else {
                i++;
            }
        }
        return new String(chars);
    }

    private static int maskQuoted(char[] chars, int start, char quote) {
        int i = start + 1;
        int n = chars.length;
        chars[start] = ' ';
        while (i < n) {
            char c = chars[i];
            if (c == '\\' && i + 1 < n) {
                chars[i] = ' ';
                chars[i + 1] = ' ';
                i += 2;
                continue;
            }
            if (c == quote) {
                if (i + 1 < n && chars[i + 1] == quote) {
                    chars[i] = ' ';
                    chars[i + 1] = ' ';
                    i += 2;
                    continue;
                }
                chars[i] = ' ';
                return i + 1;
            }
            chars[i] = ' ';
            i++;
        }
        return i;
    }

    /** 取出括号深度为 0 的词/标点，并记录下标位置。 */
    private static List<Token> tokenize(String masked) {
        List<Token> tokens = new ArrayList<>();
        int depth = 0;
        int i = 0;
        int n = masked.length();
        while (i < n) {
            char c = masked.charAt(i);
            if (c == '(') {
                if (depth == 0) {
                    tokens.add(Token.punct("(", i));
                }
                depth++;
                i++;
                continue;
            }
            if (c == ')') {
                depth = Math.max(0, depth - 1);
                if (depth == 0) {
                    tokens.add(Token.punct(")", i));
                }
                i++;
                continue;
            }
            if (depth > 0) {
                i++;
                continue;
            }
            if (Character.isLetter(c) || c == '_' || c == '`') {
                int start = i;
                if (c == '`') {
                    i++;
                    while (i < n && masked.charAt(i) != '`') {
                        i++;
                    }
                    if (i < n) {
                        i++;
                    }
                } else {
                    while (i < n && (Character.isLetterOrDigit(masked.charAt(i))
                            || masked.charAt(i) == '_' || masked.charAt(i) == '$')) {
                        i++;
                    }
                }
                tokens.add(Token.word(masked.substring(start, i), start, i));
                continue;
            }
            if (!Character.isWhitespace(c)) {
                tokens.add(Token.punct(String.valueOf(c), i));
            }
            i++;
        }
        return tokens;
    }

    // ------------------------------------------------------------------

    private record Insertion(int position, String text, int priority) {
        /** ON 子句注入（priority=1）：同一位置时需要排在 WHERE 注入之后应用，从而落在前面。 */
        static Insertion on(int position, String text) {
            return new Insertion(position, text, 1);
        }

        /** WHERE 注入（priority=0）。 */
        static Insertion where(int position, String text) {
            return new Insertion(position, text, 0);
        }
    }

    private record TableRef(String table, String alias, int nextIndex) {
        String qualifier() {
            return alias == null || alias.isEmpty() ? table : alias;
        }
    }

    private record Token(String text, int start, int end, boolean word) {
        static Token word(String text, int start, int end) {
            return new Token(text, start, end, true);
        }

        static Token punct(String text, int start) {
            return new Token(text, start, start + text.length(), false);
        }

        String upper() {
            return text.toUpperCase(Locale.ROOT);
        }
    }

    /** 供诊断日志使用：把掩码后的 SQL 还原成可读形式（本项目未使用，保留给调试）。 */
    static String describe(String sql) {
        return sql == null ? "null" : sql.replaceAll("\\s+", " ");
    }

    /** 暴露给测试：判断一个 SQL 是否需要注入（不含 I/O）。 */
    static boolean needsInjection(String sql) {
        String masked = mask(sql);
        if (hasExplicitIsDeletedCondition(masked)) {
            return false;
        }
        return !rewrite(sql).equals(sql);
    }

    /** 便于测试断言：返回被注入的限定名集合。 */
    static Set<String> injectedQualifiers(String sql) {
        Matcher matcher = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\.is_deleted = 0")
                .matcher(rewrite(sql));
        Set<String> found = new LinkedHashSet<>();
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }
}
