package com.guarantee.system.mybatis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 逻辑删除 SQL 改写器的单元测试（设计文档 §11 的 LD-T14 / LD-T22 的表清单部分）。
 *
 * <p>为什么这些断言必须存在：拦截器是"已删除数据不可见"的兜底机制，
 * 它一旦注入错位置就会产生**语法错误的全站故障**，一旦漏注入就是**数据泄漏**。
 * 因此这里对四类行为逐条锁定：</p>
 * <ul>
 *   <li>自动注入（简单查询 / 多表 JOIN）；</li>
 *   <li>已显式含 {@code is_deleted} 条件时不重复注入（列投影不算条件）；</li>
 *   <li>{@code ...IncludingDeleted} 方法名豁免（"显示已删除"与恢复读取）；</li>
 *   <li>解析不确定时**抛异常而不是静默放行**（顶层 UNION 场景）。</li>
 * </ul>
 */
class LogicalDeleteSqlRewriterTest {

    // ==================================================================
    // LD-T14：自动注入
    // ==================================================================

    @Test
    @DisplayName("LD-T14 简单单表查询：注入表名限定条件，未删除语义生效")
    void injectsSimpleSelect() {
        String sql = "SELECT id, org_code FROM sys_org WHERE status = 1 ORDER BY sort_no ASC LIMIT ?";
        String rewritten = LogicalDeleteSqlRewriter.rewrite(sql);
        assertThat(rewritten)
                .contains("sys_org.is_deleted = 0")
                // 注入点必须在 ORDER BY 之前，否则语法错误
                .doesNotContain("0ORDER BY");
        assertThat(rewritten.indexOf("is_deleted = 0")).isLessThan(rewritten.indexOf("ORDER BY"));
    }

    @Test
    @DisplayName("LD-T14 无 WHERE 的查询：补一个 WHERE，且必须插在 JOIN 之后")
    void injectsWhereWhenMissing() {
        // 用户不再挂机构（机构服务于订单），真实 SQL 里 sys_user 只 JOIN 部门
        String sql = "SELECT COUNT(*) FROM sys_user u "
                + "LEFT JOIN sys_department d ON d.id = u.dept_id";
        String rewritten = LogicalDeleteSqlRewriter.rewrite(sql);
        assertThat(rewritten).contains("u.is_deleted = 0");
        // 关键回归：曾经把 "WHERE ... is_deleted = 0" 插到第一个 LEFT JOIN 之前，
        // 产生 "FROM sys_user u WHERE ... LEFT JOIN ..." 的语法错误
        int whereIndex = rewritten.indexOf(" WHERE ");
        assertThat(whereIndex).isGreaterThan(rewritten.indexOf("LEFT JOIN sys_department"));
        assertThat(rewritten).doesNotContain("u WHERE");
    }

    @Test
    @DisplayName("LD-T14 JOIN 表注入到 ON 子句：保持 LEFT JOIN 的外连接语义")
    void injectsJoinConditionIntoOnClause() {
        // 机构仍服务于订单：订单查询 JOIN sys_org 取机构名称（订单自带 org_id）
        String sql = "SELECT o.id, org.org_name FROM tender_order o "
                + "LEFT JOIN sys_org org ON org.id = o.org_id WHERE o.status = 'EFFECTIVE'";
        // 注入会在关键字前留一个空格，断言前统一把连续空白压成一个空格
        String rewritten = LogicalDeleteSqlRewriter.rewrite(sql).replaceAll("\\s+", " ");
        assertThat(rewritten)
                .contains("ON org.id = o.org_id AND org.is_deleted = 0")
                .contains("o.is_deleted = 0");
        // 若把 org 的条件写进 WHERE，LEFT JOIN 会退化成 INNER JOIN（左表行被误过滤）
        assertThat(rewritten).doesNotContain("WHERE o.status = 'EFFECTIVE' AND org.is_deleted");
    }

