package com.occ.smartexpense.vo;

import lombok.Data;

import java.util.List;

/** 管理端用户列表出参：用户基本信息 + 角色 code 列表 */
@Data
public class UserRoleVO {

    private Long id;

    private String username;

    private String realName;

    private String deptName;

    /** 1 在职 / 0 离职 */
    private Integer status;

    private List<String> roles;
}
