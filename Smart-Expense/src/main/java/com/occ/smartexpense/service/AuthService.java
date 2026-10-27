package com.occ.smartexpense.service;

import com.occ.smartexpense.dto.LoginDTO;
import com.occ.smartexpense.vo.LoginVO;
import com.occ.smartexpense.vo.UserVO;

/**
 * 认证服务，见 docs/12 §4。
 */
public interface AuthService {

    LoginVO login(LoginDTO dto);

    /** 当前登录用户（从 UserContext 取） */
    UserVO me();
}
