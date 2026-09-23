package com.guarantee.web.support;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 集成测试用的极简 HTTP 客户端。
 *
 * <p><b>为什么用 {@code RestTemplate} 而不是 {@code TestRestTemplate}</b>：Spring Boot 4 已移除
 * {@code org.springframework.boot.test.web.client.TestRestTemplate}（4.1.1 的依赖里不存在该类）。
 * 与既有 {@code PermissionDeniedMappingIT} 保持同一手法。</p>
 *
 * <p><b>为什么统一按"HTTP 200 + 业务 code"断言</b>：本项目的约定是业务错误也走 HTTP 200，
 * 前端 {@code request.ts} 按 {@code body.code} 分支。只有认证失败（401/403）才由
 * {@code AuthenticationEntryPoint} / {@code AccessDeniedHandler} 直接给 HTTP 状态码。
 * 两类响应必须分开断言，混在一起会掩盖"本该 401 却返回 200 + code=401"这类缺陷。</p>
 */
public class ApiClient {

    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public ApiClient(int port, ObjectMapper objectMapper) {
        this.baseUrl = "http://127.0.0.1:" + port;
        this.objectMapper = objectMapper;
    }

    /** 完整响应：既拿到 HTTP 状态，也拿到解析后的包体。 */
    public record Exchange(int status, Map<String, Object> body) {

        public int code() {
            Object code = body == null ? null : body.get("code");
            return code instanceof Number number ? number.intValue() : -1;
        }

        public String message() {
            Object message = body == null ? null : body.get("message");
            return message == null ? "" : message.toString();
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> data() {
            Object data = body == null ? null : body.get("data");
            return data instanceof Map ? (Map<String, Object>) data : Map.of();
        }

        /** {@code data} 为数组时取列表（例如 {@code PageResult.list} 的上级 data 是对象，需再取一层）。 */
        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> dataList() {
            Object data = body == null ? null : body.get("data");
            return data instanceof List ? (List<Map<String, Object>>) data : List.of();
        }

        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> pageList() {
            Object list = data().get("list");
            return list instanceof List ? (List<Map<String, Object>>) list : List.of();
        }

        public long pageTotal() {
            Object total = data().get("total");
            return total instanceof Number number ? number.longValue() : 0L;
        }
    }

    public Exchange post(String path, Object body, String token, Map<String, String> extraHeaders) {
        return exchange(HttpMethod.POST, path, body, token, extraHeaders);
    }

    public Exchange post(String path, Object body, String token) {
        return post(path, body, token, Map.of());
    }

    public Exchange get(String path, String token) {
        return exchange(HttpMethod.GET, path, null, token, Map.of());
    }

    public Exchange get(String path, String token, Map<String, String> extraHeaders) {
        return exchange(HttpMethod.GET, path, null, token, extraHeaders);
    }

    public Exchange delete(String path, String token) {
        return exchange(HttpMethod.DELETE, path, null, token, Map.of());
    }

    public Exchange exchange(HttpMethod method, String path, Object body, String token,
                            Map<String, String> extraHeaders) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        extraHeaders.forEach(headers::set);

        ResponseEntity<String> response;
        try {
            response = rest.exchange(baseUrl + path, method, new HttpEntity<>(body, headers),
                    String.class);
        } catch (RestClientResponseException ex) {
            // 4xx/5xx：RestTemplate 默认抛异常，但本项目**大量用例要断言 401/403/503 本身**
            // （认证失败与健康检查都不是"业务错误走 HTTP 200"那一类）。
            // 这里把异常还原成响应，让调用方按状态码断言，而不是让测试以异常的面孔失败。
            return new Exchange(ex.getStatusCode().value(), parseQuietly(ex.getResponseBodyAsString()));
        }
        return new Exchange(response.getStatusCode().value(), parseQuietly(response.getBody()));
    }

    private Map<String, Object> parseQuietly(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return readMap(json);
        } catch (RuntimeException ex) {
            return Map.of("__unparsable__", json);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        return objectMapper.readValue(json, Map.class);
    }

    /** 便捷：登录并返回令牌；失败时抛出带原因的断言错误（否则整类测试都会以"登录失败"的面孔失败）。 */
    public String login(String username, String password) {
        Exchange response = post("/api/auth/login", Map.of("username", username, "password", password),
                null);
        if (response.code() != 0) {
            throw new AssertionError("登录失败 username=" + username
                    + " code=" + response.code() + " message=" + response.message());
        }
        Object token = response.data().get("token");
        if (token == null) {
            throw new AssertionError("登录响应缺少 token：" + response.body());
        }
        return token.toString();
    }

    /** 登录并返回完整响应（用于断言业务码，例如被锁定时的 1004）。 */
    public Exchange loginRaw(String username, String password, Map<String, String> extraHeaders) {
        return post("/api/auth/login", Map.of("username", username, "password", password),
                null, extraHeaders);
    }
}
