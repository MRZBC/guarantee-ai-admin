package com.guarantee.web.ai;

import com.guarantee.common.api.ResultCode;
import com.guarantee.web.GuaranteeAiAdminApplication;
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
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
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

    @LocalServerPort
    private int port;

    /** Spring Boot 4 已移除 TestRestTemplate，这里用框架自带的 RestTemplate + 随机端口。 */
    private final RestTemplate rest = new RestTemplate();

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("all=true → 区间内全部记录（数量等于 total，且突破 200 条上限）")
    void allShouldReturnEveryRowInRange() {
        String token = login();

        Map<String, Object> body = getAudits(token, "all=true");
        long total = totalOf(body);
        List<?> items = itemsOf(body);

        assertThat(total).as("演示库里近 7 天的审计应明显多于 200 条，否则本用例测不出差异")
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
