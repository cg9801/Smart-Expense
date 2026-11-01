package com.occ.smartexpense.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 用户-角色关联 sys_user_role，字段见 sql/schema.sql §1。
 * 复合主键 (user_id, role_id)，无自增 id，增删都按 user_id 整批覆盖。
 */
@Data
@TableName("sys_user_role")
public class SysUserRole {

    private Long userId;

    private Long roleId;
}
