package com.occ.smartexpense.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求级上下文：traceId 进 MDC + 结束后清理用户上下文（docs/12 §6、docs/10）。
 *
 * <p>为什么清理放在 Filter 而不是拦截器的 afterCompletion：
 * 拦截器自己的 preHandle 抛异常（例如 @RequireRole 不通过）时，Spring 不会回调它的 afterCompletion，
 * ThreadLocal 会留在 Tomcat 工作线程里，下一个复用线程的请求可能读到上一个人的身份。
 * Filter 的 finally 一定会执行，这是唯一安全的位置。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class UserContextFilter extends OncePerRequestFilter {

    private static final String TRACE_HEADER = "X-Trace-Id";
    private static final String TRACE_MDC_KEY = "traceId";
    /** 头长度上限：防止有人塞超长 traceId 把日志撑爆 */
    private static final int TRACE_MAX_LEN = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = resolveTraceId(request);
        MDC.put(TRACE_MDC_KEY, traceId);
        response.setHeader(TRACE_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
            MDC.remove(TRACE_MDC_KEY);
        }
    }

    /** 请求没带就生成 8 位短 trace，保证同一请求的日志能串起来 */
    private String resolveTraceId(HttpServletRequest request) {
        String raw = request.getHeader(TRACE_HEADER);
        if (raw == null || raw.isBlank()) {
            return UUID.randomUUID().toString().substring(0, 8);
        }
        String trimmed = raw.trim();
        return trimmed.length() > TRACE_MAX_LEN ? trimmed.substring(0, TRACE_MAX_LEN) : trimmed;
    }
}
