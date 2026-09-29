package com.guarantee.web.ai.config;

import com.guarantee.common.api.ResultCode;
import com.guarantee.web.GuaranteeAiAdminApplication;
import com.guarantee.web.support.ApiClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置变更的全链路集成测试（TEST-CFG-05 / AC-CFG-05）。
 *
 * <p><b>为什么必须是走 HTTP + 真实库的 IT</b>：单测能证明"审计入参拼对了"，证明不了
 * 这条链路真的通——{@code @PreAuthorize} 的权限码有没有下发到角色、{@code WebAuditor}
 * 在真实 Security 上下文里拿不拿得到操作者、审计有没有真的落到 {@code ai_operation_audit}
 * 的当月经分区，只有跑起来才知道。而"配了但没审计"恰恰是这类功能最危险的失败形态。</p>
 *
 * <p><b>建表来源</b>（task-6 落地前）：正常链路由 schema.sql 自举，但 T4-00 的两张配置表
 * 要到 T4-01 才并进 schema.sql。这里用 {@code @TestPropertySource} 把
 * {@code db/migration/V8__ai_config.sql} 追加进 schema 初始化列表——V8 幂等，
 * 重复执行无害；task-6 把 DDL 并进 schema.sql 后本覆盖仍然成立（重复执行 IF NOT EXISTS）。</p>
 *
 * <p><b>不污染共享开发库</b>：每个用例开始与结束都清掉本用例涉及的两行配置
 * （空表 = 全部走目录默认值），净影响为零；审计记录按既有约定保留
 * （审计是只增不改的事实记录）。</p>
 */
@SpringBootTest(
        classes = GuaranteeAiAdminApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "guarantee.data-init.enabled=false",
                "guarantee.ai.proposal-expire-interval-ms=3600000",
                "guarantee.ai.audit-inspect-interval-ms=3600000"
        })
@TestPropertySource(properties = {
        "spring.sql.init.schema-locations=classpath:db/schema.sql,"
                + "classpath:db/migration/V8__ai_config.sql"
})
class AiConfigChangeAuditIT {

    private static final String ADMIN_PASSWORD = "Admin@123";
    private static final String ANALYST_PASSWORD = "Analyst@123";

