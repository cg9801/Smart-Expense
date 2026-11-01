package com.occ.smartexpense.service;

import com.occ.smartexpense.dto.LoginDTO;
import com.occ.smartexpense.vo.LoginVO;
import com.occ.smartexpense.vo.UserVO;

/**
 * 认证服务，见 docs/12 §4。
 */
public interface AuthService {

    /**
     * 登录：校验密码并签发 JWT。
     *
     * @param clientIp 客户端 IP，仅用于登录失败限流（用户名 + IP 双维度，docs/12 §9）
     */
    LoginVO login(LoginDTO dto, String clientIp);

    /** 当前登录用户（从 UserContext 取） */
    UserVO me();
}
