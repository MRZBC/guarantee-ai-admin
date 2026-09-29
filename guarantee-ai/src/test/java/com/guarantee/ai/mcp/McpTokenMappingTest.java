package com.guarantee.ai.mcp;

import com.guarantee.ai.mcp.mapper.McpTokenMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
 * MCP 凭据的结构性守护（不连库）：
 *
 * <ol>
 *   <li>POJO ↔ Mapper XML ↔ V10 DDL 三者列名一致（少列静默丢数据、多列静默写错表）；</li>
 *   <li><b>明文不入库</b>：实体没有明文/Token 字段，Mapper 也没有任何返回明文的入口；</li>
 *   <li><b>逻辑删除房规</b>：三列定义与 {@code idx_ai_mcp_token_deleted} 必须完全符合
 *       逐列断言（否则 {@code LogicalDeleteSchemaIntegrationTest} 会红）；</li>
 *   <li><b>V10 未执行</b>：脚本里必须留着"先登记 MANAGED 再执行"的硬约束说明
 *       （本切片明确不落地到共享库）。</li>
 * </ol>
 */
class McpTokenMappingTest {

    private static final String MIGRATION_FILE = "V10__ai_mcp.sql";
    private static final String XML_RESOURCE = "mapper/mcp/McpTokenMapper.xml";

    /** POJO 的业务列（不含 DB 自增 id），与 V10 建表列一一对应。 */
    private static final Set<String> EXPECTED_COLUMNS = Set.of(
            "service_account_id", "token_prefix", "token_hash", "permissions",
            "expires_at", "last_used_at", "revoked_at", "revoked_by", "created_by", "created_at",
            "is_deleted", "deleted_at", "deleted_by");

    /** INSERT 语句写入的列（其余由 DB 默认值/后续 UPDATE 负责）。 */
    private static final Set<String> EXPECTED_INSERT_COLUMNS = Set.of(
            "service_account_id", "token_prefix", "token_hash", "permissions", "expires_at", "created_by");

    /** XML columns 片段比 POJO 多一个主键 id。 */
    private static Set<String> expectedXmlColumns() {
        Set<String> columns = new LinkedHashSet<>(EXPECTED_COLUMNS);
        columns.add("id");
        return columns;
    }

    // ==================================================================
    // 1. 三方列一致
    // ==================================================================