    private static final String TEMPERATURE_KEY = "model.temperature";
    private static final String API_KEY_REF_KEY = "model.api-key-ref";
    private static final String DEFAULT_API_KEY_REF = "DEEPSEEK_API_KEY";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port, objectMapper);
        // 1) 清掉本用例涉及的两行（开发库回到"空表 = 全默认"）
        clearConfigRows();
        // 2) 再通过接口恢复默认：如果应用内快照缓存还留着上一用例的值，
        //    这一步会触发 reset → 刷新缓存，保证每个用例都从"缺省行为"出发
        String token = api.login("admin", ADMIN_PASSWORD);
        reset(token, TEMPERATURE_KEY);
        reset(token, API_KEY_REF_KEY);
    }

    @AfterEach
    void restore() {
        clearConfigRows();
    }

    // ==================================================================
    // AC-CFG-05：每次配置变更可在审计查到 before → after
    // ==================================================================

    @Test
    @DisplayName("ADMIN 改 temperature：不重启生效 + 审计 source=WEB/CONFIG_UPDATE 且 before/after 正确")
    void adminChangeTemperatureShouldTakeEffectAndWriteWebAudit() {
        String token = api.login("admin", ADMIN_PASSWORD);

        // 0) 未改之前是默认值 0.2
        ApiClient.Exchange before = api.get("/api/ai/config", token);
        assertThat(before.code()).isEqualTo(ResultCode.SUCCESS.code());
        assertThat(itemValue(before, TEMPERATURE_KEY)).isEqualTo("0.2");
        assertThat(itemDefault(before, TEMPERATURE_KEY)).isEqualTo("0.2");
        long versionBefore = longValue(before.data().get("version"));

        // 1) 改到 0.55
        ApiClient.Exchange changed = api.post("/api/ai/config/change",
                Map.of("key", TEMPERATURE_KEY, "value", "0.55"), token);
        assertThat(changed.code()).as("改配置应成功：%s", changed.message())
                .isEqualTo(ResultCode.SUCCESS.code());
        assertThat(changed.data().get("changed")).isEqualTo(true);
        assertThat(changed.data().get("value")).as("写响应直接返回变更后的生效值").isEqualTo("0.55");
        assertThat(longValue(changed.data().get("version")))
                .as("写入必须推进配置版本号（快照刷新判据）").isGreaterThan(versionBefore);

        // 2) 同一个进程、不重启：下一个读请求已经看到新值
        ApiClient.Exchange after = api.get("/api/ai/config", token);
        assertThat(itemValue(after, TEMPERATURE_KEY)).as("不重启即生效").isEqualTo("0.55");

        // 3) 审计：谁、何时、哪一项、before → after
        Map<String, Object> audit = latestConfigAudit(TEMPERATURE_KEY);
        assertThat(audit.get("source")).as("页面渠道必须是 WEB").isEqualTo("WEB");
        assertThat(audit.get("action")).isEqualTo("CONFIG_UPDATE");
        assertThat(audit.get("target_type")).isEqualTo("AI_CONFIG");
        assertThat(audit.get("target_id")).as("target_id 是 BIGINT，配置键放不下，必须为 null").isNull();
        assertThat(audit.get("target_name")).isEqualTo(TEMPERATURE_KEY);
        assertThat(audit.get("operator_username")).isEqualTo("admin");
        assertThat(audit.get("result")).isEqualTo("SUCCESS");
        assertThat(String.valueOf(audit.get("before_value")))
                .contains(TEMPERATURE_KEY).contains("0.2");
        assertThat(String.valueOf(audit.get("after_value")))
                .contains(TEMPERATURE_KEY).contains("0.55");
        assertThat(String.valueOf(audit.get("changed_fields"))).contains(TEMPERATURE_KEY);
    }

    @Test
    @DisplayName("密钥类改引用名：审计只记 <changed>，新旧引用名都不落库")
    void secretClassChangeHidesReferenceNameInAudit() {
        String token = api.login("admin", ADMIN_PASSWORD);
        String newRef = "INTERNAL_KEY_REF_CHECK";

        ApiClient.Exchange changed = api.post("/api/ai/config/change",
                Map.of("key", API_KEY_REF_KEY, "value", newRef), token);
        assertThat(changed.code()).isEqualTo(ResultCode.SUCCESS.code());
        assertThat(changed.data().get("value")).isEqualTo(newRef);

        // 页面允许显示"引用名 + 是否已配置"（REQ §5.1.8）
        ApiClient.Exchange view = api.get("/api/ai/config", token);
        assertThat(itemValue(view, API_KEY_REF_KEY)).as("读配置时引用名可见（密钥值本身永不返回）")
                .isEqualTo(newRef);
        assertThat(item(view, API_KEY_REF_KEY).get("configured"))
                .as("指向的环境变量未配置，页面应显示「未配置」").isEqualTo(false);

        Map<String, Object> audit = latestConfigAudit(API_KEY_REF_KEY);
        String before = String.valueOf(audit.get("before_value"));
        String after = String.valueOf(audit.get("after_value"));
        assertThat(before).as("密钥类只记『是否变化』，不记引用名")
                .contains("<changed>")
                .doesNotContain(DEFAULT_API_KEY_REF)
                .doesNotContain(newRef);
        assertThat(after).contains("<changed>")
                .doesNotContain(DEFAULT_API_KEY_REF)
                .doesNotContain(newRef);
        assertThat(String.valueOf(audit.get("changed_fields"))).contains(API_KEY_REF_KEY);
    }

    @Test
    @DisplayName("值未变化：不写入、不产生审计（不能制造『改了但没变』的审计垃圾）")
    void unchangedValueProducesNoAudit() {
        String token = api.login("admin", ADMIN_PASSWORD);

        ApiClient.Exchange first = api.post("/api/ai/config/change",
                Map.of("key", TEMPERATURE_KEY, "value", "0.31"), token);
        assertThat(first.data().get("changed")).isEqualTo(true);
        long auditsAfterFirst = countConfigAudits(TEMPERATURE_KEY);

        ApiClient.Exchange second = api.post("/api/ai/config/change",
                Map.of("key", TEMPERATURE_KEY, "value", "0.31"), token);

        assertThat(second.code()).isEqualTo(ResultCode.SUCCESS.code());
        assertThat(second.data().get("changed")).isEqualTo(false);
        assertThat(second.data().get("message")).as("值未变化时必须如实提示，而不是谎报成功")
                .asString().contains("未发生变化");
        assertThat(countConfigAudits(TEMPERATURE_KEY))
                .as("值没变就不该多出一条审计").isEqualTo(auditsAfterFirst);
    }

    // ==================================================================
    // 越权：401 / 403
    // ==================================================================

    @Test
    @DisplayName("未登录读配置 → HTTP 401；ANALYST 读/写 → code=403（服务端兜底，不靠前端隐藏）")
    void nonAdminIsRejectedByServerSideAuthorization() {
        ApiClient.Exchange anonymous = api.get("/api/ai/config", null);
        assertThat(anonymous.status()).as("未带令牌必须是 401").isEqualTo(401);

        String analyst = api.login("analyst", ANALYST_PASSWORD);

        ApiClient.Exchange read = api.get("/api/ai/config", analyst);
        assertThat(read.code()).as("ANALYST 默认没有 ai:config:view")
                .isEqualTo(ResultCode.FORBIDDEN.code());
        assertThat(read.message()).contains("权限").doesNotContain("系统内部错误");

        ApiClient.Exchange write = api.post("/api/ai/config/change",
                Map.of("key", TEMPERATURE_KEY, "value", "0.77"), analyst);
        assertThat(write.code()).as("ANALYST 默认没有 ai:config:update")
                .isEqualTo(ResultCode.FORBIDDEN.code());
        assertThat(write.message()).doesNotContain("系统内部错误");

        // 越权请求不得改变生效值（用 ADMIN 再读一次确认仍是默认 0.2）
        ApiClient.Exchange after = api.get("/api/ai/config", api.login("admin", ADMIN_PASSWORD));
        assertThat(itemValue(after, TEMPERATURE_KEY)).as("被拒绝的写请求不得改变生效值").isEqualTo("0.2");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** 通过接口把配置项恢复默认（value=null 即"清空显式值"）。 */
    private void reset(String token, String key) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("key", key);
        body.put("value", null);
        api.post("/api/ai/config/change", body, token);
    }

    /** 清掉本用例涉及的两行配置。 */
    private void clearConfigRows() {
        jdbcTemplate.update("DELETE FROM ai_config_item WHERE config_key IN (?, ?)",
                TEMPERATURE_KEY, API_KEY_REF_KEY);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemsOf(ApiClient.Exchange exchange) {
        Object items = exchange.data().get("items");
        return items instanceof List ? (List<Map<String, Object>>) items : List.of();
    }

    private static Map<String, Object> item(ApiClient.Exchange exchange, String key) {
        return itemsOf(exchange).stream()
                .filter(map -> key.equals(map.get("key")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("配置项不存在：" + key
                        + "，httpStatus=" + exchange.status()
                        + "，code=" + exchange.code()
                        + "，message=" + exchange.message()
                        + "，body=" + exchange.body()));
    }

    private static String itemValue(ApiClient.Exchange exchange, String key) {
        Object value = item(exchange, key).get("value");
        return value == null ? null : value.toString();
    }

    private static String itemDefault(ApiClient.Exchange exchange, String key) {
        Object value = item(exchange, key).get("defaultValue");
        return value == null ? null : value.toString();
    }

    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : -1L;
    }

    private Map<String, Object> latestConfigAudit(String key) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT source, action, target_type, target_id, target_name,
                       before_value, after_value, changed_fields, operator_username, result
                FROM ai_operation_audit
                WHERE target_type = 'AI_CONFIG' AND target_name = ?
                ORDER BY operated_at DESC, id DESC
                LIMIT 1
                """, key);
        assertThat(rows).as("必须存在 %s 的配置变更审计", key).isNotEmpty();
        return rows.get(0);
    }

    private long countConfigAudits(String key) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ai_operation_audit
                WHERE target_type = 'AI_CONFIG' AND target_name = ?
                """, Long.class, key);
        return count == null ? 0L : count;
    }
}
