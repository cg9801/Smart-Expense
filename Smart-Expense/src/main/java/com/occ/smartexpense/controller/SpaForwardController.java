package com.occ.smartexpense.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 单端口部署的 SPA 路由回退（docs/14 §16.5）。
 *
 * <p>前端用 history 模式：直接刷新 /reports/613 时浏览器会请求这个路径，
 * 静态资源服务器找不到文件 → 404。这里把前端路由转发到 index.html，由 Vue Router 接管。
 * 只列前端路由前缀，不匹配 /api、/actuator、/assets，避免吞掉接口与静态资源。
 */
@Controller
public class SpaForwardController {

    @GetMapping({"/", "/login", "/reports", "/reports/**", "/approvals", "/approvals/**",
            "/payments", "/payments/**", "/invoices", "/invoices/**", "/admin", "/admin/**"})
    public String forwardToIndex() {
        return "forward:/index.html";
    }
}
