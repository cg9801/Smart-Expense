package com.occ.smartexpense.vo;

import lombok.Data;

import java.util.List;

/** 当前用户出参，不含密码等敏感字段 */
@Data
public class UserVO {

    private Long id;

    private String username;

    private String realName;

    private Long deptId;

    private List<String> roles;
}