    @Test
    @DisplayName("LD-T14 派生表/子查询不由拦截器处理：由该 SQL 自身负责（设计 §5.2）")
    void doesNotTouchDerivedTables() {
        String sql = "SELECT COUNT(*) FROM ("
                + "SELECT 'TENDER' AS k, id FROM tender_order WHERE status = 'EFFECTIVE'"
                + " UNION ALL"
                + " SELECT 'PERFORMANCE' AS k, id FROM performance_order WHERE status = 'EFFECTIVE'"
                + ") merged";
        // 顶层 FROM 后面是 "("，没有受管表 → 原样返回；这类语句必须显式写 is_deleted = 0
        assertThat(LogicalDeleteSqlRewriter.rewrite(sql)).isEqualTo(sql);
    }

    // ==================================================================
    // LD-T14：不重复注入
    // ==================================================================

    @Test
    @DisplayName("LD-T14 已显式含 is_deleted 条件 → 整句跳过，不重复注入")
    void skipsWhenExplicitConditionPresent() {
        String sql = "SELECT id FROM sys_org WHERE is_deleted = 0 AND status = 1";
        assertThat(LogicalDeleteSqlRewriter.rewrite(sql)).isEqualTo(sql);

        String withIn = "SELECT id FROM sys_org WHERE is_deleted IN (0, 1) ORDER BY id";
        assertThat(LogicalDeleteSqlRewriter.rewrite(withIn)).isEqualTo(withIn);
    }

    @Test
    @DisplayName("LD-T14 列投影 is_deleted AS isDeleted 不算条件：仍要注入（保证 VO 能返回标记）")
    void projectionIsNotACondition() {
        String sql = "SELECT id, is_deleted AS isDeleted, deleted_at AS deletedAt FROM sys_org WHERE id = ?";
        String rewritten = LogicalDeleteSqlRewriter.rewrite(sql);
        assertThat(rewritten)
                .contains("is_deleted AS isDeleted")
                .contains("sys_org.is_deleted = 0");
    }

    @Test
    @DisplayName("LD-T14 字符串字面量里的 is_deleted 不误判、不破坏注入点")
    void ignoresLiteralText() {
        String sql = "SELECT id FROM sys_org WHERE remark = 'is_deleted' AND status = 1";
        String rewritten = LogicalDeleteSqlRewriter.rewrite(sql);
        assertThat(rewritten).contains("sys_org.is_deleted = 0");
    }

    // ==================================================================
    // LD-T14：方法名豁免
    // ==================================================================

    @Test
    @DisplayName("LD-T14 ...IncludingDeleted 方法名豁免（显示已删除 / 恢复前读取）")
    void methodNameSuffixIsExempt() {
        assertThat(LogicalDeleteInnerInterceptor
                .isExempt("com.guarantee.system.mapper.SysOrgMapper.selectEntityByIdIncludingDeleted"))
                .isTrue();
        assertThat(LogicalDeleteInnerInterceptor
                .isExempt("com.guarantee.system.mapper.SysOrgMapper.selectEntityById"))
                .isFalse();
    }

    // ==================================================================
    // LD-T14：解析不确定 → 抛异常（绝不静默放行）
    // ==================================================================

    @Test
    @DisplayName("LD-T14 顶层 UNION 无法安全注入 → 抛异常而不是只过滤一个分支")
    void topLevelUnionFailsLoudly() {
        String sql = "SELECT id FROM sys_org WHERE status = 1"
                + " UNION ALL SELECT id FROM sys_org WHERE status = 0";
        assertThatThrownBy(() -> LogicalDeleteSqlRewriter.rewrite(sql))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UNION");
    }

    // ==================================================================
    // 受管表清单（LD-EX-01 的护栏）
    // ==================================================================

    @Test
    @DisplayName("受管表恰好 18 张，且 ai_operation_secret 不在其中（LD-EX-01）")
    void managedTablesAreExactly18AndExcludeSecret() {
        assertThat(LogicalDeleteTables.MANAGED).hasSize(18);
        assertThat(LogicalDeleteTables.isManaged("ai_operation_secret")).isFalse();
        assertThat(LogicalDeleteTables.isManaged("AI_OPERATION_SECRET")).isFalse();
        assertThat(LogicalDeleteTables.isManaged("sys_user")).isTrue();
        assertThat(LogicalDeleteTables.isManaged("SYS_USER")).as("大小写不敏感").isTrue();
        // 未登记的表不注入：否则会因为列不存在直接报 SQL 错误
        assertThat(LogicalDeleteTables.isManaged("t_dt")).isFalse();
    }
}
