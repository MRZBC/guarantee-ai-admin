package com.guarantee.ai.metrics;

import com.guarantee.ai.metrics.mapper.AiTurnMetricMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 观测切片的结构性守护（不连库）：
 *
 * <ol>
 *   <li><b>POJO ↔ 建表列 ↔ Mapper XML</b> 三者必须一一对应 —— 少了列是静默丢数据，
 *       多了列是静默写错表，两者都不会在"跑得通"的集成测试里立刻暴露；</li>
 *   <li><b>只追加</b>：Mapper 接口不得出现 update/delete 方法（指标是流水）；</li>
 *   <li><b>房规</b>：{@code ai_turn_metric} 是只追加流水表，**不得**带逻辑删除三列；
 *       聚合 {@code ai_tool_call} / {@code ai_operation_proposal} 时必须过滤 {@code is_deleted = 0}
 *       （带逻辑删除的表不过滤 = 把已删数据算进指标）。</li>
 * </ol>
 */
class AiTurnMetricMappingTest {

    private static final String MIGRATION_FILE = "V9__ai_observability.sql";
    private static final String XML_RESOURCE = "mapper/metrics/AiTurnMetricMapper.xml";

    /** 与 V9 建表语句一一对应的插入列（不含 DB 自增 id 与 DB 默认 created_at）。 */
    private static final Set<String> EXPECTED_INSERT_COLUMNS = Set.of(
            "conversation_id", "message_id", "user_id", "model", "prompt_version",
            "rounds", "tool_calls", "tool_cost_ms", "total_cost_ms",
            "input_tokens", "output_tokens", "capped", "cap_reason",
            "source", "outcome", "trace_id");

    /** 实体里承载 DB 生成值/非插入列的两个字段。 */
    private static final Set<String> NON_INSERT_FIELDS = Set.of("id", "createdAt");

    // ==================================================================
    // 1. 字段映射
    // ==================================================================

