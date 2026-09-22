package com.guarantee.system.mybatis;

import com.guarantee.system.mapper.SysUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 逻辑删除的**数据库结构与唯一键**集成测试。
 *
 * <p>对应设计文档 §11：LD-T2（13 键 × 5 轮删建循环）、LD-T2a（唯一键定义）、
 * LD-T8a（业务唯一性的真实边界）、LD-T17（deleted_by 三态）、LD-T18（列定义断言）、
 * LD-T19（一致性巡检）、LD-T20（直连 SQL 模板有效性）、LD-T21（secret 表不参与）、
 * LD-T22（库中不存在触发器/存储过程/函数）。</p>
 *
 * <p><b>为什么必须是集成测试</b>：断言对象就是 MySQL 自己的行为（唯一索引对 NULL 的处理、
 * DATETIME(6) 的精度、information_schema 元数据），内存库或 mock 无法替代
 * ——设计 §2.2 的实测证据正是因此而来。</p>
 *
 * <p><b>测试数据纪律</b>：所有夹具业务键以 {@code __ldt} 开头，{@code @AfterEach} 物理清理
 * （它们是测试自建数据，不是业务数据），以免影响"300 用户 / 21 机构"这类既有断言。</p>
 */
@SpringBootTest(classes = com.guarantee.system.ItMybatisConfig.class)
@TestPropertySource(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/schema.sql"
})
class LogicalDeleteSchemaIntegrationTest {

    private static final String P = "__ldt";

    private static final List<String> TABLES = List.of(
            "sys_org", "sys_department", "sys_user", "sys_role", "sys_permission",
            "sys_user_role", "sys_role_permission", "insurance_type", "enterprise", "project",
            "tender_order", "performance_order", "ai_conversation", "ai_message", "ai_tool_call",
            "ai_audit_log", "ai_operation_proposal", "ai_operation_audit");

    private static final List<String[]> UNIQUE_KEYS = List.of(
            new String[]{"sys_user", "uk_sys_user_username", "username"},
            new String[]{"sys_org", "uk_sys_org_code", "org_code"},
            new String[]{"sys_department", "uk_sys_dept_code", "dept_code"},
            new String[]{"sys_role", "uk_sys_role_code", "role_code"},
            new String[]{"sys_permission", "uk_sys_perm_code", "perm_code"},
            new String[]{"insurance_type", "uk_insurance_type_code", "type_code"},
            new String[]{"enterprise", "uk_enterprise_code", "ent_code"},
            new String[]{"enterprise", "uk_enterprise_credit", "credit_code"},
            new String[]{"project", "uk_project_code", "project_code"},
            new String[]{"tender_order", "uk_tender_order_no", "order_no"},
            new String[]{"performance_order", "uk_perf_order_no", "order_no"},
            new String[]{"ai_conversation", "uk_ai_conv_no", "conversation_no"},
            new String[]{"ai_operation_proposal", "uk_proposal_no", "proposal_no"});

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SysUserMapper sysUserMapper;

    @AfterEach
    void cleanupFixtures() {
        jdbc.update("DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE username LIKE ?)", P + "%");
        jdbc.update("DELETE FROM sys_user WHERE username LIKE ?", P + "%");
        jdbc.update("DELETE FROM sys_role WHERE role_code LIKE ?", P + "%");
        jdbc.update("DELETE FROM sys_department WHERE dept_code LIKE ?", P + "%");
        jdbc.update("DELETE FROM sys_org WHERE org_code LIKE ?", P + "%");
        jdbc.update("DELETE FROM insurance_type WHERE type_code LIKE ?", P + "%");
        jdbc.update("DELETE FROM enterprise WHERE ent_code LIKE ?", P + "%");
        jdbc.update("DELETE FROM project WHERE project_code LIKE ?", P + "%");
        jdbc.update("DELETE FROM tender_order WHERE order_no LIKE ?", P + "%");
        jdbc.update("DELETE FROM performance_order WHERE order_no LIKE ?", P + "%");
        jdbc.update("DELETE FROM ai_conversation WHERE conversation_no LIKE ?", P + "%");
        jdbc.update("DELETE FROM ai_operation_proposal WHERE proposal_no LIKE ?", P + "%");
        jdbc.update("DELETE FROM sys_permission WHERE perm_code LIKE ?", P + "%");
    }

