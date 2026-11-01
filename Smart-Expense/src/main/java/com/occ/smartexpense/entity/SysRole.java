package com.occ.smartexpense.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 角色 sys_role，字段见 sql/schema.sql §1。 */
@Data
@TableName("sys_role")
public class SysRole {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** EMPLOYEE / SUPERVISOR / FINANCE / DIRECTOR / ADMIN */
    private String code;

    private String name;

    private LocalDateTime createdAt;
}
