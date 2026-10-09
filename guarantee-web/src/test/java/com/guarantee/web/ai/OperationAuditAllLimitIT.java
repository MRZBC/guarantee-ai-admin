package com.guarantee.web.ai;

import com.guarantee.common.api.ResultCode;
import com.guarantee.web.GuaranteeAiAdminApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计接口「条数上限」的线上行为验收（页面「条数上限 = 全部」）。
 *
 * <p><b>为什么要走 HTTP</b>：单测只能证明 Service 传了 {@code Integer.MAX_VALUE} 给 Mapper，
 * 证明不了"Controller 收得到 {@code all=true}"——而那正是最容易漏的一环
 * （参数没接上时，页面写着"全部"却只拿到默认 50 条，且**不会报错**，属于静默的谎报）。</p>
 *
 * <p>两条断言互为对照：</p>
 * <ol>
 *   <li>{@code all=true} → 区间内**全部**记录（数量等于 {@code total}，且明显超过 200）；
 *   </li>
 *   <li>不传 {@code all}、只给 {@code limit=200} → 仍然是 200 条上限（默认行为没被改坏）。</li>
 * </ol>
 *
 * <p><b>数据来源：本用例自建夹具</b>（{@link #createAuditHistory()}）。
 * 审计行是**运行时**产生的：DataInitializer 不造审计数据（本类也显式
 * {@code guarantee.data-init.enabled=false}），所以"库里近 7 天有几条"取决于跑了多少活。
 * 早先这里断言"演示库里近 7 天的审计应明显多于 200 条"，于是它只在**被反复使用过的开发库**
 * 上成立——2026-10-08 CI 首跑时新库只有 14 条（同一次 verify 里其他 IT 产生），两个用例直接红
 * 在"数不出差异"上，而真正要测的 all/limit 行为根本没被执行到。
 * 现在改为每条用例前插入 {@link #FIXTURE_ROWS} 条近 7 天的审计行、用例后按 trace_id 删除，
 * 断言口径不变，但在空库 / CI / 用过的库上都可复现。</p>
 */
@SpringBootTest(
        classes = GuaranteeAiAdminApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "guarantee.data-init.enabled=false",
                "guarantee.ai.proposal-expire-interval-ms=3600000",
                "guarantee.ai.audit-inspect-interval-ms=3600000"
        })
class OperationAuditAllLimitIT {

    private static final String ADMIN_PASSWORD = "Admin@123";

    /** 夹具行数：必须 > 200，才能把"全部"与"默认 200 上限"区分开。 */
    private static final int FIXTURE_ROWS = 250;

    /** 夹具标记：只删自己插入的行，不碰真实审计数据。 */
    private static final String FIXTURE_TRACE = "IT-op-audit-all-limit";

    @LocalServerPort
    private int port;

    /** Spring Boot 4 已移除 TestRestTemplate，这里用框架自带的 RestTemplate + 随机端口。 */
    private final RestTemplate rest = new RestTemplate();

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 近 7 天内的 {@link #FIXTURE_ROWS} 条审计夹具（逐条落在 now-i 分钟，保证落在查询区间内）。
     *
     * <p>用 {@code operated_at} 而不是 created_at：该表按 {@code operated_at} 分区，
     * 且查询的时间区间也过滤这一列（见 {@code AiOperationAuditMapper.selectPage}）。</p>
     */
    @BeforeEach
    void createAuditHistory() {
        List<Object[]> batch = new ArrayList<>(FIXTURE_ROWS);
        LocalDateTime base = LocalDateTime.now();
        for (int i = 0; i < FIXTURE_ROWS; i++) {
            batch.add(new Object[]{base.minusMinutes(i), 1L, "admin", FIXTURE_TRACE});
        }
        jdbc.batchUpdate("""
                INSERT INTO ai_operation_audit
                    (operated_at, operator_user_id, operator_username, source, action,
                     target_type, result, trace_id)
                VALUES (?, ?, ?, 'WEB', 'IT_ALL_LIMIT', 'IT_FIXTURE', 'SUCCESS', ?)
                """, batch);
        Integer inserted = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_operation_audit WHERE trace_id = ?", Integer.class, FIXTURE_TRACE);
        assertThat(inserted).as("审计夹具必须真的落库（否则用例会以'没有差异'的假象失败）")
                .isEqualTo(FIXTURE_ROWS);
    }

    /** 只清理本用例插入的行：真实审计数据一条都不动。 */
    @AfterEach
    void removeAuditHistory() {
        jdbc.update("DELETE FROM ai_operation_audit WHERE trace_id = ?", FIXTURE_TRACE);
    }

    @Test
    @DisplayName("all=true → 区间内全部记录（数量等于 total，且突破 200 条上限）")
    void allShouldReturnEveryRowInRange() {
        String token = login();

        Map<String, Object> body = getAudits(token, "all=true");
        long total = totalOf(body);
        List<?> items = itemsOf(body);

        assertThat(total).as("本用例造了 %s 条近 7 天审计，总数必然 > 200，否则测不出 all 与 limit=200 的差异",
                        FIXTURE_ROWS)
                .isGreaterThan(200L);
        assertThat(items).as("选了「全部」就必须返回全部，而不是被 200 截断")
                .hasSize((int) total);
    }

    @Test
    @DisplayName("不传 all、limit=200 → 仍然只返回 200 条（默认上限没被改坏）")
    void defaultLimitShouldStillCapAtTwoHundred() {
        String token = login();

        Map<String, Object> body = getAudits(token, "limit=200");
        long total = totalOf(body);
        List<?> items = itemsOf(body);

        assertThat(total).isGreaterThan(200L);
        assertThat(items).as("没有显式要求「全部」时，SYS-Q-06 的 200 条上限必须仍然生效")
                .hasSize(200);
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private String login() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange(baseUrl() + "/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", "admin", "password", ADMIN_PASSWORD), headers),
                String.class);
        Map<String, Object> body = read(response.getBody());
        assertThat(body.get("code")).isEqualTo(ResultCode.SUCCESS.code());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        return String.valueOf(data.get("token"));
    }

    private Map<String, Object> getAudits(String token, String limitOrAll) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(6);
        String url = baseUrl() + "/api/ai/operation-audits?startDate=" + start + "&endDate=" + end
                + "&" + limitOrAll;
        ResponseEntity<String> response = rest.exchange(url, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        Map<String, Object> body = read(response.getBody());
        assertThat(body.get("code")).as("查询应成功：%s", body.get("message"))
                .isEqualTo(ResultCode.SUCCESS.code());
        return body;
    }

    private static long totalOf(Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        return ((Number) data.get("total")).longValue();
    }

    private static List<?> itemsOf(Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        return (List<?>) data.get("items");
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String json) {
        assertThat(json).isNotBlank();
        return objectMapper.readValue(json, Map.class);
    }
}