    // ==================================================================
    // LD-T18：列定义断言（防止误加 CURRENT_TIMESTAMP 默认值）
    // ==================================================================

    @Test
    @DisplayName("LD-T18 18 张表的 deleted_at 必须是 DATETIME(6) 且 DEFAULT NULL")
    void deletedAtMustBeMicrosecondAndNullable() {
        for (String table : TABLES) {
            Map<String, Object> col = jdbc.queryForMap("""
                    SELECT COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, DATETIME_PRECISION
                    FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = 'deleted_at'
                    """, table);
            assertThat(col.get("COLUMN_TYPE")).as("%s.deleted_at 必须微秒精度", table).isEqualTo("datetime(6)");
            assertThat(((Number) col.get("DATETIME_PRECISION")).intValue()).as("%s 精度必须为 6", table).isEqualTo(6);
            assertThat(col.get("IS_NULLABLE")).as("%s.deleted_at 必须可空", table).isEqualTo("YES");
            // 核心断言：一旦加了 CURRENT_TIMESTAMP(6) 默认值，有效行的 deleted_at 会非 NULL，
            // 从而绕过 (业务键, deleted_at) 唯一键，使多个同名有效账号共存（设计 §2.1b）
            assertThat(col.get("COLUMN_DEFAULT")).as("%s.deleted_at 必须 DEFAULT NULL（禁止默认值）", table).isNull();
        }
    }

