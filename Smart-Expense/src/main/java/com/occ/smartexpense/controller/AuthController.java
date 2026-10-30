package com.occ.smartexpense.controller;

import com.occ.smartexpense.common.Result;
import com.occ.smartexpense.dto.LoginDTO;
import com.occ.smartexpense.service.AuthService;
import com.occ.smartexpense.vo.LoginVO;
import com.occ.smartexpense.vo.UserVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 认证接口，见 docs/12 §4。
 * /auth/login 在拦截器放行列表里；/auth/me 需要合法身份。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return Result.ok(authService.login(dto));
    }

    @GetMapping("/me")
    public Result<UserVO> me() {
        return Result.ok(authService.me());
    }
}
