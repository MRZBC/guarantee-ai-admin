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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「权限不足」的**线上响应映射**集成测试。
 *
 * <p><b>为什么必须是走 HTTP 的 IT</b>：单元测试只能证明"处理这个方法会返回 403"，
 * 证明不了"Spring MVC 真的把这个异常派发到了这个方法"。而真机故障恰恰出在派发上——
 * {@code @PreAuthorize} 抛的 {@code AuthorizationDeniedException} 曾经落进
 * {@code @ExceptionHandler(Exception.class)} 兜底分支，线上返回
 * {@code {"code":500,"message":"系统内部错误"}}，前端针对 403 的文案永远命中不了。</p>
 *
 * <p>本测试启动一个**随机端口**的真实 Web 环境，用演示账号登录取 JWT 后请求同一个接口，
 * 断言线上响应：非 ADMIN → {@code code=403} + 可读话术；ADMIN → 正常返回。
 * 两条一起才能说明"修复没有把接口权限放宽或收紧错方向"。</p>
 *
 * <p>用运行中的应用做验证（而不是只跑单测）还有一个好处：这套断言与"重启后端后手工 curl"
 * 得到的结果是同一层证据。</p>
 */
@SpringBootTest(
        classes = GuaranteeAiAdminApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "guarantee.data-init.enabled=false",
                // 关掉定时任务，避免测试期间提案被"过期清理"打断
                "guarantee.ai.proposal-expire-interval-ms=3600000",
                "guarantee.ai.audit-inspect-interval-ms=3600000"
        })
class PermissionDeniedMappingIT {

    /** 演示账号口令（`DataInitializer` 中的公开常量）。 */
    private static final String ANALYST_PASSWORD = "Analyst@123";
    private static final String ADMIN_PASSWORD = "Admin@123";

    @LocalServerPort
    private int port;

    /**
     * 直接用 `RestTemplate` 而不是 `TestRestTemplate`：
     * Spring Boot 4 已移除 `org.springframework.boot.test.web.client.TestRestTemplate`
     * （4.1.1 的依赖里不存在该类），用框架自带的 `RestTemplate` + 随机端口拼绝对地址更稳。
     */
    private final RestTemplate rest = new RestTemplate();

    @Autowired
    private ObjectMapper objectMapper;

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    // ==================================================================
    // 非 ADMIN：必须先被后端拒绝，且如实说"权限不足"
    // ==================================================================

    @Test
    @DisplayName("ANALYST 请求操作审计接口 → code=403 + 「权限/联系管理员」，不再是 500「系统内部错误」")
    void analystShouldGetForbiddenNotInternalError() {
        String token = login("analyst", ANALYST_PASSWORD);

        Map<String, Object> body = getOperationAudits(token);

        assertThat(body.get("code"))
                .as("权限不足必须是 403；曾经被兜底分支报成 500「系统内部错误」")
                .isEqualTo(ResultCode.FORBIDDEN.code())
                .isNotEqualTo(ResultCode.INTERNAL_ERROR.code());
        assertThat(String.valueOf(body.get("message")))
                .as("话术要可读、可操作，且不得谎称系统故障")
                .contains("权限")
                .contains("联系管理员")
                .doesNotContain("系统内部错误");
        // 响应里必须带 traceId：权限问题也要能回溯（与其它响应一致）
        assertThat(body.get("traceId")).as("错误响应同样要带 traceId").isNotNull();
    }

    // ==================================================================
    // ADMIN：同一接口仍然正常（修复不能误伤有权限的人）
    // ==================================================================

    @Test
    @DisplayName("ADMIN 请求同一接口仍然成功（权限判定没被改坏）")
    void adminShouldStillSucceed() {
        String token = login("admin", ADMIN_PASSWORD);

        Map<String, Object> body = getOperationAudits(token);

        assertThat(body.get("code")).isEqualTo(ResultCode.SUCCESS.code());
        assertThat(body.get("data")).as("成功响应必须带 data（total + items）").isNotNull();
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** 登录并取 JWT；顺带断言演示账号可用（否则整类测试都会以"登录失败"的面孔失败）。 */
    private String login(String username, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> payload = Map.of("username", username, "password", password);

        ResponseEntity<String> response = rest.exchange(baseUrl() + "/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(payload, headers), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

        Map<String, Object> body = read(response.getBody());
        assertThat(body.get("code")).as("演示账号 %s 应能登录（依赖演示数据）", username)
                .isEqualTo(ResultCode.SUCCESS.code());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        return String.valueOf(data.get("token"));
    }

    private Map<String, Object> getOperationAudits(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(6);
        String url = "/api/ai/operation-audits?startDate=" + start + "&endDate=" + end + "&limit=5";

        ResponseEntity<String> response = rest.exchange(baseUrl() + url, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        // 本项目的约定：业务错误也是 HTTP 200 + 非 0 的 code
        // （前端 request.ts 按 body.code 判断，所以这里断言 body 而不是 HTTP 状态）
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("业务错误走 HTTP 200 + code，不应变成 4xx/5xx")
                .isTrue();
        return read(response.getBody());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String json) {
        assertThat(json).as("响应体不应为空").isNotBlank();
        return objectMapper.readValue(json, Map.class);
    }
}