    @Test
    @DisplayName("POJO 字段（snake_case 后）与插入列清单完全一致：不多不少不漂移")
    void pojoFieldsMustMatchInsertColumns() {
        Set<String> derived = new LinkedHashSet<>();
        for (Field field : AiTurnMetric.class.getDeclaredFields()) {
            if (field.isSynthetic() || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (NON_INSERT_FIELDS.contains(field.getName())) {
                continue;
            }
            derived.add(toSnakeCase(field.getName()));
        }
        assertThat(derived).containsExactlyInAnyOrderElementsOf(EXPECTED_INSERT_COLUMNS);
    }

    @Test
    @DisplayName("Mapper XML 的 INSERT 列清单与 POJO 字段一致")
    void mapperXmlInsertMustMatchPojoColumns() throws IOException {
        String xml = readClasspath(XML_RESOURCE);
        Matcher matcher = Pattern.compile("INSERT INTO ai_turn_metric\\s*\\((.*?)\\)\\s*VALUES", Pattern.DOTALL)
                .matcher(xml);
        assertThat(matcher.find()).as("XML 里必须有一个 INSERT INTO ai_turn_metric ... VALUES").isTrue();

        Set<String> columns = new LinkedHashSet<>();
        for (String raw : matcher.group(1).split(",")) {
            String column = raw.trim();
            if (!column.isEmpty()) {
                columns.add(column);
            }
        }
        assertThat(columns).containsExactlyInAnyOrderElementsOf(EXPECTED_INSERT_COLUMNS);
    }

    @Test
    @DisplayName("聚合查询：ai_turn_metric 不带逻辑删除列（不过滤）；两个带逻辑删除的表必须过滤")
    void aggregateQueriesMustFilterLogicalDelete() throws IOException {
        String xml = readClasspath(XML_RESOURCE);

        // ai_turn_metric 没有 is_deleted 列：查询里出现它就会直接报 Unknown column
        for (String fragment : fragmentsFrom(xml, "FROM ai_turn_metric")) {
            assertThat(fragment).as("ai_turn_metric 是只追加流水表，不带 is_deleted").doesNotContain("is_deleted");
        }

        // ai_tool_call / ai_operation_proposal 带逻辑删除：必须过滤，否则把已删数据算进指标
        assertThat(fragmentsFrom(xml, "FROM ai_tool_call")).isNotEmpty();
        assertThat(fragmentsFrom(xml, "FROM ai_tool_call")).allSatisfy(
                fragment -> assertThat(fragment).contains("is_deleted = 0"));
        assertThat(fragmentsFrom(xml, "FROM ai_operation_proposal")).isNotEmpty();
        assertThat(fragmentsFrom(xml, "FROM ai_operation_proposal")).allSatisfy(
                fragment -> assertThat(fragment).contains("is_deleted = 0"));
    }

    // ==================================================================
    // 2. 只追加
    // ==================================================================

    @Test
    @DisplayName("Mapper 只追加：不得出现 update / delete / remove 方法")
    void mapperMustBeAppendOnly() {
        List<String> methods = Arrays.stream(AiTurnMetricMapper.class.getDeclaredMethods())
                .map(Method::getName)
                .toList();

        assertThat(methods).contains("insert", "selectOverview", "selectDailyTrend", "selectTopTools",
                "selectProposalStatusCounts");
        assertThat(methods).as("指标是流水，不能改也不能删")
                .noneMatch(name -> name.startsWith("update") || name.startsWith("delete") || name.startsWith("remove"));
    }

    // ==================================================================
    // 3. V9 DDL
    // ==================================================================

    @Test
    @DisplayName("V9 建表列覆盖 POJO 全部字段，且不带逻辑删除三列")
    void v9DdlMustMatchPojoColumns() throws IOException {
        String ddl = Files.readString(migrationPath(), StandardCharsets.UTF_8);

        assertThat(ddl).contains("CREATE TABLE IF NOT EXISTS ai_turn_metric");
        String block = tableBlock(ddl, "ai_turn_metric");

        for (String column : EXPECTED_INSERT_COLUMNS) {
            assertThat(block)
                    .as("ai_turn_metric 缺少列 %s（会静默丢数据）", column)
                    .containsPattern("(?m)^\\s*" + column + "\\s");
        }
        assertThat(block).as("id 主键必须在").containsPattern("(?m)^\\s*id\\s+BIGINT");
        assertThat(block).as("只追加流水表不得带逻辑删除三列（房规）")
                .doesNotContain("is_deleted")
                .doesNotContain("deleted_at")
                .doesNotContain("deleted_by");
        assertThat(block).as("REQ-MCP-09 要求的三条索引")
                .contains("idx_ai_turn_metric_created")
                .contains("idx_ai_turn_metric_user")
                .contains("idx_ai_turn_metric_model");
    }

    @Test
    @DisplayName("V9 给 ai_tool_call 加 source 列与索引，且是幂等写法（information_schema + PREPARE）")
    void v9DdlMustAddToolCallSourceIdempotently() throws IOException {
        String ddl = Files.readString(migrationPath(), StandardCharsets.UTF_8);

        assertThat(ddl).contains("ADD COLUMN source VARCHAR(8) NOT NULL DEFAULT ''CHAT''");
        assertThat(ddl).contains("idx_ai_tool_call_source");
        // MySQL 8 不支持 ADD COLUMN IF NOT EXISTS：必须走 information_schema 判断 + 动态执行
        assertThat(ddl).contains("information_schema.COLUMNS");
        assertThat(ddl).contains("information_schema.STATISTICS");
        assertThat(ddl).contains("PREPARE");
        assertThat(ddl).as("全新库走 CREATE TABLE IF NOT EXISTS，因此重复执行无副作用")
                .contains("CREATE TABLE IF NOT EXISTS");
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 取出某个 FROM 子句到所在 select 结束之间的片段（用于检查 where 条件）。 */
    private static List<String> fragmentsFrom(String xml, String fromClause) {
        List<String> fragments = new ArrayList<>();
        int index = 0;
        while ((index = xml.indexOf(fromClause, index)) >= 0) {
            int end = xml.indexOf("</select>", index);
            fragments.add(end < 0 ? xml.substring(index) : xml.substring(index, end));
            index += fromClause.length();
        }
        return fragments;
    }

    /** 截取某张表的 CREATE TABLE 语句（到第一个分号）。 */
    private static String tableBlock(String ddl, String table) {
        int start = ddl.indexOf("CREATE TABLE IF NOT EXISTS " + table);
        assertThat(start).as("找不到 %s 的建表语句", table).isNotNegative();
        int end = ddl.indexOf(';', start);
        return end < 0 ? ddl.substring(start) : ddl.substring(start, end);
    }

    private static String toSnakeCase(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private static String readClasspath(String resource) throws IOException {
        try (InputStream stream = AiTurnMetricMappingTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(stream).as("classpath 上找不到 %s", resource).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 定位 V9 迁移文件：surefire 的工作目录是模块目录（guarantee-ai），
     * 但 IDE 里可能是仓库根，因此给几个候选路径而不是写死一个。
     */
    private static Path migrationPath() {
        List<Path> candidates = new ArrayList<>();
        String basedir = System.getProperty("basedir");
        if (basedir != null && !basedir.isBlank()) {
            candidates.add(Path.of(basedir, "..", "guarantee-web", "src", "main", "resources", "db", "migration", MIGRATION_FILE));
        }
        candidates.add(Path.of("..", "guarantee-web", "src", "main", "resources", "db", "migration", MIGRATION_FILE));
        candidates.add(Path.of("guarantee-web", "src", "main", "resources", "db", "migration", MIGRATION_FILE));
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate.normalize();
            }
        }
        throw new AssertionError("找不到迁移文件 " + MIGRATION_FILE + "，候选路径：" + candidates);
    }
}
