package com.occ.smartexpense.security;

import com.occ.smartexpense.dto.ReportCreateDTO;
import com.occ.smartexpense.dto.ReportItemDTO;
import com.occ.smartexpense.service.ReportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 鉴权边界（docs/12 §11）：走真实 Filter + 拦截器 + GlobalExceptionHandler，
 * 覆盖"无 token 401 / 员工 403 / 越权看他人详情 403"三条最容易出事的路径。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthzTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private ReportService reportService;

    @Test
    void no_token_is_unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/reports"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    void employee_cannot_confirm_payment() throws Exception {
        // @RequireRole 在拦截器里就拦掉，不需要真实的付款单 id
        mockMvc.perform(post("/api/v1/payments/1/confirm")
                        .header("Authorization", bearer(1L, "linyi", "EMPLOYEE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    void employee_cannot_read_others_report() throws Exception {
        Long reportId = draftForLinyi();

        mockMvc.perform(get("/api/v1/reports/" + reportId)
                        .header("Authorization", bearer(3L, "lisi", "EMPLOYEE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    void forbidden_request_does_not_leak_identity() throws Exception {
        // 第一步：带 token 调需要 FINANCE 的接口 → 拦截器 set 身份后抛 403（异常路径）
        mockMvc.perform(post("/api/v1/payments/1/confirm")
                        .header("Authorization", bearer(1L, "linyi", "EMPLOYEE")))
                .andExpect(status().isForbidden());

        // 第二步：同一线程、不带任何凭证 → 必须 401；若 ThreadLocal 没清，这里会读到上一个用户
        mockMvc.perform(get("/api/v1/reports"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    void owner_can_read_own_report() throws Exception {
        Long reportId = draftForLinyi();

        mockMvc.perform(get("/api/v1/reports/" + reportId)
                        .header("Authorization", bearer(1L, "linyi", "EMPLOYEE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(reportId));
    }

    private String bearer(Long userId, String username, String... roles) {
        return "Bearer " + jwtUtil.issue(userId, username, List.of(roles));
    }

    private Long draftForLinyi() {
        ReportItemDTO item = new ReportItemDTO();
        item.setExpenseDate(LocalDate.of(2026, 10, 18));
        item.setCategory("交通");
        item.setAmount(new BigDecimal("3200.00"));
        item.setDescription("AuthzTest");

        ReportCreateDTO dto = new ReportCreateDTO();
        dto.setTitle("鉴权测试单");
        dto.setExpenseType("TRAVEL");
        dto.setItems(List.of(item));
        return reportService.createDraft(dto, 1L, 2L).getId();
    }
}
