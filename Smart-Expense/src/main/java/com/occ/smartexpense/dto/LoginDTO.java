package com.occ.smartexpense.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 登录入参，见 docs/12 §4.1 */
@Data
public class LoginDTO {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;
}
