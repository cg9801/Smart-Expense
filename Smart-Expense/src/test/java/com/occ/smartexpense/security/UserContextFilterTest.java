package com.occ.smartexpense.security;

import com.occ.smartexpense.common.BizException;
import com.occ.smartexpense.common.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求级上下文的清理语义（docs/12 §6）：
 * 无论链路是否抛异常，ThreadLocal 与 MDC 都必须在 finally 里清掉，否则 Tomcat 复用线程会串号。
 */
class UserContextFilterTest {

    private final UserContextFilter filter = new UserContextFilter();

    @Test
    void clears_user_context_even_when_chain_throws() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reports");
        request.addHeader("X-Trace-Id", "trace-abc");
        MockHttpServletResponse response = new MockHttpServletResponse();
        UserContext.set(new CurrentUser(1L, "linyi", "林一", 2L, Set.of("EMPLOYEE")));

        FilterChain chain = (req, res) -> {
            throw new ServletException("boom");
        };

        assertThrows(ServletException.class, () -> filter.doFilter(request, response, chain));
        assertNull(UserContext.get(), "异常路径也必须清理 ThreadLocal");
        assertNull(MDC.get("traceId"), "异常路径也必须清理 MDC");
    }

    @Test
    void clears_user_context_when_interceptor_throws_biz_exception() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/payments/1/confirm");
        MockHttpServletResponse response = new MockHttpServletResponse();
        UserContext.set(new CurrentUser(1L, "linyi", "林一", 2L, Set.of("EMPLOYEE")));

        // 真实的 403 路径：拦截器先 set 身份、角色不足再抛 BizException，异常必然穿过 Filter
        FilterChain chain = (req, res) -> {
            throw new BizException(ErrorCode.FORBIDDEN, "需要角色 FINANCE/ADMIN");
        };

        assertThrows(BizException.class, () -> filter.doFilter(request, response, chain));
        assertNull(UserContext.get(), "403 路径也必须清理 ThreadLocal");
    }

    @Test
    void echoes_trace_id_header() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reports");
        request.addHeader("X-Trace-Id", "trace-abc");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            assertEquals("trace-abc", MDC.get("traceId"));
            assertEquals("trace-abc", ((MockHttpServletResponse) res).getHeader("X-Trace-Id"));
        });

        assertNotNull(response.getHeader("X-Trace-Id"));
    }

    @Test
    void generates_short_trace_id_when_missing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reports");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        String traceId = response.getHeader("X-Trace-Id");
        assertNotNull(traceId);
        assertTrue(traceId.length() > 0 && traceId.length() <= 64);
    }
}