    @Test
    @DisplayName("POJO 字段（snake_case 后）与 V10 建表列一致：不多不少")
    void pojoFieldsMatchMigrationColumns() {
        Set<String> derived = new LinkedHashSet<>();
        for (Field field : McpToken.class.getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers()) || "id".equals(field.getName())) {
                continue;
            }
            derived.add(toSnakeCase(field.getName()));
        }
        assertThat(derived).containsExactlyInAnyOrderElementsOf(EXPECTED_COLUMNS);
    }

    @Test
    @DisplayName("Mapper XML：columns 片段覆盖全部列，INSERT 只写应写的那几列")
    void mapperXmlColumnsMatch() throws IOException {
        String xml = readClasspath(XML_RESOURCE);

        assertThat(columnsFragment(xml))
                .as("selectByHash/selectAll 的列清单必须覆盖全部业务列")
                .containsExactlyInAnyOrderElementsOf(expectedXmlColumns());

        Matcher insert = Pattern.compile("INSERT INTO ai_mcp_token\\s*\\((.*?)\\)\\s*VALUES", Pattern.DOTALL)
                .matcher(xml);
        assertThat(insert.find()).as("XML 里必须有 INSERT INTO ai_mcp_token ... VALUES").isTrue();
        assertThat(splitColumns(insert.group(1)))
                .as("INSERT 列清单（漏列会静默丢字段，多列会直接报 Unknown column）")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_INSERT_COLUMNS);
    }

    @Test
    @DisplayName("V10 建表语句包含 POJO 的每一列")
    void migrationContainsEveryColumn() throws IOException {
        String block = tableBlock(readMigration(), "ai_mcp_token");

        for (String column : EXPECTED_COLUMNS) {
            assertThat(block)
                    .as("ai_mcp_token 缺少列 %s（会静默丢数据）", column)
                    .containsPattern("(?m)^\\s*" + column + "\\s");
        }
        assertThat(block).containsPattern("(?m)^\\s*id\\s+BIGINT");
    }

    // ==================================================================
    // 2. 明文不入库
    // ==================================================================

    @Test
    @DisplayName("明文不入库：实体与列表视图都没有明文/Token 字段，Mapper 也没有读明文的入口")
    void plaintextNeverEntersPersistenceModel() {
        List<String> entityFields = Arrays.stream(McpToken.class.getDeclaredFields()).map(Field::getName).toList();
        assertThat(entityFields)
                .as("实体只能有哈希与前缀，不能有明文")
                .doesNotContain("plainToken", "plaintext", "token");

        List<String> viewFields = Arrays.stream(McpTokenView.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertThat(viewFields)
                .as("列表视图不得暴露哈希或明文")
                .doesNotContain("tokenHash", "plainToken", "token");

        List<String> mapperMethods = Arrays.stream(McpTokenMapper.class.getDeclaredMethods())
                .map(Method::getName).sorted().toList();
        assertThat(mapperMethods).containsExactly(
                "insert", "revoke", "revokeByServiceAccount", "selectAll", "selectByHash", "selectById",
                "touchLastUsed");
        for (String method : mapperMethods) {
            assertThat(method.toLowerCase(Locale.ROOT))
                    .as("Mapper 不得出现任何「取得明文」的入口：%s", method)
                    .doesNotContain("plain");
        }
    }

    // ==================================================================
    // 3. 房规（逐列断言口径）
    // ==================================================================

    @Test
    @DisplayName("房规：三列定义正确，且必须有名为 idx_ai_mcp_token_deleted 的 is_deleted 索引")
    void logicalDeleteHouseRules() throws IOException {
        String block = tableBlock(readMigration(), "ai_mcp_token");

        assertThat(block).containsPattern(
                "(?m)^\\s*is_deleted\\s+TINYINT\\s+NOT NULL DEFAULT 0");
        assertThat(block).containsPattern(
                "(?m)^\\s*deleted_at\\s+DATETIME\\(6\\)\\s+NULL(?!\\s+DEFAULT)");
        assertThat(block).containsPattern(
                "(?m)^\\s*deleted_by\\s+VARCHAR\\(64\\)\\s+NOT NULL DEFAULT 'DB'");
        assertThat(block)
                .as("受管表必须有 idx_<table>_deleted 索引（巡检按索引名统计）")
                .contains("idx_ai_mcp_token_deleted");
        assertThat(block)
                .as("哈希唯一键必须沿用 (业务键, IFNULL(deleted_at, 哨兵)) 形态，否则删除后无法重建")
                .containsIgnoringCase("ifnull(deleted_at");
    }

    @Test
    @DisplayName("V10 保留「先登记 MANAGED 再执行」的硬约束说明（本切片不落地到共享库）")
    void migrationDocumentsManagedRegistrationOrder() throws IOException {
        String ddl = readMigration();

        assertThat(ddl).contains("LogicalDeleteTables");
        assertThat(ddl).contains("MANAGED");
        assertThat(ddl)
                .as("必须写清顺序：先改 MANAGED，再执行脚本")
                .contains("先改 MANAGED");
        assertThat(ddl).contains("CREATE TABLE IF NOT EXISTS ai_mcp_token");
        assertThat(ddl).contains("account_type");
        assertThat(ddl)
                .as("MCP 无会话，ai_tool_call.conversation_id 的 NOT NULL 是待裁决项，必须留下痕迹")
                .contains("conversation_id")
                .contains("待裁决");
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private static Set<String> columnsFragment(String xml) {
        Matcher matcher = Pattern.compile("<sql id=\"columns\">(.*?)</sql>", Pattern.DOTALL).matcher(xml);
        assertThat(matcher.find()).as("XML 必须有 <sql id=\"columns\"> 片段").isTrue();
        return splitColumns(matcher.group(1));
    }

    private static Set<String> splitColumns(String raw) {
        Set<String> columns = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String column = part.trim().replaceAll("\\s+", " ");
            if (!column.isEmpty()) {
                columns.add(column);
            }
        }
        return columns;
    }

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
        try (InputStream stream = McpTokenMappingTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(stream).as("classpath 上找不到 %s", resource).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String readMigration() throws IOException {
        List<Path> candidates = new ArrayList<>();
        String basedir = System.getProperty("basedir");
        if (basedir != null && !basedir.isBlank()) {
            candidates.add(Path.of(basedir, "..", "guarantee-web", "src", "main", "resources", "db",
                    "migration", MIGRATION_FILE));
        }
        candidates.add(Path.of("..", "guarantee-web", "src", "main", "resources", "db", "migration", MIGRATION_FILE));
        candidates.add(Path.of("guarantee-web", "src", "main", "resources", "db", "migration", MIGRATION_FILE));
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return Files.readString(candidate.normalize(), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("找不到迁移文件 " + MIGRATION_FILE + "，候选路径：" + candidates);
    }
}
