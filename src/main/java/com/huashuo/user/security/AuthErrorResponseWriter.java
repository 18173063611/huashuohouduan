package com.huashuo.user.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class AuthErrorResponseWriter {

    private final ObjectMapper objectMapper;

    public AuthErrorResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, JwtAuthException exception) throws IOException {
        response.setStatus(exception.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(),
                ApiResponse.failure(exception.code(), exception.getMessage(), MDC.get(TraceIdFilter.TRACE_ID)));
    }
}
