package com.occ.smartexpense.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 部门 sys_dept，字段定义见 sql/schema.sql §1。
 * 表主键没有 AUTO_INCREMENT，因此显式使用 IdType.INPUT（当前只读，不做插入）。
 */
@Data
@TableName("sys_dept")
public class SysDept {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long parentId;

    private String name;

    /** 部门负责人，审批链解析用 */
    private Long leaderUserId;

    /** 祖级路径，如 0,1 */
    private String ancestors;

    private Integer sortNo;

    /** 1 启用 / 0 停用 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