    @Test
    @DisplayName("LD-T18 18 张表的 is_deleted / deleted_by 定义正确，且都有 is_deleted 索引")
    void deletedFlagAndOperatorColumns() {
        for (String table : TABLES) {
            Map<String, Object> flag = jdbc.queryForMap("""
                    SELECT COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
                    FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = 'is_deleted'
                    """, table);
            assertThat(flag.get("COLUMN_TYPE")).isEqualTo("tinyint");
            assertThat(flag.get("IS_NULLABLE")).isEqualTo("NO");
            assertThat(String.valueOf(flag.get("COLUMN_DEFAULT"))).isEqualTo("0");

            Map<String, Object> by = jdbc.queryForMap("""
                    SELECT COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
                    FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = 'deleted_by'
                    """, table);
            assertThat(by.get("COLUMN_TYPE")).as("%s.deleted_by 必须是 VARCHAR(64)（不是 BIGINT）", table)
                    .isEqualTo("varchar(64)");
            assertThat(by.get("IS_NULLABLE")).isEqualTo("NO");
            assertThat(String.valueOf(by.get("COLUMN_DEFAULT"))).isEqualTo("DB");
        }

        Integer count = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT TABLE_NAME) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME = 'is_deleted'
                """, Integer.class);
        assertThat(count).as("受管表必须恰好 18 张（新增表需同步 LogicalDeleteTables）").isEqualTo(18);

        Integer idx = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT TABLE_NAME) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND INDEX_NAME LIKE 'idx\\_%\\_deleted'
                """, Integer.class);
        assertThat(idx).as("每张受管表都要有 is_deleted 索引").isEqualTo(18);
    }

    // ==================================================================
    // LD-T21（结构部分）
    // ==================================================================

    @Test
    @DisplayName("LD-T21 ai_operation_secret 不含三个逻辑删除字段（LD-EX-01，保持物理删除）")
    void secretTableHasNoLogicalDeleteColumns() {
        Integer cols = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_operation_secret'
                  AND COLUMN_NAME IN ('is_deleted', 'deleted_at', 'deleted_by')
                """, Integer.class);
        assertThat(cols)
                .as("该表唯一目的是缩短敏感密文存储窗口，逻辑删除会让密文永久滞留（§7.3a）")
                .isZero();
        assertThat(LogicalDeleteTables.isManaged("ai_operation_secret"))
                .as("也不得进入拦截器受管表，否则会因列不存在直接报 SQL 错误")
                .isFalse();
    }

    // ==================================================================
    // LD-T2a：唯一键定义
    // ==================================================================

    @Test
    @DisplayName("LD-T2a 13 个唯一键都必须是 (业务键, IFNULL(deleted_at, 哨兵)) 的表达式形态")
    void uniqueKeysIncludeDeletedAt() {
        for (String[] key : UNIQUE_KEYS) {
            String table = key[0];
            String indexName = key[1];
            String businessCol = key[2];

            // 第一个分量：业务键（普通列）
            List<Map<String, Object>> parts = jdbc.queryForList("""
                    SELECT COLUMN_NAME, EXPRESSION FROM information_schema.STATISTICS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?
                    ORDER BY SEQ_IN_INDEX
                    """, table, indexName);
            assertThat(parts).as("%s.%s 必须是两个分量", table, indexName).hasSize(2);
            assertThat(parts.get(0).get("COLUMN_NAME")).as("%s.%s 第一个分量必须是业务键", table, indexName)
                    .isEqualTo(businessCol);

            // 第二个分量：必须是**表达式**（IFNULL 归一化 NULL），不是裸的 deleted_at 列
            //
            // 为什么不能用裸列：MySQL 唯一索引允许多个 NULL，而有效行的 deleted_at 就是 NULL
            // → `UNIQUE(业务键, deleted_at)` 会**放行两条同名有效行**，实测确认。
            // 归一化为哨兵值后，有效行在索引里是同一个常量值 → 互斥；已删除行各带自己微秒时间戳
            // → 可多条共存、支持无限次删建。这是 PostgreSQL partial unique index 的等价实现。
            Map<String, Object> second = parts.get(1);
            assertThat(second.get("COLUMN_NAME"))
                    .as("%s.%s 第二个分量必须是表达式（不能是裸列 deleted_at，否则无法拦住重复有效行）",
                            table, indexName)
                    .isNull();
            assertThat(String.valueOf(second.get("EXPRESSION")))
                    .as("%s.%s 表达式必须是 IFNULL(deleted_at, ...)", table, indexName)
                    .containsIgnoringCase("ifnull")
                    .containsIgnoringCase("deleted_at");
        }

        // 不允许把 is_deleted 纳入唯一键（只有 0/1，第二次删除同业务键会撞键）
        Integer bad = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME = 'is_deleted' AND NON_UNIQUE = 0
                """, Integer.class);
        assertThat(bad).as("不允许存在把 is_deleted 纳入唯一键的写法（只能删一次）").isZero();

        // 不允许残留"裸 deleted_at 列"形态的旧唯一键（V2 遗留形态，会被 V3 自愈）
        Integer legacy = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME = 'deleted_at' AND NON_UNIQUE = 0
                """, Integer.class);
        assertThat(legacy)
                .as("不允许存在把 deleted_at 作为裸列的唯一键（会放行重复有效行）")
                .isZero();
    }

    // ==================================================================
    // LD-T22：库中不存在触发器 / 存储过程 / 函数
    // ==================================================================

    @Test
    @DisplayName("LD-T22 库中不存在任何触发器 / 存储过程 / 函数（LD-EX-02 回归护栏）")
    void noDatabaseObjects() {
        Integer triggers = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = DATABASE()
                """, Integer.class);
        assertThat(triggers).as("评审明确不使用触发器（不便维护，且与代码形成两套真相）").isZero();

        List<Map<String, Object>> routines = jdbc.queryForList("""
                SELECT ROUTINE_NAME, ROUTINE_TYPE FROM information_schema.ROUTINES
                WHERE ROUTINE_SCHEMA = DATABASE()
                """);
        assertThat(routines).as("不允许任何 PROCEDURE / FUNCTION：%s", routines).isEmpty();

        Integer events = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.EVENTS WHERE EVENT_SCHEMA = DATABASE()
                """, Integer.class);
        assertThat(events).as("也不允许用事件调度器替代触发器").isZero();
    }

    // ==================================================================
    // LD-T19：一致性巡检（不用触发器后唯一的兜底）
    // ==================================================================

    @Test
    @DisplayName("LD-T19 一致性巡检：18 张表 (is_deleted = 1) <> (deleted_at IS NOT NULL) 必须全为 0 行")
    void consistencyScanIsClean() {
        StringBuilder union = new StringBuilder();
        for (int i = 0; i < TABLES.size(); i++) {
            if (i > 0) {
                union.append(" UNION ALL ");
            }
            union.append("SELECT '").append(TABLES.get(i)).append("' AS tbl, COUNT(*) AS bad FROM ")
                    .append(TABLES.get(i))
                    .append(" WHERE (is_deleted = 1) <> (deleted_at IS NOT NULL)");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT tbl, bad FROM (" + union + ") x WHERE bad > 0");
        assertThat(rows).as("存在\"只写了一半\"的脏数据（现象与原因看起来无关，极难排查）：%s", rows).isEmpty();
    }

    // ==================================================================
    // LD-T17：deleted_by 三态可区分
    // ==================================================================

    @Test
    @DisplayName("LD-T17 deleted_by 三态：直连落 'DB'、应用写数字且可关联出账号、列 NOT NULL")
    void deletedByDistinguishesDirectDbFromApplication() {
        jdbc.update("INSERT INTO sys_org (org_code, org_name, region_code, region_name, org_level, parent_id, status) "
                + "VALUES (?, '夹具-直连', '000000', '未指定', 1, 0, 1)", P + "_by_db");
        jdbc.update("UPDATE sys_org SET is_deleted = 1, deleted_at = NOW(6) WHERE org_code = ?", P + "_by_db");

        long adminId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = 'admin'", Long.class);
        jdbc.update("INSERT INTO sys_org (org_code, org_name, region_code, region_name, org_level, parent_id, status) "
                + "VALUES (?, '夹具-应用', '000000', '未指定', 1, 0, 1)", P + "_by_app");
        jdbc.update("UPDATE sys_org SET is_deleted = 1, deleted_at = NOW(6), deleted_by = ? WHERE org_code = ?",
                String.valueOf(adminId), P + "_by_app");

        // 注意：必须用别名限定外层的 o.deleted_by。
        // 若写成裸 deleted_by，子查询里会优先解析到最内层 sys_user.deleted_by（非限定列名的解析规则），
        // CAST('DB' AS UNSIGNED) = 0 → 永远关联不到人。设计 §2.1a 的片段在外层表不是 sys_user 时会踩这个坑。
        List<Map<String, Object>> classified = jdbc.queryForList("""
                SELECT o.org_code,
                       CASE WHEN o.deleted_by = 'DB' THEN 'DB'
                            WHEN o.deleted_by REGEXP '^[0-9]+$' THEN 'APP'
                            ELSE 'OTHER' END AS src,
                       CASE WHEN o.deleted_by REGEXP '^[0-9]+$'
                            THEN (SELECT u.username FROM sys_user u WHERE u.id = CAST(o.deleted_by AS UNSIGNED))
                            ELSE NULL END AS operator
                FROM sys_org o WHERE o.org_code LIKE ?
                ORDER BY o.org_code
                """, P + "_by%");
        assertThat(classified).hasSize(2);
        assertThat(classified.get(0).get("src")).isEqualTo("APP");
        assertThat(classified.get(0).get("operator")).as("数字形态必须能关联出账号").isEqualTo("admin");
        assertThat(classified.get(1).get("src")).isEqualTo("DB");
        assertThat(classified.get(1).get("operator")).isNull();

        Integer nulls = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_org WHERE deleted_by IS NULL", Integer.class);
        assertThat(nulls).as("列是 NOT NULL，不允许出现 NULL（\"未知\"不能被伪装成已知用户）").isZero();
    }

    // ==================================================================
    // LD-T20：直连 SQL 模板有效性（不用存储过程）
    // ==================================================================

    @Test
    @DisplayName("LD-T20 直连模板：三列一条语句写全、NOW(6) 微秒、重复删除 ROW_COUNT=0、缺省落 'DB'")
    void directSqlTemplateWorks() {
        jdbc.update("INSERT INTO sys_org (org_code, org_name, region_code, region_name, org_level, parent_id, status) "
                + "VALUES (?, '夹具-直连模板', '000000', '未指定', 1, 0, 1)", P + "_direct");

        int affected = jdbc.update("""
                UPDATE sys_org
                   SET is_deleted = 1,
                       deleted_at = NOW(6),
                       deleted_by = 'DB'
                 WHERE org_code = ?
                   AND is_deleted = 0
                """, P + "_direct");
        assertThat(affected).as("首次删除影响 1 行").isEqualTo(1);

        int again = jdbc.update("""
                UPDATE sys_org
                   SET is_deleted = 1, deleted_at = NOW(6), deleted_by = 'DB'
                 WHERE org_code = ? AND is_deleted = 0
                """, P + "_direct");
        assertThat(again).as("重复删除必须 0 行（否则会被当成成功，审计失真）").isZero();

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT is_deleted, deleted_at, deleted_by FROM sys_org WHERE org_code = ?
                """, P + "_direct");
        assertThat(((Number) row.get("is_deleted")).intValue()).isEqualTo(1);
        assertThat(row.get("deleted_by")).isEqualTo("DB");
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(jdbc.queryForObject("""
                SELECT DATETIME_PRECISION FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_org' AND COLUMN_NAME = 'deleted_at'
                """, Integer.class)).isEqualTo(6);

        int restored = jdbc.update("""
                UPDATE sys_org SET is_deleted = 0, deleted_at = NULL, deleted_by = 'DB'
                 WHERE org_code = ? AND is_deleted = 1
                """, P + "_direct");
        assertThat(restored).isEqualTo(1);
        Map<String, Object> after = jdbc.queryForMap(
                "SELECT is_deleted, deleted_at FROM sys_org WHERE org_code = ?", P + "_direct");
        assertThat(((Number) after.get("is_deleted")).intValue()).isZero();
        assertThat(after.get("deleted_at")).isNull();
    }

    // ==================================================================
    // LD-T8a：业务唯一性由数据库保证（V3 函数索引修复后）
    // ==================================================================

    @Test
    @DisplayName("LD-T8a 数据库必须拒绝两条未删除的同名行；删除后必须能重建同业务键")
    void activeRowUniquenessIsEnforcedByDatabase() {
        // 背景：V2 建的 UNIQUE(业务键, deleted_at) 有一个漏洞——MySQL 唯一索引允许多个 NULL，
        // 而有效行的 deleted_at 就是 NULL，因此"两条同名有效行"曾被放行。
        // V3 改为 UNIQUE(业务键, IFNULL(deleted_at, 哨兵值)) 后，有效行在索引里是同一个常量，
        // 因此**数据库层面互斥**，同时已删除行仍可多条共存（哨兵只在 NULL 时生效）。
        jdbc.update("INSERT INTO sys_user (username, password, real_name, org_id, status) VALUES (?, 'x', '夹具', ?, 1)",
                P + "_dup", hqOrgId());

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO sys_user (username, password, real_name, org_id, status) VALUES (?, 'x', '夹具', ?, 1)",
                P + "_dup", hqOrgId()))
                .as("数据库必须拒绝第二条未删除的同名行（业务唯一性由 DB 保证，不依赖应用层）")
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);

        // 按账号查询必须只返回唯一一条（不再有 TooManyResults 风险）
        assertThat(sysUserMapper.selectByUsername(P + "_dup"))
                .as("唯一有效行下按账号查询必须正常返回单条")
                .isNotNull();

        // 软删除后必须能重建同业务键——这是复合唯一键存在的理由
        jdbc.update("UPDATE sys_user SET is_deleted = 1, deleted_at = NOW(6) WHERE username = ? AND is_deleted = 0",
                P + "_dup");
        assertThatCode(() -> jdbc.update(
                "INSERT INTO sys_user (username, password, real_name, org_id, status) VALUES (?, 'x', '夹具', ?, 1)",
                P + "_dup", hqOrgId()))
                .as("删除后必须能重建同业务键")
                .doesNotThrowAnyException();

        // 再删除一次也应该成功（哨兵值让已删除行各自独立，支持无限次删建）
        assertThatCode(() -> jdbc.update(
                "UPDATE sys_user SET is_deleted = 1, deleted_at = NOW(6) WHERE username = ? AND is_deleted = 0",
                P + "_dup"))
                .as("第二次删除同业务键也必须成功（deleted_at 微秒精度 + 哨兵归一化）")
                .doesNotThrowAnyException();

        // 两条已删除行 + 无有效行：确认已删除行之间不互斥
        Integer deletedRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE username = ? AND is_deleted = 1", Integer.class, P + "_dup");
        assertThat(deletedRows).as("同一业务键的多条已删除行必须共存").isEqualTo(2);
    }

    // ==================================================================
    // LD-T2：13 个唯一键 × 5 轮 删除→重建
    // ==================================================================

    @Test
    @DisplayName("LD-T2 13 个唯一键连续 5 轮\"删除→重建→再删除\"全部通过（微秒精度方案）")
    void fiveRoundDeleteRebuildCycleForAllKeys() {
        long hq = hqOrgId();
        long userId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = 'admin'", Long.class);
        long entId = jdbc.queryForObject("SELECT MIN(id) FROM enterprise", Long.class);
        long projectId = jdbc.queryForObject("SELECT MIN(id) FROM project", Long.class);
        long typeId = jdbc.queryForObject("SELECT MIN(id) FROM insurance_type", Long.class);

        List<KeyCase> cases = List.of(
                new KeyCase("sys_user", "username", P + "_u1",
                        "INSERT INTO sys_user (username, password, real_name, org_id, status) VALUES (?, 'x', '夹具', ?, 1)",
                        List.of(hq)),
                new KeyCase("sys_org", "org_code", P + "_o1",
                        "INSERT INTO sys_org (org_code, org_name, region_code, region_name, org_level, parent_id, status) "
                                + "VALUES (?, '夹具', '000000', '未指定', 3, 0, 1)",
                        List.of()),
                new KeyCase("sys_department", "dept_code", P + "_d1",
                        "INSERT INTO sys_department (dept_code, dept_name, org_id, parent_id, status) VALUES (?, '夹具', ?, 0, 1)",
                        List.of(hq)),
                new KeyCase("sys_role", "role_code", P + "_r1",
                        "INSERT INTO sys_role (role_code, role_name, status) VALUES (?, '夹具', 1)",
                        List.of()),
                new KeyCase("sys_permission", "perm_code", P + "_p1",
                        "INSERT INTO sys_permission (perm_code, perm_name, perm_type, parent_id) VALUES (?, '夹具', 'BUTTON', 0)",
                        List.of()),
                new KeyCase("insurance_type", "type_code", P + "_i1",
                        "INSERT INTO insurance_type (type_code, type_name, category, base_rate, status) VALUES (?, '夹具', 'OTHER', 0.01, 1)",
                        List.of()),
                new KeyCase("enterprise", "ent_code", P + "_e1",
                        "INSERT INTO enterprise (ent_code, ent_name, credit_code, region_code, region_name, industry, ent_level, status) "
                                + "VALUES (?, '夹具', ?, '000000', '未指定', '其他', 'A', 1)",
                        List.of(P + "_c1")),
                new KeyCase("enterprise", "credit_code", P + "_c2",
                        "INSERT INTO enterprise (ent_code, ent_name, credit_code, region_code, region_name, industry, ent_level, status) "
                                + "VALUES (?, '夹具', ?, '000000', '未指定', '其他', 'A', 1)",
                        List.of(P + "_c2")),
                new KeyCase("project", "project_code", P + "_pr1",
                        "INSERT INTO project (project_code, project_name, enterprise_id, region_code, region_name, "
                                + "project_amount, project_type, status, tender_date) "
                                + "VALUES (?, '夹具', ?, '000000', '未指定', 100, '其他', 'BIDDING', CURDATE())",
                        List.of(entId)),
                new KeyCase("tender_order", "order_no", P + "_t1",
                        "INSERT INTO tender_order (order_no, project_id, enterprise_id, insurance_type_id, org_id, "
                                + "region_code, region_name, guarantee_amount, premium_amount, premium_rate, status, apply_date) "
                                + "VALUES (?, ?, ?, ?, ?, '000000', '未指定', 100, 1, 0.01, 'DRAFT', CURDATE())",
                        List.of(projectId, entId, typeId, hq)),
                new KeyCase("performance_order", "order_no", P + "_f1",
                        "INSERT INTO performance_order (order_no, contract_no, project_id, enterprise_id, insurance_type_id, "
                                + "org_id, region_code, region_name, guarantee_amount, premium_amount, premium_rate, status, apply_date) "
                                + "VALUES (?, 'C-夹具', ?, ?, ?, ?, '000000', '未指定', 100, 1, 0.01, 'DRAFT', CURDATE())",
                        List.of(projectId, entId, typeId, hq)),
                new KeyCase("ai_conversation", "conversation_no", P + "_cv1",
                        "INSERT INTO ai_conversation (conversation_no, user_id, title, model, status) VALUES (?, ?, '夹具', 'test', 'ACTIVE')",
                        List.of(userId)),
                new KeyCase("ai_operation_proposal", "proposal_no", P + "_op1",
                        "INSERT INTO ai_operation_proposal (proposal_no, user_id, tool_name, action, target_type, "
                                + "required_perms, status, expires_at) "
                                + "VALUES (?, ?, 'tool', 'CREATE', 'ORG', 'system:org:create', 'PENDING', NOW() + INTERVAL 1 DAY)",
                        List.of(userId)));

        assertThat(cases).as("必须逐个覆盖 13 个唯一键").hasSize(13);

        for (KeyCase c : cases) {
            for (int round = 1; round <= 5; round++) {
                List<Object> params = new ArrayList<>();
                params.add(c.keyValue());
                params.addAll(c.extraParams());
                int inserted = jdbc.update(c.insertSql(), params.toArray());
                assertThat(inserted).as("%s 第 %d 轮重建必须成功", c.table(), round).isEqualTo(1);

                int deleted = jdbc.update("UPDATE " + c.table()
                        + " SET is_deleted = 1, deleted_at = NOW(6), deleted_by = 'DB'"
                        + " WHERE " + c.keyColumn() + " = ? AND is_deleted = 0", c.keyValue());
                assertThat(deleted).as("%s 第 %d 轮删除必须成功（不能撞唯一键）", c.table(), round).isEqualTo(1);
            }
            Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM " + c.table()
                    + " WHERE " + c.keyColumn() + " = ?", Integer.class, c.keyValue());
            assertThat(rows).as("%s 5 轮后应留下 5 行已删除记录", c.table()).isEqualTo(5);
            Integer distinctDeletedAt = jdbc.queryForObject("SELECT COUNT(DISTINCT deleted_at) FROM " + c.table()
                    + " WHERE " + c.keyColumn() + " = ?", Integer.class, c.keyValue());
            assertThat(distinctDeletedAt).as("%s 每轮 deleted_at 必须互不相同（微秒精度）", c.table()).isEqualTo(5);
        }
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private record KeyCase(String table, String keyColumn, String keyValue,
                           String insertSql, List<Object> extraParams) {
    }

    /** 把异常链上的类名拼起来，便于断言"具体以哪种异常失败"。 */
    private static String causeChain(Throwable throwable) {
        StringBuilder sb = new StringBuilder();
        Throwable cursor = throwable;
        while (cursor != null) {
            sb.append(cursor.getClass().getName()).append(" | ");
            cursor = cursor.getCause();
        }
        return sb.toString();
    }

    private long hqOrgId() {
        return jdbc.queryForObject("SELECT id FROM sys_org WHERE org_level = 1", Long.class);
    }
}
