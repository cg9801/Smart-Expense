package com.occ.smartexpense.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户 sys_user，字段定义见 sql/schema.sql §1。
 * manager_user_id 是审批链第 1 级的来源，dept_id 用于快照部门名。
 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String employeeNo;

    private String username;

    private String realName;

    private String passwordHash;

    private String email;

    private Long deptId;

    /** 直属主管用户 id，审批链第 1 级来源 */
    private Long managerUserId;

    /** 1 在职 / 0 离职 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
