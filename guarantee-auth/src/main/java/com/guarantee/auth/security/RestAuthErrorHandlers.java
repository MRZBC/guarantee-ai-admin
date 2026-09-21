package com.guarantee.auth.security;

import tools.jackson.databind.ObjectMapper;
import com.guarantee.common.api.Result;
import com.guarantee.common.api.ResultCode;
import com.guarantee.common.trace.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 未认证 / 无权限时返回与业务一致的 JSON 结构，而不是 Spring Security 默认页面。
 */
public class RestAuthErrorHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public RestAuthErrorHandlers(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        write(response, HttpServletResponse.SC_UNAUTHORIZED,
                Result.fail(ResultCode.UNAUTHORIZED));
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        write(response, HttpServletResponse.SC_FORBIDDEN,
                Result.fail(ResultCode.FORBIDDEN));
    }

    private void write(HttpServletResponse response, int status, Result<Void> body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(TraceContext.HEADER, String.valueOf(TraceContext.currentTraceId()));
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
