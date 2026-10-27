package com.occ.smartexpense.vo;

import lombok.Data;

/** 登录出参，见 docs/12 §4.1 */
@Data
public class LoginVO {

    private String token;

    private String tokenType = "Bearer";

    /** 有效期（秒） */
    private long expiresIn;

    private UserVO user;
}
